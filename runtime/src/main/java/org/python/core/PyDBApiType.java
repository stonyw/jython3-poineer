package org.python.core;

import java.math.BigInteger;

/**
 * {@code zxJDBC.STRING} and its kin: Jython 2's {@code DBApiType}, an {@code int} that says it is an SQL type.
 *
 * <p>The reference's type is a {@code PyInteger} subclass with nothing added but identity, and that
 * identity is the observable part: {@code zxJDBC.VARCHAR == 12} holds, a constant is usable where an
 * integer is, and {@code type(zxJDBC.STRING).__name__} answers {@code DBApiType} rather than
 * {@code int}. Arithmetic on a constant hands back a plain {@code int} in the reference for the same
 * reason, because the result is no longer a declared type.
 */
final class PyDBApiType extends PyInt {

    /** The one type every SQL-type constant is an instance of. */
    static final PyType TYPE = PyType.declare("DBApiType", PyType.INT).filedUnder("zxJDBC.DBApiType");

    PyDBApiType(int value) {
        super(BigInteger.valueOf(value), TYPE);
    }
}
