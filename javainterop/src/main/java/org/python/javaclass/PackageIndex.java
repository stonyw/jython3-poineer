package org.python.javaclass;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Which Java packages the JVM can see, without a {@code jrt:} scan.
 *
 * <p>Jython 2.7 reads the JDK by walking the {@code jrt:/} file system with ASM and caches the result in
 * a {@code packages.idx} resource. That works, but it is a second, hand-maintained view of the module
 * system, and on a JDK 21 runtime it is unnecessary: {@link Module#getPackages()} answers "what packages
 * does this module have" and the boot layer knows every module in the image. What is still missing is
 * the application class path, and that is a directory listing plus one {@link JarFile} per entry — built
 * lazily, on the first import that needs it, so a script that never mentions {@code java} never pays.
 *
 * <p>Two limits, both deliberate. {@code getPackages()} lists packages a module does not export as well
 * as ones it does, so {@code import sun.misc} resolves here even where a compiled Java program would be
 * refused; the JPMS boundary is M2-c work and will be enforced at the call, not at the import. And a
 * class reachable only through a loader built at run time is invisible to a {@code java.class.path}
 * index, so that caller has to say so — the loader this index uses is the one that loaded interop itself.
 */
final class PackageIndex {

    /** Every package name, and every dotted head of one, so {@code java} is there for {@code java.util}. */
    private final Set<String> packages = new LinkedHashSet<>();

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

    /** Take {@code org/python/interop/fixtures/Widget.class} apart and keep only the package. */
    private void record(String slashSeparatedPath) {
        int cut = slashSeparatedPath.lastIndexOf('/');
        if (cut <= 0) {
            return; // the unnamed package, which no Python import can name anyway
        }
        String simple = slashSeparatedPath.substring(cut + 1, slashSeparatedPath.length() - ".class".length());
        if (simple.equals("module-info") || simple.startsWith("package-info")) {
            return;
        }
        add(slashSeparatedPath.substring(0, cut).replace('/', '.'));
    }

    /** {@code java.util.concurrent} also makes {@code java}, {@code java.util} and itself a package. */
    private void add(String dotted) {
        int at = 0;
        while ((at = dotted.indexOf('.', at + 1)) > 0) {
            packages.add(dotted.substring(0, at));
        }
        packages.add(dotted);
    }
}
