package org.python.core;

/**
 * The marker an async generator's body puts around every value it yields.
 *
 * <p>CPython's {@code _PyAsyncGenWrappedValue}: a yield in an {@code async def} body compiles to
 * {@code CALL_INTRINSIC_1 4} before the {@code YIELD_VALUE}, and the awaitable that steps the body
 * unwraps it. The distinction the marker carries is the whole reason it exists — a value the body
 * yielded is what {@code asend} reports through {@code StopIteration}, while a value that reached the
 * step by any other route is passed through as it stands.
 *
 * <p>It is not reachable from Python: the type is the runtime's own bookkeeping, and nothing but
 * {@link PyAsyncGenASend} and {@link PyAsyncGenAThrow} ever sees one.
 */
final class PyAsyncGenWrapped extends PyObject {

    final PyObject value;

    PyAsyncGenWrapped(PyObject value) {
        super(PyType.OBJECT);
        this.value = value;
    }
}
