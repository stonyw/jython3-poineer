package org.python.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

/**
 * {@code zxJDBC}: DB-API 2.0 over JDBC, the way Jython 2's {@code com.ziclix.python.sql} is.
 *
 * <p>This is a port of the module's Python-visible surface, not of its internals: the module keeps the
 * four names the PEP requires ({@code apilevel}, {@code threadsafety}, {@code paramstyle} and the
 * exception family), the three connector functions, the seven date and binary helpers, and the two
 * dictionaries of type codes. What it does not carry is zxJDBC's {@code DataHandler} / {@code Fetch} /
 * {@code PyStatement} layering — a driver's values are read and written here directly, so what a query
 * keeps is exactly what the driver answered.
 *
 * <p>The driver is not a dependency of this runtime. It is looked for on the classpath at connect time,
 * and a URL whose driver is not there is refused with a message that names the URL, the driver class
 * that was tried and the drivers {@link DriverManager} has.
 *
 * <p>Nothing here rewrites SQL: {@code ?} is JDBC's own placeholder, {@code nativeSQL} hands the text
 * to the driver's translator, and every {@link SQLException} is reported with its own message, code and
 * state. An ORM that needs a dialect is expected to bring its own.
 */
final class ZxJdbcModule {

    private ZxJdbcModule() {
    }

    /** The two value classes this module hands out, plus the stand-in for a plain Java value. */
    static final PyType CONNECTION_TYPE =
            PyType.declare("Connection", PyType.OBJECT).filedUnder("zxJDBC.Connection");

    static final PyType CURSOR_TYPE =
            PyType.declare("Cursor", PyType.OBJECT).filedUnder("zxJDBC.Cursor");

    static final PyType JAVA_OBJECT_TYPE =
            PyType.declare("JavaObject", PyType.OBJECT).filedUnder("zxJDBC.JavaObject");

    /**
     * The DB-API 2.0 exception family.
     *
     * <p>Jython 2 hangs {@code Warning} and {@code Error} under {@code StandardError}; Python 3 has no
     * such name, so both hang under {@code Exception}. Everything below {@code Error} keeps Jython 2's
     * shape: {@code InterfaceError} and {@code DatabaseError} are its two children, and the seven
     * specific ones are children of {@code DatabaseError}.
     */
    static final PyType WARNING = child("Warning", ExceptionTypes.EXCEPTION);

    static final PyType ERROR = child("Error", ExceptionTypes.EXCEPTION);

    static final PyType INTERFACE_ERROR = child("InterfaceError", ERROR);

    static final PyType DATABASE_ERROR = child("DatabaseError", ERROR);

    static final PyType DATA_ERROR = child("DataError", DATABASE_ERROR);

    static final PyType OPERATIONAL_ERROR = child("OperationalError", DATABASE_ERROR);

    static final PyType INTEGRITY_ERROR = child("IntegrityError", DATABASE_ERROR);

    static final PyType INTERNAL_ERROR = child("InternalError", DATABASE_ERROR);

    static final PyType PROGRAMMING_ERROR = child("ProgrammingError", DATABASE_ERROR);

    static final PyType NOT_SUPPORTED_ERROR = child("NotSupportedError", DATABASE_ERROR);

    private static PyType child(String name, PyType parent) {
        return PyType.declare(name, parent).filedUnder("zxJDBC." + name);
    }

    /**
     * The driver classes the URL schemes of the common JDBC drivers name.
     *
     * <p>This is only a hint for the error message, and for a one-argument {@code connect} — which is
     * all Jython 2's own {@code connect} needs no driver for. A driver registered by the service loader
     * is found by {@link DriverManager} whether or not its class is written down here.
     */
    private static final String[][] KNOWN_DRIVERS = {
        {"sqlite", "org.sqlite.JDBC"},
        {"postgresql", "org.postgresql.Driver"},
        {"mysql", "com.mysql.cj.jdbc.Driver"},
        {"mariadb", "org.mariadb.jdbc.Driver"},
        {"h2", "org.h2.Driver"},
        {"hsqldb", "org.hsqldb.jdbc.JDBCDriver"},
        {"derby", "org.apache.derby.jdbc.EmbeddedDriver"},
        {"oracle", "oracle.jdbc.OracleDriver"},
        {"sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver"},
        {"db2", "com.ibm.db2.jcc.DB2Driver"},
    };

    private static final String MODULE_DOC =
            "zxJDBC - DB-API 2.0 over JDBC.\n"
                    + "\n"
                    + "The driver is not part of this runtime: put its jar on the classpath and\n"
                    + "connect() finds it. For \"jdbc:sqlite:\" URLs that jar is sqlite-jdbc\n"
                    + "(org.xerial:sqlite-jdbc).\n";

    static PyModule newModule() {
        PyModule module = new PyModule("zxJDBC");
        PyRuntime.markBuiltIn(module, "zxJDBC");
        module.set("__doc__", PyStr.of(MODULE_DOC));
        module.set("apilevel", PyStr.of("2.0"));
        module.set("threadsafety", PyInt.of(1));
        module.set("paramstyle", PyStr.of("qmark"));

        module.set("connect", function("connect", ZxJdbcModule::connect));
        module.set("connectx", function("connectx", ZxJdbcModule::connectx));
        module.set("lookup", function("lookup", ZxJdbcModule::lookup));

        module.set("Date", function("Date", ZxJdbcModule::date));
        module.set("Time", function("Time", ZxJdbcModule::time));
        module.set("Timestamp", function("Timestamp", ZxJdbcModule::timestamp));
        module.set("DateFromTicks", function("DateFromTicks", ZxJdbcModule::dateFromTicks));
        module.set("TimeFromTicks", function("TimeFromTicks", ZxJdbcModule::timeFromTicks));
        module.set("TimestampFromTicks", function("TimestampFromTicks", ZxJdbcModule::timestampFromTicks));
        module.set("Binary", function("Binary", ZxJdbcModule::binary));

        module.set("Warning", WARNING);
        module.set("Error", ERROR);
        module.set("InterfaceError", INTERFACE_ERROR);
        module.set("DatabaseError", DATABASE_ERROR);
        module.set("DataError", DATA_ERROR);
        module.set("OperationalError", OPERATIONAL_ERROR);
        module.set("IntegrityError", INTEGRITY_ERROR);
        module.set("InternalError", INTERNAL_ERROR);
        module.set("ProgrammingError", PROGRAMMING_ERROR);
        module.set("NotSupportedError", NOT_SUPPORTED_ERROR);

        addSqlTypes(module);
        return module;
    }

    // --- connectors --------------------------------------------------------------------

    /**
     * {@code connect(url[, user[, password[, driver]]], **properties)}.
     *
     * <p>Jython 2 makes {@code driver} the fourth argument and requires it. Here it may be left out: the
     * URL's scheme picks the usual class name for the message, and the driver is whatever
     * {@link DriverManager} can find — which is what lets a driver registered through
     * {@code META-INF/services/java.sql.Driver} work without naming its class.
     */
    private static PyObject connect(PyObject[] args, String[] kwNames) {
        int positional = positional(args, kwNames);
        arity("connect", positional, 1, 4);

        String url = Slots.str(args[0]);
        String user = positional > 1 ? optionalText(args[1]) : null;
        String password = positional > 2 ? optionalText(args[2]) : null;
        String driver = positional > 3 ? optionalText(args[3]) : null;
        Properties properties = new Properties();

        for (int i = 0; i < keywordCount(kwNames); i++) {
            String name = kwNames[i];
            PyObject value = args[positional + i];
            switch (name) {
                case "url" -> {
                    if (positional > 0) {
                        throw duplicate("connect", name);
                    }
                    url = Slots.str(value);
                }
                case "user" -> {
                    if (positional > 1) {
                        throw duplicate("connect", name);
                    }
                    user = optionalText(value);
                }
                case "password" -> {
                    if (positional > 2) {
                        throw duplicate("connect", name);
                    }
                    password = optionalText(value);
                }
                case "driver" -> {
                    if (positional > 3) {
                        throw duplicate("connect", name);
                    }
                    driver = optionalText(value);
                }
                default -> properties.setProperty(name, Slots.str(value));
            }
        }

        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        return open(url, driver, properties);
    }

    /**
     * {@code connectx(url, driver[, user[, password]], **properties)}: Jython 2's spelling for a
     * connection whose driver class is named.
     *
     * <p>The argument order is the module's own — the driver comes second here and fourth in {@code
     * connect} — so this maps onto the same body rather than duplicating it.
     */
    private static PyObject connectx(PyObject[] args, String[] kwNames) {
        int positional = positional(args, kwNames);
        arity("connectx", positional, 1, 4);

        String url = Slots.str(args[0]);
        String driver = positional > 1 ? optionalText(args[1]) : null;
        String user = positional > 2 ? optionalText(args[2]) : null;
        String password = positional > 3 ? optionalText(args[3]) : null;
        Properties properties = new Properties();
        for (int i = 0; i < keywordCount(kwNames); i++) {
            String name = kwNames[i];
            PyObject value = args[positional + i];
            switch (name) {
                case "url" -> url = Slots.str(value);
                case "driver" -> driver = optionalText(value);
                case "user" -> user = optionalText(value);
                case "password" -> password = optionalText(value);
                default -> properties.setProperty(name, Slots.str(value));
            }
        }
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        return open(url, driver, properties);
    }

    /** {@code lookup(driver)}: an instance of the named {@link Driver}, so its properties can be read. */
    private static PyObject lookup(PyObject[] args, String[] kwNames) {
        arity("lookup", positional(args, kwNames), 1, 1);
        String name = Slots.str(args[0]);
        try {
            Class<?> type = Class.forName(name, true, ZxJdbcModule.class.getClassLoader());
            return new PyJavaObject(type.getDeclaredConstructor().newInstance());
        } catch (Throwable missing) {
            throw PyRaise.raise(INTERFACE_ERROR, driverMissing(name, missing));
        }
    }

    /**
     * Opens the connection, turning "no driver" into a message that says what to put where.
     *
     * <p>A driver named by the caller has to load: that is what naming it means, and it is also what
     * Jython 2 does. A driver inferred from the URL does not, because {@link DriverManager} may find one
     * that was registered without the guessed class name ever being loaded.
     */
    private static PyObject open(String url, String driver, Properties properties) {
        String attempted = driver != null ? driver : knownDriver(url);
        String attempt;
        if (attempted == null) {
            attempt = "no driver class is known for this URL";
        } else {
            try {
                Class.forName(attempted, true, ZxJdbcModule.class.getClassLoader());
                attempt = "loaded";
            } catch (Throwable missing) {
                attempt = "not found (" + describe(missing) + ")";
                if (driver != null) {
                    throw PyRaise.raise(INTERFACE_ERROR, noDriverMessage(url, driver, attempt));
                }
            }
        }

        try {
            return new PyZxConnection(DriverManager.getConnection(url, properties));
        } catch (SQLException failure) {
            if (noDriver(failure)) {
                throw PyRaise.raise(INTERFACE_ERROR, noDriverMessage(url, attempted, attempt));
            }
            throw sqlException(failure);
        }
    }

    private static String knownDriver(String url) {
        String scheme = url.startsWith("jdbc:") ? url.substring(5) : url;
        int colon = scheme.indexOf(':');
        if (colon >= 0) {
            scheme = scheme.substring(0, colon);
        }
        for (String[] known : KNOWN_DRIVERS) {
            if (known[0].equalsIgnoreCase(scheme)) {
                return known[1];
            }
        }
        return null;
    }

    private static boolean noDriver(SQLException failure) {
        String message = failure.getMessage();
        return failure instanceof java.sql.SQLNonTransientConnectionException
                || (message != null && message.toLowerCase().contains("no suitable driver"));
    }

    private static String noDriverMessage(String url, String attempted, String attempt) {
        return "Cannot open a JDBC connection to \""
                + url
                + "\": no usable driver was found.\n"
                + "Put the driver's jar on the classpath - for a \"jdbc:sqlite:\" URL that is\n"
                + "sqlite-jdbc (org.xerial:sqlite-jdbc), for example as -cp sqlite-jdbc-3.x.x.jar\n"
                + "on the jy3 command line, or beside jy3.jar.\n"
                + "Driver class tried: "
                + (attempted == null ? "(none)" : "\"" + attempted + "\": " + attempt)
                + ".\n"
                + "Drivers registered with java.sql.DriverManager: "
                + registeredDrivers()
                + ".";
    }

    private static String registeredDrivers() {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        List<String> names = new ArrayList<>();
        while (drivers.hasMoreElements()) {
            names.add(drivers.nextElement().getClass().getName());
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private static String driverMissing(String name, Throwable missing) {
        return "JDBC driver \""
                + name
                + "\" is not on the classpath ("
                + describe(missing)
                + ").\nPut the driver's jar on the classpath, for a \"jdbc:sqlite:\" URL sqlite-jdbc\n"
                + "(org.xerial:sqlite-jdbc) as -cp sqlite-jdbc-3.x.x.jar on the jy3 command line.";
    }

    private static String describe(Throwable failure) {
        return failure.getMessage() == null
                ? failure.getClass().getName()
                : failure.getClass().getName() + ": " + failure.getMessage();
    }

    // --- errors ------------------------------------------------------------------------

    /**
     * The DB-API 2.0 class a failed statement is reported as.
     *
     * <p>Jython 2 looks at the SQLState and maps only class 23 to {@code IntegrityError}, answering a
     * bare {@code Error} for everything else. The standard SQLState classes are used here instead, plus
     * SQLite's own {@code SQLITE_CONSTRAINT} vendor code, which sqlite-jdbc reports with an extended
     * state rather than a class-23 one. A driver failure that is none of these is an
     * {@code OperationalError} — a statement that could not run — rather than the base class.
     */
    private static PyType typeOf(SQLException failure) {
        String state = failure.getSQLState();
        if (state != null && !state.isEmpty()) {
            String upper = state.toUpperCase();
            if (upper.startsWith("23")
                    || upper.equals("40002")
                    || upper.contains("CONSTRAINT")) {
                return INTEGRITY_ERROR;
            }
            if (upper.startsWith("22")) {
                return DATA_ERROR;
            }
            if (upper.startsWith("42")
                    || upper.startsWith("37")
                    || upper.startsWith("2A")
                    || upper.startsWith("0A")) {
                return PROGRAMMING_ERROR;
            }
            if (upper.startsWith("08")) {
                return OPERATIONAL_ERROR;
            }
            if (upper.startsWith("40")) {
                return INTERNAL_ERROR;
            }
        }
        if (failure instanceof SQLIntegrityConstraintViolationException) {
            return INTEGRITY_ERROR;
        }
        int code = failure.getErrorCode();
        if ((code & 0xFF) == 19 || code == 275 || code == 2067 || code == 1555) {
            // SQLITE_CONSTRAINT and its PRIMARY KEY / UNIQUE / FOREIGN KEY extensions.
            return INTEGRITY_ERROR;
        }
        if (code == 1) {
            // SQLITE_ERROR: bad SQL or a missing table, which is what a statement cannot run for.
            return OPERATIONAL_ERROR;
        }
        return OPERATIONAL_ERROR;
    }

    /** A failed statement, keeping the driver's message and adding its code and state. */
    static PyRaise sqlException(SQLException failure) {
        StringBuilder text = new StringBuilder(String.valueOf(failure.getMessage()));
        text.append(" [SQLCode: ")
                .append(failure.getErrorCode())
                .append("], [SQLState: ")
                .append(failure.getSQLState())
                .append(']');
        for (SQLException next = failure.getNextException(); next != null; next = next.getNextException()) {
            text.append('\n').append(next.getMessage());
        }
        return PyRaise.raise(typeOf(failure), text.toString());
    }

    // --- helpers exposed to the connection and cursor -------------------------------------

    static int positional(PyObject[] args, String[] kwNames) {
        return args.length - keywordCount(kwNames);
    }

    static int keywordCount(String[] kwNames) {
        return kwNames == null ? 0 : kwNames.length;
    }

    static void arity(String name, int positional, int least, int most) {
        if (positional < least || positional > most) {
            String expected =
                    least == most
                            ? least + " argument" + (least == 1 ? "" : "s")
                            : "at least " + least + " and at most " + most + " arguments";
            throw Slots.typeError("%s() takes %s (%d given)", name, expected, positional);
        }
    }

    static PyRaise duplicate(String function, String name) {
        return Slots.typeError("%s() got multiple values for argument '%s'", function, name);
    }

    static String optionalText(PyObject value) {
        return value == PyNone.INSTANCE ? null : Slots.str(value);
    }

    static int integer(PyObject value) {
        return Numbers.asIndex(value).intValue();
    }

    // --- date and binary helpers -----------------------------------------------------------

    private static PyObject date(PyObject[] args, String[] kwNames) {
        arity("Date", positional(args, kwNames), 3, 3);
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, integer(args[0]));
        calendar.set(Calendar.MONTH, integer(args[1]) - 1);
        calendar.set(Calendar.DAY_OF_MONTH, integer(args[2]));
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return new PyJavaObject(new java.sql.Date(calendar.getTimeInMillis()));
    }

    private static PyObject time(PyObject[] args, String[] kwNames) {
        arity("Time", positional(args, kwNames), 3, 3);
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, integer(args[0]));
        calendar.set(Calendar.MINUTE, integer(args[1]));
        calendar.set(Calendar.SECOND, integer(args[2]));
        calendar.set(Calendar.MILLISECOND, 0);
        return new PyJavaObject(new java.sql.Time(calendar.getTimeInMillis()));
    }

    private static PyObject timestamp(PyObject[] args, String[] kwNames) {
        arity("Timestamp", positional(args, kwNames), 6, 6);
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, integer(args[0]));
        calendar.set(Calendar.MONTH, integer(args[1]) - 1);
        calendar.set(Calendar.DAY_OF_MONTH, integer(args[2]));
        calendar.set(Calendar.HOUR_OF_DAY, integer(args[3]));
        calendar.set(Calendar.MINUTE, integer(args[4]));
        calendar.set(Calendar.SECOND, integer(args[5]));
        calendar.set(Calendar.MILLISECOND, 0);
        return new PyJavaObject(new java.sql.Timestamp(calendar.getTimeInMillis()));
    }

    private static PyObject dateFromTicks(PyObject[] args, String[] kwNames) {
        arity("DateFromTicks", positional(args, kwNames), 1, 1);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(integer(args[0]) * 1000L);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return new PyJavaObject(new java.sql.Date(calendar.getTimeInMillis()));
    }

    private static PyObject timeFromTicks(PyObject[] args, String[] kwNames) {
        arity("TimeFromTicks", positional(args, kwNames), 1, 1);
        return new PyJavaObject(new java.sql.Time(integer(args[0]) * 1000L));
    }

    private static PyObject timestampFromTicks(PyObject[] args, String[] kwNames) {
        arity("TimestampFromTicks", positional(args, kwNames), 1, 1);
        return new PyJavaObject(new java.sql.Timestamp(integer(args[0]) * 1000L));
    }

    /**
     * {@code Binary(value)}: the bytes a parameter binds as a binary value.
     *
     * <p>Jython 2 answers a Java {@code byte[]}; here a Python {@code bytes} is both what goes in and
     * what comes back, and anything else is refused the way Python 3 refuses a binary operand.
     */
    private static PyObject binary(PyObject[] args, String[] kwNames) {
        arity("Binary", positional(args, kwNames), 1, 1);
        if (args[0] instanceof PyBytes bytes) {
            return PyBytes.of(bytes.getValue().clone());
        }
        throw Slots.typeError(
                "Binary() argument must be a bytes-like object, not '%s'",
                args[0].getType().filedName());
    }

    // --- type codes -------------------------------------------------------------------------

    /**
     * The type codes Jython 2 reflects out of {@code java.sql.Types} and {@code java.sql.ResultSet}.
     *
     * <p>Every public static {@code int} field becomes a module attribute, and each {@code Types} field
     * is also filed in {@code sqltype} under its value. The four aliases are Jython 2's own:
     * {@code ROWID} is {@code OTHER}, {@code NUMBER} is {@code NUMERIC}, {@code STRING} is
     * {@code VARCHAR} and {@code DATETIME} is {@code TIMESTAMP}.
     */
    private static void addSqlTypes(PyModule module) {
        PyDict sqltype = PyDict.empty();
        java.util.Map<String, PyObject> filed = new java.util.HashMap<>();
        for (Field field : Types.class.getFields()) {
            if (constant(field)) {
                continue;
            }
            try {
                int value = field.getInt(null);
                PyObject constant = new PyDBApiType(value);
                module.set(field.getName(), constant);
                filed.put(field.getName(), constant);
                sqltype.put(PyInt.of(value), PyStr.of(field.getName()));
            } catch (IllegalAccessException ignored) {
                // A public field of a public class is readable; nothing to report if it is not.
            }
        }
        for (Field field : java.sql.ResultSet.class.getFields()) {
            if (constant(field)) {
                continue;
            }
            try {
                module.set(field.getName(), PyInt.of(field.getInt(null)));
            } catch (IllegalAccessException ignored) {
                // As above.
            }
        }
        module.set("ROWID", filed.get("OTHER"));
        module.set("NUMBER", filed.get("NUMERIC"));
        module.set("STRING", filed.get("VARCHAR"));
        module.set("DATETIME", filed.get("TIMESTAMP"));
        module.set("sqltype", sqltype);
    }

    private static boolean constant(Field field) {
        int modifiers = field.getModifiers();
        return !Modifier.isPublic(modifiers)
                || !Modifier.isStatic(modifiers)
                || field.getType() != int.class;
    }

    private static PyObject function(String name, PyNativeFunction.Body body) {
        return new PyNativeFunction(name, body);
    }
}
