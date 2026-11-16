package org.python.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * What crosses between a driver's values and Python's.
 *
 * <p>Jython 2 puts this in {@code DataHandler}, whose base class reads a result set column by its
 * {@link Types} code and binds a Python value with {@code setObject}. The mapping here is the same one:
 * a number is a number, text is {@code str}, binary is {@code bytes}, and a date, a time or a timestamp
 * is carried as the {@link java.sql} value it is — there is no Java bridge to hand it to, so
 * {@link PyJavaObject} stands in, and binding one hands the driver back exactly what it gave.
 *
 * <p>The mapping is a reading of the driver's declared column type, not a translation of the SQL. A
 * driver that reports {@code OTHER} for a type gets a {@link PyJavaObject} holding that value, and every
 * {@link SQLException} escapes as it was thrown; nothing here rewrites SQL or invents a value.
 */
final class ZxJdbcData {

    private ZxJdbcData() {
    }

    /** The DB-API {@code description} rows for a result set, in Jython 2's seven-field shape. */
    static PyObject describe(ResultSetMetaData meta) throws SQLException {
        PyList description = PyList.empty();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            int type = meta.getColumnType(i);
            boolean numeric =
                    switch (type) {
                        case Types.BIGINT,
                                Types.BIT,
                                Types.DECIMAL,
                                Types.NUMERIC,
                                Types.DOUBLE,
                                Types.FLOAT,
                                Types.INTEGER,
                                Types.SMALLINT,
                                Types.TINYINT ->
                                true;
                        default -> false;
                    };
            description.add(
                    PyTuple.of(
                            PyStr.of(meta.getColumnLabel(i)),
                            PyInt.of(type),
                            PyInt.of(meta.getColumnDisplaySize(i)),
                            PyNone.INSTANCE,
                            numeric ? PyInt.of(meta.getPrecision(i)) : PyNone.INSTANCE,
                            numeric ? PyInt.of(meta.getScale(i)) : PyNone.INSTANCE,
                            PyInt.of(meta.isNullable(i))));
        }
        return description;
    }

    /** One column of the current row, read by the type the driver declared for it. */
    static PyObject read(ResultSet set, int column, int type) throws SQLException {
        switch (type) {
            case Types.NULL:
                return PyNone.INSTANCE;
            case Types.BIT:
            case Types.BOOLEAN:
                {
                    boolean value = set.getBoolean(column);
                    return set.wasNull() ? PyNone.INSTANCE : PyBool.of(value);
                }
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
                {
                    int value = set.getInt(column);
                    return set.wasNull() ? PyNone.INSTANCE : PyInt.of(value);
                }
            case Types.BIGINT:
                {
                    long value = set.getLong(column);
                    return set.wasNull() ? PyNone.INSTANCE : PyInt.of(value);
                }
            case Types.REAL:
            case Types.FLOAT:
                {
                    float value = set.getFloat(column);
                    return set.wasNull() ? PyNone.INSTANCE : PyFloat.of(value);
                }
            case Types.DOUBLE:
                {
                    double value = set.getDouble(column);
                    return set.wasNull() ? PyNone.INSTANCE : PyFloat.of(value);
                }
            case Types.NUMERIC:
            case Types.DECIMAL:
                {
                    BigDecimal value = set.getBigDecimal(column);
                    return value == null ? PyNone.INSTANCE : PyFloat.of(value.doubleValue());
                }
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
                {
                    String value = set.getString(column);
                    return value == null ? PyNone.INSTANCE : PyStr.of(value);
                }
            case Types.DATE:
                {
                    java.sql.Date value = set.getDate(column);
                    return value == null ? PyNone.INSTANCE : new PyJavaObject(value);
                }
            case Types.TIME:
                {
                    java.sql.Time value = set.getTime(column);
                    return value == null ? PyNone.INSTANCE : new PyJavaObject(value);
                }
            case Types.TIMESTAMP:
                {
                    java.sql.Timestamp value = set.getTimestamp(column);
                    return value == null ? PyNone.INSTANCE : new PyJavaObject(value);
                }
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                {
                    byte[] value = set.getBytes(column);
                    return value == null ? PyNone.INSTANCE : PyBytes.of(value);
                }
            case Types.BLOB:
                {
                    // Jython 2 hands the java.sql.Blob back as a PyJavaInstance. There is no Java bridge
                    // here to make one useful, so the bytes are read out instead — and through getBytes,
                    // which is what drivers such as sqlite-jdbc implement where getBlob is refused.
                    byte[] value = set.getBytes(column);
                    return value == null ? PyNone.INSTANCE : PyBytes.of(value);
                }
            case Types.CLOB:
            case Types.NCLOB:
                {
                    // The same choice for a Clob: Jython 2 returns the object, this returns its text.
                    String value = set.getString(column);
                    return value == null ? PyNone.INSTANCE : PyStr.of(value);
                }
            case Types.OTHER:
            case Types.JAVA_OBJECT:
                {
                    Object value = set.getObject(column);
                    return value == null ? PyNone.INSTANCE : new PyJavaObject(value);
                }
            case Types.ARRAY:
            case Types.DATALINK:
            case Types.DISTINCT:
            case Types.REF:
            case Types.ROWID:
            case Types.STRUCT:
                throw PyRaise.raise(
                        ZxJdbcModule.NOT_SUPPORTED_ERROR,
                        "unsupported column type for column " + column + ": " + type);
            default:
                return generic(set.getObject(column));
        }
    }

    /** A value the driver declared with a type this table does not name, mapped by its Java class. */
    private static PyObject generic(Object value) {
        if (value == null) {
            return PyNone.INSTANCE;
        }
        if (value instanceof Boolean flag) {
            return PyBool.of(flag);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return PyInt.of(((Number) value).longValue());
        }
        if (value instanceof BigInteger integer) {
            return PyInt.of(integer);
        }
        if (value instanceof Number number) {
            return PyFloat.of(number.doubleValue());
        }
        if (value instanceof String text) {
            return PyStr.of(text);
        }
        if (value instanceof byte[] bytes) {
            return PyBytes.of(bytes);
        }
        return new PyJavaObject(value);
    }

    /** Binds every value of a parameter sequence to the statement, starting at index 1. */
    static void bindAll(PreparedStatement statement, PyObject params) throws SQLException {
        PyIterator iterator = Slots.iterator(params);
        int index = 1;
        for (PyObject value = iterator.nextOrNull(); value != null; value = iterator.nextOrNull()) {
            bind(statement, index++, value);
        }
    }

    /** One parameter, in the JDBC call that fits the Python value. */
    static void bind(PreparedStatement statement, int index, PyObject value) throws SQLException {
        if (value == PyNone.INSTANCE) {
            statement.setObject(index, null);
            return;
        }
        if (value instanceof PyBool flag) {
            statement.setBoolean(index, flag.getValue());
            return;
        }
        if (value instanceof PyInt integer) {
            BigInteger number = integer.getValue();
            if (number.bitLength() < 64) {
                statement.setLong(index, number.longValue());
            } else {
                statement.setBigDecimal(index, new BigDecimal(number));
            }
            return;
        }
        if (value instanceof PyFloat number) {
            statement.setDouble(index, number.getValue());
            return;
        }
        if (value instanceof PyStr text) {
            statement.setString(index, text.getValue());
            return;
        }
        if (value instanceof PyBytes bytes) {
            statement.setBytes(index, bytes.getValue());
            return;
        }
        if (value instanceof PyJavaObject java) {
            statement.setObject(index, java.value());
            return;
        }
        throw PyRaise.raise(
                ZxJdbcModule.PROGRAMMING_ERROR,
                "unsupported parameter type: " + value.getType().filedName());
    }
}
