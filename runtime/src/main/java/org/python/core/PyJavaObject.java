package org.python.core;

/**
 * A plain Java value, as Python sees one.
 *
 * <p>Jython 2 answers a Java object with a {@code PyJavaInstance}, which knows the object's class and
 * reaches its methods. This runtime has no Java bridge, so a value the driver hands over that has no
 * Python counterpart — {@code java.sql.Date}, a {@code BigDecimal} a driver reported as
 * {@code JAVA_OBJECT}, a {@link java.sql.Driver} from {@code lookup()} — is carried in this wrapper
 * instead. It answers {@code str()} and {@code repr()} and nothing else; handing it back as a
 * parameter gives the driver the object it came from, because {@link ZxJdbcData#bind} unwraps it.
 */
final class PyJavaObject extends PyObject {

    private final Object value;

    PyJavaObject(Object value) {
        super(ZxJdbcModule.JAVA_OBJECT_TYPE);
        this.value = value;
    }

    Object value() {
        return value;
    }

    /** The Java value's own {@code toString}, which is what Jython 2's bridge also answers. */
    @Override
    public String str() {
        return String.valueOf(value);
    }

    @Override
    public String repr() {
        return "<java "
                + value.getClass().getName()
                + " object at 0x"
                + Integer.toHexString(System.identityHashCode(value))
                + ">";
    }
}
