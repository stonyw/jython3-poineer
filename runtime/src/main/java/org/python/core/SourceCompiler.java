package org.python.core;

/**
 * What the import system asks for when a {@code .py} file has to become a code object.
 *
 * <p>The runtime owns the protocol and the front end owns the compiler, and design D2 keeps the
 * dependency pointing that way: {@code runtime} may not reach into {@code front-cpython314}. A build
 * that embeds the runtime hands a compiler over with {@link PyRuntime#setSourceCompiler}; the launcher
 * and the embedding API each do, because each already depends on the front end.
 */
public interface SourceCompiler {

    /** Compile {@code source} as a module's top level, reporting errors against {@code fileName}. */
    PyCode compile(String source, String fileName);
}
