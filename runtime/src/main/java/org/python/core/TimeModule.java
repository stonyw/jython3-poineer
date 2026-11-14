package org.python.core;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The {@code time} module: clocks, the epoch, and the nine-item time tuple.
 *
 * <p>What a Python program reaches for here is a small core — {@code time}, {@code monotonic},
 * {@code perf_counter}, {@code sleep}, and the {@code gmtime}/{@code localtime}/{@code strftime} family
 * over {@code struct_time}. Everything below that core is implemented; {@code strptime},
 * {@code clock_settime} and {@code pthread_getcpuclockid} are named gaps, and {@code process_time} is
 * the current thread's CPU time rather than the process's, which is denoted in {@code PLAN.md}.
 *
 * <p>The constants are the reference's macOS values, because that is the platform this runtime is
 * measured on. The zone offsets and abbreviations are read from the JVM's default zone at import time,
 * which is what {@code tzset} would recompute; the no-op is the gap.
 */
final class TimeModule {

    private static final String DOC =
            "This module provides various functions to manipulate time values.\n"
            + "\n"
            + "There are two standard representations of time.  One is the number\n"
            + "of seconds since the Epoch, in UTC (a.k.a. GMT).  It may be an integer\n"
            + "or a floating-point number (to represent fractions of seconds).\n"
            + "The epoch is the point where the time starts, the return value of\n"
            + "time.gmtime(0).  It is January 1, 1970, 00:00:00 (UTC) on all platforms.";

    // The reference's CLOCK_* values for macOS.
    private static final int CLOCK_REALTIME = 0;
    private static final int CLOCK_MONOTONIC = 6;
    private static final int CLOCK_MONOTONIC_RAW = 4;
    private static final int CLOCK_MONOTONIC_RAW_APPROX = 5;
    private static final int CLOCK_PROCESS_CPUTIME_ID = 12;
    private static final int CLOCK_THREAD_CPUTIME_ID = 16;
    private static final int CLOCK_UPTIME_RAW = 8;
    private static final int CLOCK_UPTIME_RAW_APPROX = 9;

    private static final String[] DAY_NAMES = {
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    };

    private static final String[] MONTH_NAMES = {
        "January", "February", "March", "April", "May", "June", "July", "August", "September",
        "October", "November", "December"
    };

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private TimeModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("time");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "time");

        module.set("CLOCK_REALTIME", PyInt.of(CLOCK_REALTIME));
        module.set("CLOCK_MONOTONIC", PyInt.of(CLOCK_MONOTONIC));
        module.set("CLOCK_MONOTONIC_RAW", PyInt.of(CLOCK_MONOTONIC_RAW));
        module.set("CLOCK_MONOTONIC_RAW_APPROX", PyInt.of(CLOCK_MONOTONIC_RAW_APPROX));
        module.set("CLOCK_PROCESS_CPUTIME_ID", PyInt.of(CLOCK_PROCESS_CPUTIME_ID));
        module.set("CLOCK_THREAD_CPUTIME_ID", PyInt.of(CLOCK_THREAD_CPUTIME_ID));
        module.set("CLOCK_UPTIME_RAW", PyInt.of(CLOCK_UPTIME_RAW));
        module.set("CLOCK_UPTIME_RAW_APPROX", PyInt.of(CLOCK_UPTIME_RAW_APPROX));

        module.set("timezone", PyInt.of(timezone()));
        module.set("altzone", PyInt.of(timezone()));
        module.set("daylight", PyInt.of(0));
        module.set("tzname", PyTuple.of(PyStr.of(zoneName()), PyStr.of(zoneName())));
        module.set("_STRUCT_TM_ITEMS", PyInt.of(11));
        module.set("struct_time", PyType.STRUCT_TIME);

        module.set("time", PyNativeFunction.positional("time", args -> PyFloat.of(now())));
        module.set("time_ns",
                PyNativeFunction.positional("time_ns", args -> PyInt.of(epochNanos())));
        module.set("monotonic", PyNativeFunction.positional(
                "monotonic", args -> PyFloat.of(System.nanoTime() / 1_000_000_000.0)));
        module.set("monotonic_ns",
                PyNativeFunction.positional("monotonic_ns", args -> PyInt.of(System.nanoTime())));
        module.set("perf_counter", PyNativeFunction.positional(
                "perf_counter", args -> PyFloat.of(System.nanoTime() / 1_000_000_000.0)));
        module.set("perf_counter_ns",
                PyNativeFunction.positional("perf_counter_ns", args -> PyInt.of(System.nanoTime())));
        module.set("process_time", PyNativeFunction.positional(
                "process_time", args -> PyFloat.of(cpuNanos() / 1_000_000_000.0)));
        module.set("process_time_ns",
                PyNativeFunction.positional("process_time_ns", args -> PyInt.of(cpuNanos())));
        module.set("thread_time", PyNativeFunction.positional(
                "thread_time", args -> PyFloat.of(cpuNanos() / 1_000_000_000.0)));
        module.set("thread_time_ns",
                PyNativeFunction.positional("thread_time_ns", args -> PyInt.of(cpuNanos())));

        module.set("sleep", PyNativeFunction.positional("sleep", TimeModule::sleep));
        module.set("gmtime", PyNativeFunction.positional(
                "gmtime", args -> structural(instant(optionalSeconds(args)).atZone(ZoneOffset.UTC),
                        "UTC", 0)));
        module.set("localtime", PyNativeFunction.positional(
                "localtime", args -> local(instant(optionalSeconds(args)))));
        module.set("mktime", PyNativeFunction.positional("mktime",
                args -> PyFloat.of(mktime(fields(args, "mktime")))));
        module.set("asctime", PyNativeFunction.positional("asctime",
                args -> PyStr.of(asctime(fields(args, "asctime")))));
        module.set("ctime", PyNativeFunction.positional("ctime", args -> {
            double seconds = optionalSeconds(args);
            return PyStr.of(asctime(fieldsOf(local(instant(seconds)), "ctime")));
        }));
        module.set("strftime", PyNativeFunction.positional("strftime", args -> {
            Arguments.between("strftime", args, null, 1, 2);
            String format = Numbers.asText(args[0]);
            int[] f;
            PyStructTime given = null;
            if (args.length == 2 && args[1] != PyNone.INSTANCE) {
                if (args[1] instanceof PyStructTime time) {
                    given = time;
                }
                f = fields(args, "strftime", 1);
            } else {
                f = fieldsOf(local(instant(now())), "strftime");
            }
            return PyStr.of(strftime(format, f, given));
        }));

        module.set("get_clock_info", PyNativeFunction.positional("get_clock_info", args -> {
            Arguments.exactlyOne("get_clock_info", args, null);
            return clockInfo(Numbers.asText(args[0]));
        }));
        module.set("clock_gettime",
                PyNativeFunction.positional("clock_gettime", args -> PyFloat.of(clock(args))));
        module.set("clock_gettime_ns", PyNativeFunction.positional(
                "clock_gettime_ns", args -> PyInt.of(Math.round(clock(args) * 1_000_000_000.0))));
        module.set("clock_getres", PyNativeFunction.positional("clock_getres", args -> {
            clock(args);
            return PyFloat.of(1.0000000000000002e-06);
        }));
        module.set("clock_getres_ns", PyNativeFunction.positional("clock_getres_ns", args -> {
            clock(args);
            return PyInt.of(1000);
        }));
        module.set("tzset",
                PyNativeFunction.positional("tzset", args -> PyNone.INSTANCE));
        return module;
    }

    // --- clocks --------------------------------------------------------------------------------------

    private static double now() {
        Instant instant = Instant.now();
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    private static java.math.BigInteger epochNanos() {
        Instant instant = Instant.now();
        return java.math.BigInteger.valueOf(instant.getEpochSecond())
                .multiply(java.math.BigInteger.valueOf(1_000_000_000L))
                .add(java.math.BigInteger.valueOf(instant.getNano()));
    }

    private static long cpuNanos() {
        long nanos = THREADS.getCurrentThreadCpuTime();
        return nanos < 0 ? 0 : nanos;
    }

    private static PyObject sleep(PyObject[] args) {
        Arguments.exactlyOne("sleep", args, null);
        double seconds = numeric(args[0]);
        if (seconds < 0) {
            throw Slots.valueError("sleep length must be non-negative");
        }
        long total = Math.round(seconds * 1_000_000_000.0);
        int holds = Gil.suspend();
        try {
            Thread.sleep(total / 1_000_000L, (int) (total % 1_000_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            Gil.resume(holds);
        }
        return PyNone.INSTANCE;
    }

    /** {@code clock_gettime(clk_id)}: the wall clock for CLOCK_REALTIME, a monotonic one otherwise. */
    private static double clock(PyObject[] args) {
        Arguments.exactlyOne("clock_gettime", args, null);
        int id = Numbers.asIndex(args[0]).intValueExact();
        if (id == CLOCK_REALTIME) {
            return now();
        }
        if (id == CLOCK_MONOTONIC || id == CLOCK_MONOTONIC_RAW || id == CLOCK_MONOTONIC_RAW_APPROX
                || id == CLOCK_UPTIME_RAW || id == CLOCK_UPTIME_RAW_APPROX) {
            return System.nanoTime() / 1_000_000_000.0;
        }
        if (id == CLOCK_PROCESS_CPUTIME_ID || id == CLOCK_THREAD_CPUTIME_ID) {
            return cpuNanos() / 1_000_000_000.0;
        }
        throw Slots.valueError("invalid clock id");
    }

    /** {@code get_clock_info(name)}: the four attributes the reference's namespace carries. */
    private static PyObject clockInfo(String name) {
        String implementation;
        boolean monotonic;
        boolean adjustable;
        double resolution;
        switch (name) {
            case "time" -> {
                implementation = "clock_gettime(CLOCK_REALTIME)";
                monotonic = false;
                adjustable = true;
                resolution = 1.0000000000000002e-06;
            }
            case "monotonic", "perf_counter" -> {
                implementation = "mach_absolute_time()";
                monotonic = true;
                adjustable = false;
                resolution = 4.166666666666666e-08;
            }
            case "process_time" -> {
                implementation = "clock_gettime(CLOCK_PROCESS_CPUTIME_ID)";
                monotonic = true;
                adjustable = false;
                resolution = 1.0000000000000002e-06;
            }
            case "thread_time" -> {
                implementation = "clock_gettime(CLOCK_THREAD_CPUTIME_ID)";
                monotonic = true;
                adjustable = false;
                resolution = 4.2000000000000006e-08;
            }
            default -> throw Slots.valueError("unknown clock");
        }
        PyNamespace info = new PyNamespace();
        info.set("implementation", PyStr.of(implementation));
        info.set("monotonic", PyBool.of(monotonic));
        info.set("adjustable", PyBool.of(adjustable));
        info.set("resolution", PyFloat.of(resolution));
        return info;
    }

    // --- the time tuple ------------------------------------------------------------------------------

    private static double optionalSeconds(PyObject[] args) {
        Arguments.atMost("time function", args, null, 1);
        if (args.length == 0 || args[0] == PyNone.INSTANCE) {
            return now();
        }
        return numeric(args[0]);
    }

    private static double numeric(PyObject value) {
        Double number = Numeric.doubleOf(value);
        if (number == null) {
            throw Slots.typeError("must be real number, not %s", value.getType().filedName());
        }
        return number;
    }

    private static Instant instant(double seconds) {
        long whole = (long) Math.floor(seconds);
        long nanos = Math.round((seconds - whole) * 1_000_000_000.0);
        if (nanos == 1_000_000_000L) {
            whole++;
            nanos = 0;
        }
        return Instant.ofEpochSecond(whole, nanos);
    }

    private static PyObject local(Instant when) {
        ZonedDateTime zoned = when.atZone(ZoneId.systemDefault());
        int offset = zoned.getOffset().getTotalSeconds();
        return structural(zoned, zoneName(), offset);
    }

    /** The nine items plus {@code tm_zone} and {@code tm_gmtoff}, as {@link PyStructTime} wants them. */
    private static PyStructTime structural(ZonedDateTime zoned, String zone, int offset) {
        int isdst = zoned.getZone().getRules().isDaylightSavings(zoned.toInstant()) ? 1 : 0;
        PyObject[] items = {
            PyInt.of(zoned.getYear()),
            PyInt.of(zoned.getMonthValue()),
            PyInt.of(zoned.getDayOfMonth()),
            PyInt.of(zoned.getHour()),
            PyInt.of(zoned.getMinute()),
            PyInt.of(zoned.getSecond()),
            PyInt.of(zoned.getDayOfWeek().getValue() - 1),
            PyInt.of(zoned.getDayOfYear()),
            PyInt.of(isdst)
        };
        return PyStructTime.of(items, PyStr.of(zone), PyInt.of(offset));
    }

    /** The nine integers a function reads out of a time-tuple argument. */
    private static int[] fields(PyObject[] args, String name) {
        Arguments.exactlyOne(name, args, null);
        return fieldsOf(args[0], name);
    }

    private static int[] fields(PyObject[] args, String name, int index) {
        return fieldsOf(args[index], name);
    }

    private static int[] fieldsOf(PyObject value, String name) {
        if (value instanceof PyStructTime time) {
            int[] f = new int[9];
            for (int i = 0; i < f.length; i++) {
                f[i] = Numbers.asIndex(time.at(i)).intValueExact();
            }
            return f;
        }
        java.util.List<PyObject> items = new java.util.ArrayList<>();
        PyIterator iterator = Slots.iterator(value);
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            items.add(item);
        }
        if (items.size() != 9) {
            throw Slots.typeError("%s(): illegal time tuple argument", name);
        }
        int[] f = new int[9];
        for (int i = 0; i < f.length; i++) {
            f[i] = Numbers.asIndex(items.get(i)).intValueExact();
        }
        return f;
    }

    private static double mktime(int[] f) {
        try {
            LocalDateTime local = LocalDateTime.of(f[0], f[1], f[2], f[3], f[4], f[5]);
            return local.atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (RuntimeException outOfRange) {
            throw Slots.valueError("mktime argument out of range");
        }
    }

    // --- formatting ----------------------------------------------------------------------------------

    private static String asctime(int[] f) {
        return String.format(
                Locale.ROOT,
                "%s %s %2d %02d:%02d:%02d %d",
                DAY_NAMES[f[6]].substring(0, 3),
                MONTH_NAMES[f[1] - 1].substring(0, 3),
                f[2],
                f[3],
                f[4],
                f[5],
                f[0]);
    }

    private static String strftime(String format, int[] f, PyStructTime given) {
        String zone = zoneName();
        long offset = localOffset();
        if (given != null) {
            PyObject givenZone = given.getAttr("tm_zone");
            if (givenZone instanceof PyStr text) {
                zone = text.getValue();
            }
            PyObject givenOffset = given.getAttr("tm_gmtoff");
            if (givenOffset instanceof PyInt number) {
                offset = number.getValue().longValue();
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            if (++i >= format.length()) {
                out.append('%');
                break;
            }
            directive(out, format.charAt(i), f, zone, offset);
        }
        return out.toString();
    }

    private static void directive(
            StringBuilder out, char c, int[] f, String zone, long offset) {
        switch (c) {
            case 'Y' -> out.append(f[0]);
            case 'y' -> pad(out, f[0] % 100, 2);
            case 'm' -> pad(out, f[1], 2);
            case 'd' -> pad(out, f[2], 2);
            case 'e' -> out.append(f[2] < 10 ? " " + f[2] : Integer.toString(f[2]));
            case 'H' -> pad(out, f[3], 2);
            case 'I' -> pad(out, twelveHour(f[3]), 2);
            case 'M' -> pad(out, f[4], 2);
            case 'S' -> pad(out, f[5], 2);
            case 'j' -> pad(out, f[7], 3);
            case 'w' -> out.append((f[6] + 1) % 7);
            case 'u' -> out.append(f[6] + 1);
            case 'a' -> out.append(DAY_NAMES[f[6]].substring(0, 3));
            case 'A' -> out.append(DAY_NAMES[f[6]]);
            case 'b', 'h' -> out.append(MONTH_NAMES[f[1] - 1].substring(0, 3));
            case 'B' -> out.append(MONTH_NAMES[f[1] - 1]);
            case 'p' -> out.append(f[3] < 12 ? "AM" : "PM");
            case 'Z' -> out.append(zone);
            case 'z' -> appendOffset(out, offset);
            case 'c' -> {
                out.append(DAY_NAMES[f[6]].substring(0, 3)).append(' ');
                out.append(MONTH_NAMES[f[1] - 1].substring(0, 3)).append(' ');
                out.append(f[2] < 10 ? " " + f[2] : Integer.toString(f[2])).append(' ');
                pad(out, f[3], 2);
                out.append(':');
                pad(out, f[4], 2);
                out.append(':');
                pad(out, f[5], 2);
                out.append(' ').append(f[0]);
            }
            case 'x' -> {
                pad(out, f[1], 2);
                out.append('/');
                pad(out, f[2], 2);
                out.append('/');
                pad(out, f[0] % 100, 2);
            }
            case 'X' -> {
                pad(out, f[3], 2);
                out.append(':');
                pad(out, f[4], 2);
                out.append(':');
                pad(out, f[5], 2);
            }
            case 'U' -> out.append(sundayWeeks(f));
            case 'W' -> out.append(mondayWeeks(f));
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            case '%' -> out.append('%');
            default -> out.append('%').append(c);
        }
    }

    private static int twelveHour(int hour) {
        int value = hour % 12;
        return value == 0 ? 12 : value;
    }

    private static void pad(StringBuilder out, int value, int width) {
        String text = Integer.toString(Math.abs(value));
        for (int i = text.length(); i < width; i++) {
            out.append('0');
        }
        out.append(value < 0 ? "-" + text : text);
    }

    private static void appendOffset(StringBuilder out, long offset) {
        out.append(offset < 0 ? '-' : '+');
        long absolute = Math.abs(offset);
        pad(out, (int) (absolute / 3600), 2);
        pad(out, (int) ((absolute % 3600) / 60), 2);
    }

    /** {@code %U}: week 0 is the days before the first Sunday, then Sunday-start weeks. */
    private static int sundayWeeks(int[] f) {
        int januaryFirst = Math.floorMod(f[6] - (f[7] - 1), 7);
        int firstSunday = 8 - ((januaryFirst + 1) % 7);
        int sunday = f[7] - ((f[6] + 1) % 7);
        return sunday < firstSunday ? 0 : (sunday - firstSunday) / 7 + 1;
    }

    /** {@code %W}: the same count with Monday as the first day of the week. */
    private static int mondayWeeks(int[] f) {
        int januaryFirst = Math.floorMod(f[6] - (f[7] - 1), 7);
        int firstMonday = 1 + ((7 - januaryFirst) % 7);
        int monday = f[7] - f[6];
        return monday < firstMonday ? 0 : (monday - firstMonday) / 7 + 1;
    }

    // --- the local zone ------------------------------------------------------------------------------

    private static String zoneName() {
        // `java.util.TimeZone` files the same abbreviation the reference reads out of the C library —
        // "CST" — where `ZoneId`'s CLDR short name would say "CT".
        TimeZone zone = TimeZone.getDefault();
        String name = zone.getDisplayName(zone.inDaylightTime(new java.util.Date()), TimeZone.SHORT);
        // A fixed zone reports itself as "GMT+08:00"; the reference files an abbreviation instead.
        if (name.startsWith("GMT") || name.startsWith("UTC")) {
            return "UTC";
        }
        return name;
    }

    private static long localOffset() {
        return ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds();
    }

    /** {@code timezone}: seconds west of UTC, the sign the module has always used. */
    private static int timezone() {
        int millis = TimeZone.getDefault().getRawOffset();
        return -millis / 1000;
    }
}
