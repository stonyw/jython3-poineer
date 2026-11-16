package org.python.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code zxJDBC.Connection}: one JDBC connection behind the DB-API 2.0 connection methods.
 *
 * <p>The names are Jython 2's {@code PyConnection} ones: {@code close}, {@code commit}, {@code cursor},
 * {@code rollback}, {@code autocommit} and {@code nativesql}, plus the two context-manager hooks. So is
 * the transaction default: a driver that supports transactions is switched to manual commit when the
 * connection opens, because that is what {@code commit()} and {@code rollback()} are for.
 *
 * <p>{@code cursor()} takes Jython 2's three arguments — a dynamic flag and the two JDBC result-set
 * hints — and hands them to the statement the cursor makes.
 */
final class PyZxConnection extends PyObject {

    private static final Members TABLE = Members.of(ZxJdbcModule.CONNECTION_TYPE);

    static {
        TABLE.add("close", "Close the connection.", (self, args, kwNames) -> ((PyZxConnection) self).close(args, kwNames))
                .add("commit", "Commit the current transaction.", (self, args, kwNames) -> ((PyZxConnection) self).commit(args, kwNames))
                .add("rollback", "Roll back the current transaction.", (self, args, kwNames) -> ((PyZxConnection) self).rollback(args, kwNames))
                .add(
                        "cursor",
                        "cursor(dynamic=0, rsType=None, rsConcur=None) -> a new Cursor.",
                        (self, args, kwNames) -> ((PyZxConnection) self).cursor(args, kwNames))
                .add(
                        "nativesql",
                        "nativesql(sql) -> the driver's translation of sql.",
                        (self, args, kwNames) -> ((PyZxConnection) self).nativesql(args, kwNames))
                .add("__enter__", "The connection itself.", (self, args, kwNames) -> self)
                .add(
                        "__exit__",
                        "Close the connection.",
                        (self, args, kwNames) -> {
                            ((PyZxConnection) self).close(args, kwNames);
                            return PyNone.INSTANCE;
                        })
                .addField(
                        "autocommit",
                        "Whether each statement commits itself (1) or not (0).",
                        self -> ((PyZxConnection) self).autocommit());
    }

    private final Connection connection;

    private final List<PyZxCursor> cursors = new ArrayList<>();

    private boolean closed;

    PyZxConnection(Connection connection) {
        super(ZxJdbcModule.CONNECTION_TYPE);
        this.connection = connection;
        try {
            if (connection.getMetaData().supportsTransactions()) {
                connection.setAutoCommit(false);
            }
        } catch (SQLException unsupported) {
            // A driver that cannot answer keeps the mode it opened with; the field still reads it.
        }
    }

    Connection jdbc() {
        return connection;
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    @Override
    public PyObject setAttr(String name, PyObject value) {
        if (name.equals("autocommit")) {
            if (closed) {
                throw closed();
            }
            try {
                connection.setAutoCommit(Slots.truth(value));
            } catch (SQLException failure) {
                throw ZxJdbcModule.sqlException(failure);
            }
            return PyNone.INSTANCE;
        }
        return super.setAttr(name, value);
    }

    private PyObject autocommit() {
        try {
            return PyInt.of(connection.getAutoCommit() ? 1 : 0);
        } catch (SQLException failure) {
            throw ZxJdbcModule.sqlException(failure);
        }
    }

    private PyObject close(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("close", ZxJdbcModule.positional(args, kwNames), 0, 0);
        if (!closed) {
            closed = true;
            for (PyZxCursor cursor : cursors) {
                cursor.closedByConnection();
            }
            cursors.clear();
            try {
                connection.close();
            } catch (SQLException failure) {
                throw ZxJdbcModule.sqlException(failure);
            }
        }
        return PyNone.INSTANCE;
    }

    private PyObject commit(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("commit", ZxJdbcModule.positional(args, kwNames), 0, 0);
        if (closed) {
            throw closed();
        }
        try {
            connection.commit();
        } catch (SQLException failure) {
            throw ZxJdbcModule.sqlException(failure);
        }
        return PyNone.INSTANCE;
    }

    private PyObject rollback(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("rollback", ZxJdbcModule.positional(args, kwNames), 0, 0);
        if (closed) {
            throw closed();
        }
        try {
            connection.rollback();
        } catch (SQLException failure) {
            throw ZxJdbcModule.sqlException(failure);
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code cursor(dynamic=0, rsType=None, rsConcur=None)}.
     *
     * <p>{@code dynamic} keeps Jython 2's meaning: the rows are still read from the driver in one pass,
     * but {@code rowcount} counts what was fetched instead of what the query returned. The two JDBC
     * hints go to {@code createStatement} / {@code prepareStatement} when they are given.
     */
    private PyObject cursor(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("cursor", ZxJdbcModule.positional(args, kwNames), 0, 3);
        if (closed) {
            throw closed();
        }
        boolean dynamic = args.length > 0 && Slots.truth(args[0]);
        int rsType = args.length > 1 && args[1] != PyNone.INSTANCE ? ZxJdbcModule.integer(args[1]) : 0;
        int rsConcur =
                args.length > 2 && args[2] != PyNone.INSTANCE
                        ? ZxJdbcModule.integer(args[2])
                        : (rsType == 0 ? 0 : ResultSet.CONCUR_READ_ONLY);
        PyZxCursor cursor = new PyZxCursor(this, dynamic, rsType, rsConcur);
        cursors.add(cursor);
        return cursor;
    }

    private PyObject nativesql(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("nativesql", ZxJdbcModule.positional(args, kwNames), 1, 1);
        if (closed) {
            throw closed();
        }
        try {
            return PyStr.of(connection.nativeSQL(Slots.str(args[0])));
        } catch (SQLException failure) {
            throw ZxJdbcModule.sqlException(failure);
        }
    }

    private static PyRaise closed() {
        return PyRaise.raise(ZxJdbcModule.INTERFACE_ERROR, "connection is closed");
    }
}
