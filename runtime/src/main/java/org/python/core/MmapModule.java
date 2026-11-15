package org.python.core;

/**
 * {@code mmap}: the module's constants, its {@code mmap} type, and {@code error}.
 *
 * <p>Every number here is macOS's, which is the reference this runtime is measured against: the
 * access constants are CPython's own, the {@code MAP_*} and {@code PROT_*} bits are the platform's
 * {@code <sys/mman.h>}, and {@code PAGESIZE} with {@code ALLOCATIONGRANULARITY} is the page the
 * kernel demands an offset to sit on.
 */
final class MmapModule {

    private MmapModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("mmap");
        PyRuntime.markBuiltIn(module, "mmap");
        module.set("__doc__", PyNone.INSTANCE);
        module.set("mmap", PyType.MMAP);
        module.set("error", ExceptionTypes.OS_ERROR);
        module.set("ACCESS_DEFAULT", PyInt.of(PyMmap.ACCESS_DEFAULT));
        module.set("ACCESS_READ", PyInt.of(PyMmap.ACCESS_READ));
        module.set("ACCESS_WRITE", PyInt.of(PyMmap.ACCESS_WRITE));
        module.set("ACCESS_COPY", PyInt.of(PyMmap.ACCESS_COPY));
        module.set("MAP_SHARED", PyInt.of(PyMmap.MAP_SHARED));
        module.set("MAP_PRIVATE", PyInt.of(PyMmap.MAP_PRIVATE));
        module.set("MAP_ANON", PyInt.of(PyMmap.MAP_ANONYMOUS));
        module.set("MAP_ANONYMOUS", PyInt.of(PyMmap.MAP_ANONYMOUS));
        module.set("MAP_NOEXTEND", PyInt.of(256));
        module.set("MAP_HASSEMAPHORE", PyInt.of(512));
        module.set("MAP_NORESERVE", PyInt.of(64));
        module.set("MAP_NOCACHE", PyInt.of(1024));
        module.set("MAP_JIT", PyInt.of(2048));
        module.set("MAP_32BIT", PyInt.of(32768));
        module.set("MAP_RESILIENT_CODESIGN", PyInt.of(8192));
        module.set("MAP_RESILIENT_MEDIA", PyInt.of(16384));
        module.set("MAP_TPRO", PyInt.of(524288));
        module.set("MAP_TRANSLATED_ALLOW_EXECUTE", PyInt.of(131072));
        module.set("MAP_UNIX03", PyInt.of(262144));
        module.set("PROT_READ", PyInt.of(PyMmap.PROT_READ));
        module.set("PROT_WRITE", PyInt.of(PyMmap.PROT_WRITE));
        module.set("PROT_EXEC", PyInt.of(4));
        module.set("PAGESIZE", PyInt.of(PyMmap.PAGESIZE));
        module.set("ALLOCATIONGRANULARITY", PyInt.of(PyMmap.PAGESIZE));
        module.set("MADV_NORMAL", PyInt.of(0));
        module.set("MADV_RANDOM", PyInt.of(1));
        module.set("MADV_SEQUENTIAL", PyInt.of(2));
        module.set("MADV_WILLNEED", PyInt.of(3));
        module.set("MADV_DONTNEED", PyInt.of(4));
        module.set("MADV_FREE", PyInt.of(5));
        module.set("MADV_FREE_REUSABLE", PyInt.of(7));
        module.set("MADV_FREE_REUSE", PyInt.of(8));
        return module;
    }
}
