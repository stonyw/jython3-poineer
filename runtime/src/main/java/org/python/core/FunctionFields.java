package org.python.core;

/**
 * The two fields of CPython's {@code PyCFunctionObject} that its own dictionary reads.
 *
 * <p>Three things in this runtime are {@code builtin_function_or_method}s, and they are made three
 * different ways: a function a module's dictionary holds, the entry a type's dictionary keeps under {@code
 * __new__}, and a method a read bound to a value or to a class. All three answer the same five attributes
 * and the same slot wrappers, because {@code methodobject.c} has one record behind all of them — {@code
 * m_self}, which the receiver-shaped reads quote, and {@code m_ml}, which is only ever compared and is
 * nowhere to be seen from Python.
 *
 * <p>{@code m_module} is the third field of the struct and the only one a program can write. It is the
 * name {@code __module__} reads, it starts out empty for a function that a <em>type</em>'s table filed
 * rather than a module's, and {@code del} on the name does not remove the attribute but empties the field
 * it stands for — which is why {@code del int.mro.__module__} leaves the read answering {@code None} rather
 * than raising.
 */
interface FunctionFields {

    /** {@code m_self}: the class or module a function belongs to, or the value it is bound to. */
    PyObject functionSelf();

    /** {@code m_ml}: as much of the {@code PyMethodDef} as this runtime keeps, compared by identity. */
    Object functionDefinition();

    /** {@code m_module}, which is the name {@code __module__} reads; null reads as {@code None}. */
    PyObject moduleValue();

    /** {@code PyMember_SetOne} over that field, with null for the value {@code del} hands it. */
    void writeModule(PyObject value);
}
