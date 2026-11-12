package org.python.core;

/**
 * The {@code _collections} module: the two types {@code collections} cannot write in Python.
 *
 * <p>Only {@code deque} is here. The reference's module also holds {@code defaultdict}, {@code OrderedDict}
 * and the cursor over a deque; {@code collections} imports each of those inside a {@code try} and keeps its
 * own Python implementation when the import fails, so their absence costs nothing this round. The
 * {@code deque} is not replaceable that way — {@code collections} registers it with
 * {@code MutableSequence} and {@code contextlib.ExitStack} is built on it.
 */
final class CollectionsModule {

    private CollectionsModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_collections");
        PyRuntime.markBuiltIn(module, "_collections");
        module.set("deque", PyType.DEQUE);
        return module;
    }
}
