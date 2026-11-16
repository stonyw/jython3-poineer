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
                .add("scroll", "scroll(value[, mode='relative']) -> None.", (self, args, kwNames) -> ((PyZxCursor) self).scroll(args, kwNames))
                .add("nextset", "nextset() is not supported by this layer.", (self, args, kwNames) -> ((PyZxCursor) self).nextset(args, kwNames))
                .add("prepare", "prepare() is not supported by this layer.", (self, args, kwNames) -> ((PyZxCursor) self).prepare(args, kwNames))
                .add("callproc", "callproc(name[, params[, bindings[, maxRows]]]) -> None.", (self, args, kwNames) -> ((PyZxCursor) self).callproc(args, kwNames))
                .add("setinputsizes", "A no-op, as DB-API 2.0 allows.", (self, args, kwNames) -> ((PyZxCursor) self).setinputsizes(args, kwNames))
                .add("setoutputsize", "A no-op, as DB-API 2.0 allows.", (self, args, kwNames) -> ((PyZxCursor) self).setoutputsize(args, kwNames))
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

    /**
     * Jython 2's {@code StaticFetch.scroll}: move the read position within the buffered rows.
     *
     * <p>The modes and both messages are the reference's own — {@code relative} counts from where the
     * cursor is, {@code absolute} from the first row, an unknown mode is a {@code ProgrammingError},
     * and a position outside the rows is an {@code IndexError}.
     */
    private PyObject scroll(PyObject[] args, String[] kwNames) {
        int positional = ZxJdbcModule.positional(args, kwNames);
        noKeywords("scroll", kwNames);
        ZxJdbcModule.arity("scroll", positional, 1, 2);
        ensureOpen();
        int value = ZxJdbcModule.integer(args[0]);
        String mode = positional > 1 ? Slots.str(args[1]) : "relative";
        int target;
        if (mode.equals("relative")) {
            target = position + value;
        } else if (mode.equals("absolute")) {
            target = value;
        } else {
            throw PyRaise.raise(
                    ZxJdbcModule.PROGRAMMING_ERROR, "invalid cursor scroll mode [" + mode + "]");
        }
        int count = rows == null ? 0 : rows.size();
        if (target < 0 || target >= count) {
            throw PyRaise.of(
                    new PyBaseException(
                            ExceptionTypes.INDEX_ERROR,
                            new PyObject[] {PyStr.of("cursor index [" + target + "] out of range")}));
        }
        position = target;
        return PyNone.INSTANCE;
    }

    /**
     * {@code nextset()} is refused rather than answering {@code None}.
     *
     * <p>Jython 2's static fetch can hand out a second result set when the driver produced one; this
     * layer reads and closes the first set inside {@code execute}, so it cannot reach a later one, and
     * saying {@code None} would claim there is none. DB-API 2.0 files that as {@code NotSupportedError}.
     */
    private PyObject nextset(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("nextset", ZxJdbcModule.positional(args, kwNames), 0, 0);
        ensureOpen();
        throw PyRaise.raise(
                ZxJdbcModule.NOT_SUPPORTED_ERROR,
                "nextset() is not supported: execute() reads and closes the first result set,"
                        + " so a later one cannot be reached");
    }

    /** Jython 2's {@code prepare()} hand back a statement object; this layer has none to hand back. */
    private PyObject prepare(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("prepare", ZxJdbcModule.positional(args, kwNames), 1, 1);
        ensureOpen();
        throw PyRaise.raise(
                ZxJdbcModule.NOT_SUPPORTED_ERROR,
                "prepare() is not supported: pass the SQL and its parameters to execute()");
    }

    /**
     * Jython 2's {@code callproc(name[, params[, bindings[, maxRows]]])}.
     *
     * <p>The driver is asked first, as the reference does with {@code DatabaseMetaData}: a database
     * without stored procedures answers {@code NotSupportedError("stored procedures not implemented
     * in db")}. A driver that has them would still need a {@code CallableStatement} binding for output
     * parameters, which this layer does not build, so that case is refused in its own words too.
     */
    private PyObject callproc(PyObject[] args, String[] kwNames) {
        int positional = ZxJdbcModule.positional(args, kwNames);
        noKeywords("callproc", kwNames);
        ZxJdbcModule.arity("callproc", positional, 1, 4);
        ensureOpen();
        try {
            if (!owner.jdbc().getMetaData().supportsStoredProcedures()) {
                throw PyRaise.raise(
                        ZxJdbcModule.NOT_SUPPORTED_ERROR, "stored procedures not implemented in db");
            }
        } catch (SQLException failure) {
            throw ZxJdbcModule.sqlException(failure);
        }
        throw PyRaise.raise(
                ZxJdbcModule.NOT_SUPPORTED_ERROR,
                "callproc() is not supported: this layer does not bind CallableStatement output parameters");
    }

    /** DB-API 2.0 says implementations are free to do nothing; Jython 2 does nothing too. */
    private PyObject setinputsizes(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("setinputsizes", ZxJdbcModule.positional(args, kwNames), 1, 1);
        ensureOpen();
        return PyNone.INSTANCE;
    }

    /** As {@link #setinputsizes}: an advisory call that DB-API 2.0 lets an implementation ignore. */
    private PyObject setoutputsize(PyObject[] args, String[] kwNames) {
        ZxJdbcModule.arity("setoutputsize", ZxJdbcModule.positional(args, kwNames), 1, 2);
        ensureOpen();
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
                PreparedStatement prepared;
                if (rsType != 0) {
                    prepared = owner.jdbc().prepareStatement(sql, rsType, rsConcur);
                } else {
                    prepared = preparedForKeys(sql);
                }
                statement = prepared;
                if (maxRows > 0) {
                    prepared.setMaxRows(maxRows);
                }
                ZxJdbcData.bindAll(prepared, params);
                hasResult = prepared.execute();
            }
            int updateCount = hasResult ? -1 : statement.getUpdateCount();
            read(hasResult ? statement.getResultSet() : null, updateCount);
            // Jython 2's updateAttributes() asks the handler for a row id after every execution.
            lastrowid = generatedKey(statement);
            collectWarnings();
        } catch (SQLException failure) {
            throw ZxJdbcModule.sqlException(failure);
        }
    }

    /**
     * Prepares a statement that asks the driver for generated keys, falling back to a plain one.
     *
     * <p>{@code RETURN_GENERATED_KEYS} is the JDBC 3.0 door Jython 2's {@code RowIdHandler} reached
     * through a driver-specific reflection call; a driver that refuses the flag still runs the SQL,
     * and {@link #generatedKey} then reports {@code None} as the reference's base handler does.
     */
    private PreparedStatement preparedForKeys(String sql) throws SQLException {
        try {
            return owner.jdbc().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        } catch (SQLException unsupported) {
            return owner.jdbc().prepareStatement(sql);
        }
    }

    /**
     * The row id the driver reports for the statement just run, or {@code None}.
     *
     * <p>Jython 2's {@code DataHandler.getRowId} answers {@code None} unless a driver-specific handler
     * knows a better method, and every failure in {@code RowIdHandler} falls back to that. Here the
     * general JDBC equivalent is tried instead: the first column of {@code getGeneratedKeys()}, when
     * the driver returns a row. Nothing is made up when it does not.
     */
    private static PyObject generatedKey(Statement executed) {
        if (executed == null) {
            return PyNone.INSTANCE;
        }
        try (ResultSet keys = executed.getGeneratedKeys()) {
            if (keys == null || !keys.next()) {
                return PyNone.INSTANCE;
            }
            Object value = keys.getObject(1);
            if (value == null) {
                return PyNone.INSTANCE;
            }
            if (value instanceof java.math.BigInteger big) {
                return PyInt.of(big);
            }
            if (value instanceof Number number) {
                return PyInt.of(number.longValue());
            }
            if (value instanceof byte[] bytes) {
                return PyBytes.of(bytes);
            }
            return PyStr.of(value.toString());
        } catch (SQLException | RuntimeException unsupported) {
            return PyNone.INSTANCE;
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
