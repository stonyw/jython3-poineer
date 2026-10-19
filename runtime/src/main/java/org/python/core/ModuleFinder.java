package org.python.core;

/**
 * Something that can answer {@code import name} for a name the runtime does not build itself.
 *
 * <p>The runtime owns no knowledge of what it cannot already import — a {@code .pyc} on
 * {@code sys.path} is the M4 finder, and a Java package is the interop module's — so each of them is
 * installed here rather than decided here. Returning {@code null} is not an error: it means "this name
 * is not mine", and the next finder or, at the end of the chain, {@code ModuleNotFoundError} decides.
 */
public interface ModuleFinder {

    /**
     * @param dotted the full dotted name asked for, which a finder that owns a prefix may be asked to
     *     split itself — {@code java.util} arrives whole, not as {@code java} then {@code util}
     * @return the module, or null to pass
     */
    PyObject find(String dotted);
}
