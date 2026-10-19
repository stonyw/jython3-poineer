package org.python.javaclass;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Which Java packages the JVM can see, and what is in them.
 *
 * <p>Jython 2.7 reads the JDK by walking the {@code jrt:/} file system with ASM and caches the result in
 * a {@code packages.idx} resource. That works, but it is a second, hand-maintained view of the module
 * system, and on a JDK 21 runtime it is unnecessary: {@link Module#getPackages()} answers "what packages
 * does this module have" and the boot layer knows every module in the image. What is still missing is
 * the application class path, and that is a directory listing plus one {@link JarFile} per entry — built
 * lazily, on the first import that needs it, so a script that never mentions {@code java} never pays.
 *
 * <p>Class names are read the same way but only for the package a star import asks about, which is the
 * one place the whole of a package has to be known at once. There the class path's listing is kept from
 * the scan that found the packages, and a JDK package's is one {@code jrt:} directory read — no bytecode
 * is parsed, because {@code __all__} needs names rather than signatures.
 *
 * <p>Two limits, both deliberate. {@code getPackages()} lists packages a module does not export as well
 * as ones it does, so {@code import sun.misc} resolves here even where a compiled Java program would be
 * refused; the JPMS boundary is enforced at the call that needs the access, not at the import. And a
 * class reachable only through a loader built at run time is invisible to a {@code java.class.path}
 * index, so that caller has to say so — the loader this index uses is the one that loaded interop itself.
 */
final class PackageIndex {

    /** Every package name, and every dotted head of one, so {@code java} is there for {@code java.util}. */
    private final Set<String> packages = new LinkedHashSet<>();

    /** Package to the classes found under it on the class path, nested classes left out. */
    private final Map<String, Set<String>> classPathClasses = new LinkedHashMap<>();

    /** Package to the named module holding it, which is the address a {@code jrt:} listing needs. */
    private final Map<String, String> moduleOf = new LinkedHashMap<>();

    /** Package to its published names, computed on the first star import and kept for the rest of the run. */
    private final Map<String, List<String>> members = new LinkedHashMap<>();

    private static volatile PackageIndex instance;

    private PackageIndex() {
    }

    private static PackageIndex get() {
        PackageIndex local = instance;
        if (local == null) {
            synchronized (PackageIndex.class) {
                local = instance;
                if (local == null) {
                    local = new PackageIndex();
                    local.readModules();
                    local.readClassPath();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Whether {@code dotted} is a Java package a Python import may name. */
    static boolean isPackage(String dotted) {
        return get().packages.contains(dotted);
    }

    /**
     * The names {@code from <dotted> import *} binds: the classes the package holds and the packages under
     * it, sorted.
     *
     * <p>Sorted, because a star import is a Python statement and two runs of one script should bind the
     * same names in the same order — {@code Files.list} makes no promise about the order it reads a
     * directory in, and a class path is a list of roots rather than one tree.
     */
    static List<String> membersOf(String dotted) {
        return get().memberNames(dotted);
    }

    /**
     * Load a class by its binary name, without initializing it.
     *
     * <p>Null rather than an exception, because a caller has to try {@code Widget.Builder} and
     * {@code Widget$Builder} and {@code widget} before it can say what the name was meant for, and it is
     * the caller that knows how to phrase the failure.
     */
    static Class<?> load(String binaryName) {
        try {
            return Class.forName(binaryName, false, PackageIndex.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /** The package half of a class name, which is what {@code __module__} of a Java class reports. */
    static String packageOf(Class<?> type) {
        String binary = type.getName();
        int lastDot = binary.lastIndexOf('.');
        return lastDot < 0 ? "" : binary.substring(0, lastDot);
    }

    // --- building ------------------------------------------------------------------------

    private void readModules() {
        // The boot layer holds every module in the image plus the unnamed module that reads the class
        // path, so this one call covers the JDK without walking jrt:/ at all.
        for (Module module : ModuleLayer.boot().modules()) {
            for (String name : module.getPackages()) {
                add(name);
                if (module.isNamed()) {
                    moduleOf.putIfAbsent(name, module.getName());
                }
            }
        }
    }

    private void readClassPath() {
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path file = Path.of(entry);
            if (Files.isDirectory(file)) {
                readDirectory(file);
            } else if (entry.endsWith(".jar")) {
                readJar(file);
            }
        }
    }

    private void readDirectory(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".class"))
                    .forEach(classFile -> record(root.relativize(classFile).toString()));
        } catch (IOException unreadable) {
            // A class path entry that cannot be listed is a missing package, not a broken run.
        }
    }

    private void readJar(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".class")) {
                    record(name);
                }
            }
        } catch (IOException unreadable) {
            // As above: one unreadable jar must not make `import java.util` fail.
        }
    }

    /** Keep {@code org/python/interop/fixtures/Widget.class} as a class in its package. */
    private void record(String slashSeparatedPath) {
        int cut = slashSeparatedPath.lastIndexOf('/');
        if (cut <= 0) {
            return; // the unnamed package, which no Python import can name anyway
        }
        String fileName = slashSeparatedPath.substring(cut + 1);
        if (isDescriptor(fileName)) {
            return; // a module's descriptor is not evidence that a package exists
        }
        String dotted = slashSeparatedPath.substring(0, cut).replace('/', '.');
        add(dotted);
        String simple = simpleClassName(fileName);
        if (simple != null) {
            classPathClasses.computeIfAbsent(dotted, key -> new LinkedHashSet<>()).add(simple);
        }
    }

    /** {@code java.util.concurrent} also makes {@code java}, {@code java.util} and itself a package. */
    private void add(String dotted) {
        int at = 0;
        while ((at = dotted.indexOf('.', at + 1)) > 0) {
            packages.add(dotted.substring(0, at));
        }
        packages.add(dotted);
    }

    // --- members --------------------------------------------------------------------------

    private synchronized List<String> memberNames(String dotted) {
        List<String> cached = members.get(dotted);
        if (cached == null) {
            cached = List.copyOf(found(dotted));
            members.put(dotted, cached);
        }
        return cached;
    }

    private Set<String> found(String dotted) {
        Set<String> names = new TreeSet<>();
        Set<String> onPath = classPathClasses.get(dotted);
        if (onPath != null) {
            names.addAll(onPath);
        }
        String prefix = dotted + ".";
        for (String candidate : packages) {
            if (candidate.startsWith(prefix) && candidate.indexOf('.', prefix.length()) < 0) {
                names.add(candidate.substring(prefix.length()));
            }
        }
        String module = moduleOf.get(dotted);
        if (module != null) {
            names.addAll(readImage(module, dotted));
        }
        return names;
    }

    /**
     * The classes one JDK package holds, read from the runtime image.
     *
     * <p>An empty answer is the right answer for anything that cannot be read: a package with no classes
     * in it — {@code java} itself, which only holds sub-packages — is legal, and one custom image that
     * has no {@code jrt:} provider must not cost every import its error message.
     */
    private static Set<String> readImage(String module, String dotted) {
        Set<String> names = new LinkedHashSet<>();
        try {
            FileSystem image = FileSystems.getFileSystem(URI.create("jrt:/"));
            try (Stream<Path> listing =
                    Files.list(image.getPath("/modules", module, dotted.replace('.', '/')))) {
                listing.filter(path -> !Files.isDirectory(path))
                        .map(path -> simpleClassName(path.getFileName().toString()))
                        .filter(Objects::nonNull)
                        .forEach(names::add);
            }
        } catch (IOException | RuntimeException unreadable) {
            return Set.of();
        }
        return names;
    }

    /**
     * {@code Widget.class} as {@code Widget}, or null for a file no Python name can spell.
     *
     * <p>{@code Widget$Builder} is the case that matters: a nested class is real and importable, but only
     * as {@code Widget.Builder}, which is exactly how Java reaches it. A star import that bound a name with
     * a {@code $} in it would put a word into the namespace that no expression could write.
     */
    private static String simpleClassName(String fileName) {
        if (!fileName.endsWith(".class") || fileName.contains("$") || isDescriptor(fileName)) {
            return null;
        }
        return fileName.substring(0, fileName.length() - ".class".length());
    }

    /** {@code module-info} and {@code package-info} describe a package; neither is a class to bind. */
    private static boolean isDescriptor(String fileName) {
        return fileName.equals("module-info.class") || fileName.startsWith("package-info");
    }
}
