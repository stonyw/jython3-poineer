package org.python.core;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

/**
 * {@code zxJDBC.Cursor}: the DB-API 2.0 cursor, over one JDBC {@link Statement}.
 *
 * <p>The shape is Jython 2's {@code PyCursor} with its default static fetch: {@code execute} runs the
 * statement, reads the whole result set into rows and closes it, so {@code rowcount} is the number of
 * rows the query returned and {@code fetchone} / {@code fetchmany} / {@code fetchall} walk what was
 * read. {@code execute} with a parameter sequence prepares the statement, which is what makes JDBC's
 * {@code ?} the module's {@code qmark} placeholder; without one it sends the text as it is.
 *
 * <p>{@code executemany} runs the statement once per parameter row, as Jython 2 does, so a failure on
 * the tenth row has already run the first nine — the driver's own behaviour, not a batch that was
 * rewritten here.
 */
final class PyZxCursor extends PyIterator {

    private static final Members TABLE = Members.of(ZxJdbcModule.CURSOR_TYPE);

    static {
        TABLE.add("close", "Close the cursor.", (self, args, kwNames) -> ((PyZxCursor) self).close(args, kwNames))
                .add(
                        "execute",
                        "execute(sql[, params[, bindings[, maxRows]]]) -> None.",
                        (self, args, kwNames) -> ((PyZxCursor) self).execute(args, kwNames))
                .add(
                        "executemany",
                        "executemany(sql, params) -> None.",
                        (self, args, kwNames) -> ((PyZxCursor) self).executemany(args, kwNames))
                .add("fetchone", "fetchone() -> the next row as a tuple, or None.", (self, args, kwNames) -> ((PyZxCursor) self).fetchone(args, kwNames))
                .add("fetchmany", "fetchmany(size=arraysize) -> a list of rows.", (self, args, kwNames) -> ((PyZxCursor) self).fetchmany(args, kwNames))
                .add("fetchall", "fetchall() -> all remaining rows.", (self, args, kwNames) -> ((PyZxCursor) self).fetchall(args, kwNames))
                .add("__enter__", "The cursor itself.", (self, args, kwNames) -> self)
                .add("__next__", "The next row; StopIteration at the end.", (self, args, kwNames) -> ((PyZxCursor) self).stepped(args, kwNames))
                .add("next", "Jython 2's name for __next__.", (self, args, kwNames) -> ((PyZxCursor) self).stepped(args, kwNames))
                .add(
                        "__exit__",
                        "Close the cursor.",
                        (self, args, kwNames) -> {
                            ZxJdbcModule.arity("__exit__", ZxJdbcModule.positional(args, kwNames), 3, 3);
                            ((PyZxCursor) self).close(new PyObject[0], new String[0]);
                            return PyNone.INSTANCE;
                        })
                .addField("arraysize", "The default number of rows fetchmany() reads.", self -> PyInt.of(((PyZxCursor) self).arraysize))
                .addField("description", "The column descriptions of the last result set, or None.", self -> ((PyZxCursor) self).description)
                .addField("rowcount", "The rows of the last result set, or the update count.", self -> PyInt.of(((PyZxCursor) self).rowcount))
                .addField("lastrowid", "The last generated key, when the driver reports one.", self -> ((PyZxCursor) self).lastrowid)
                .addField("warnings", "The SQLWarning chain of the last statement, or None.", self -> ((PyZxCursor) self).warnings)
                .addField("connection", "The connection this cursor came from.", self -> ((PyZxCursor) self).owner);
    }

    private final PyZxConnection owner;

    private final boolean dynamic;

    private final int rsType;

    private final int rsConcur;

    private Statement statement;

    /** The rows of the last result set, or null when the last statement produced none. */
    private PyList rows;

    private int position;

    private PyObject description = PyNone.INSTANCE;

    private PyObject lastrowid = PyNone.INSTANCE;

    private PyObject warnings = PyNone.INSTANCE;

    private int rowcount = -1;

    private int arraysize = 1;

    private boolean closed;

    PyZxCursor(PyZxConnection owner, boolean dynamic, int rsType, int rsConcur) {
        super(ZxJdbcModule.CURSOR_TYPE);
        this.owner = owner;
        this.dynamic = dynamic;
        this.rsType = rsType;
        this.rsConcur = rsConcur;
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    @Override
    public PyObject setAttr(String name, PyObject value) {
        if (name.equals("arraysize")) {
            arraysize = ZxJdbcModule.integer(value);
            return PyNone.INSTANCE;
        }
        return super.setAttr(name, value);
    }

    private PyObject close(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("close", ZxJdbcModule.positional(args, kwNames), 0, 0);
        if (!closed) {
            closed = true;
            clear();
        }
        return PyNone.INSTANCE;
    }

    /** Closes this cursor because the connection that owns it was closed. */
    void closedByConnection() {
        if (!closed) {
            closed = true;
            clear();
        }
    }

    private PyObject execute(PyObject[] args, String[] kwNames) {
        int positional = ZxJdbcModule.positional(args, kwNames);
        noKeywords("execute", kwNames);
        ZxJdbcModule.arity("execute", positional, 1, 4);
        String sql = Slots.str(args[0]);
        PyObject params = positional > 1 ? args[1] : PyNone.INSTANCE;
        int maxRows = positional > 3 ? ZxJdbcModule.integer(args[3]) : 0;
        executeOnce(sql, params, maxRows);
        return PyNone.INSTANCE;
    }

    private PyObject executemany(PyObject[] args, String[] kwNames) {
        int positional = ZxJdbcModule.positional(args, kwNames);
        noKeywords("executemany", kwNames);
        ZxJdbcModule.arity("executemany", positional, 2, 3);
        String sql = Slots.str(args[0]);
        PyIterator params = Slots.iterator(args[1]);
        for (PyObject row = params.nextOrNull(); row != null; row = params.nextOrNull()) {
            executeOnce(sql, row, 0);
        }
        return PyNone.INSTANCE;
    }

    private void executeOnce(String sql, PyObject params, int maxRows) {
        ensureOpen();
        clear();
        try {
            boolean hasResult;
            if (params == PyNone.INSTANCE) {
                Statement plain =
                        rsType == 0
                                ? owner.jdbc().createStatement()
                                : owner.jdbc().createStatement(rsType, rsConcur);
                statement = plain;
                if (maxRows > 0) {
                    plain.setMaxRows(maxRows);
                }
                hasResult = plain.execute(sql);
            } else {
                PreparedStatement prepared =
                        rsType == 0
                                ? owner.jdbc().prepareStatement(sql)
                                : owner.jdbc().prepareStatement(sql, rsType, rsConcur);
                statement = prepared;
                if (maxRows > 0) {
                    prepared.setMaxRows(maxRows);
                }
                ZxJdbcData.bindAll(prepared, params);
                hasResult = prepared.execute();
            }
            int updateCount = hasResult ? -1 : statement.getUpdateCount();
            read(hasResult ? statement.getResultSet() : null, updateCount);
            collectWarnings();
        } catch (SQLException failure) {
            throw ZxJdbcModule.sqlException(failure);
        }
    }

    /** Buffers a result set into rows, or records the update count when there is none. */
    private void read(ResultSet result, int updateCount) {
        if (result == null) {
            description = PyNone.INSTANCE;
            rows = null;
            rowcount = updateCount;
            return;
        }
        try {
            ResultSetMetaData meta = result.getMetaData();
            description = ZxJdbcData.describe(meta);
            int columns = meta.getColumnCount();
            PyList buffered = PyList.empty();
            while (result.next()) {
                PyObject[] values = new PyObject[columns];
                for (int i = 1; i <= columns; i++) {
                    values[i - 1] = ZxJdbcData.read(result, i, meta.getColumnType(i));
                }
                buffered.add(PyTuple.of(values));
            }
            rows = buffered;
            rowcount = dynamic ? 0 : buffered.size();
        } catch (SQLException failure) {
            throw ZxJdbcModule.sqlException(failure);
        } finally {
            try {
                result.close();
            } catch (SQLException ignored) {
                // The rows are already read; a close that fails has nothing left to free.
            }
        }
    }

    /** The statement's own warning chain, as the same (message, state, code) tuples Jython 2 files. */
    private void collectWarnings() throws SQLException {
        SQLWarning warning = statement == null ? null : statement.getWarnings();
        if (warning == null) {
            return;
        }
        PyList collected = PyList.empty();
        for (SQLWarning next = warning; next != null; next = next.getNextWarning()) {
            collected.add(
                    PyTuple.of(
                            next.getMessage() == null ? PyNone.INSTANCE : PyStr.of(next.getMessage()),
                            next.getSQLState() == null ? PyNone.INSTANCE : PyStr.of(next.getSQLState()),
                            PyInt.of(next.getErrorCode())));
        }
        warnings = collected;
    }

    private PyObject fetchone(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("fetchone", ZxJdbcModule.positional(args, kwNames), 0, 0);
        ensureOpen();
        if (rows == null || position >= rows.size()) {
            return PyNone.INSTANCE;
        }
        PyObject row = rows.getItem(PyInt.of(position));
        position++;
        fetched(1);
        return row;
    }

    /** Jython 2's {@code next()}: {@code fetchone}, with {@code StopIteration} at the end. */
    private PyObject stepped(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("next", ZxJdbcModule.positional(args, kwNames), 0, 0);
        return super.stepped();
    }

    /** {@code FOR_ITER}'s step: the next row, or null at the end. */
    @Override
    public PyObject nextOrNull() {
        ensureOpen();
        if (rows == null || position >= rows.size()) {
            return null;
        }
        PyObject row = rows.getItem(PyInt.of(position));
        position++;
        fetched(1);
        return row;
    }

    private PyObject fetchmany(PyObject[] args, String[] kwNames) {
        int positional = ZxJdbcModule.positional(args, kwNames);
        ZxJdbcModule.arity("fetchmany", positional, 0, 1);
        ensureOpen();
        int size = positional > 0 ? ZxJdbcModule.integer(args[0]) : arraysize;
        if (rows == null || size <= 0 || position >= rows.size()) {
            return PyList.empty();
        }
        int end = Math.min(rows.size(), position + size);
        PyObject[] taken = new PyObject[end - position];
        for (int i = 0; i < taken.length; i++) {
            taken[i] = rows.getItem(PyInt.of(position + i));
        }
        position = end;
        fetched(taken.length);
        return PyList.of(taken);
    }

    private PyObject fetchall(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("fetchall", ZxJdbcModule.positional(args, kwNames), 0, 0);
        ensureOpen();
        if (rows == null || position >= rows.size()) {
            return PyList.empty();
        }
        PyObject[] taken = new PyObject[rows.size() - position];
        for (int i = 0; i < taken.length; i++) {
            taken[i] = rows.getItem(PyInt.of(position + i));
        }
        position = rows.size();
        fetched(taken.length);
        return PyList.of(taken);
    }

    /** A dynamic cursor counts what was fetched; a static one keeps the query's row count. */
    private void fetched(int count) {
        if (dynamic) {
            rowcount += count;
        }
    }

    private void clear() {
        warnings = PyNone.INSTANCE;
        lastrowid = PyNone.INSTANCE;
        description = PyNone.INSTANCE;
        rows = null;
        position = 0;
        rowcount = -1;
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException ignored) {
                // Replacing the statement is the point; a close that fails changes nothing.
            } finally {
                statement = null;
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw PyRaise.raise(ZxJdbcModule.INTERFACE_ERROR, "cursor is closed");
        }
    }

    private static void noKeywords(String name, String[] kwNames) {
        if (ZxJdbcModule.keywordCount(kwNames) > 0) {
            throw Slots.typeError("%s() takes no keyword arguments", name);
        }
    }
}
