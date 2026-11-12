package org.python.core;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * {@code sys.monitoring}: the tool-registration surface a debugger reaches for.
 *
 * <p>CPython's monitoring API hands out six tool ids, remembers which tool owns which name, and then
 * reports execution events to the callbacks those tools registered. This runtime has no monitoring
 * events to report: its frames are JVM frames and its instructions are Java ones. What is here is
 * therefore the bookkeeping half — every id, every refusal and every answer a caller can observe from
 * outside — and none of the reporting. A debugger that registers itself and asks what it registered
 * gets the reference's answers; nothing fires.
 *
 * <p>The one caller that matters today is {@code bdb.py}, which registers itself as
 * {@link #DEBUGGER_ID} when {@code sys.monitoring} exists, reads the event mask back, and then drives
 * tracing through {@code sys.settrace} when the monitoring backend is not the one selected. That path
 * needs exactly what this class answers.
 */
final class Monitoring {

    /** How many tool ids the reference hands out: {@code 0} through {@code 5}. */
    private static final int TOOLS = 6;

    static final int DEBUGGER_ID = 0;
    static final int COVERAGE_ID = 1;
    static final int PROFILER_ID = 2;
    static final int OPTIMIZER_ID = 5;

    /** The event bits, in the reference's numbering. */
    static final int PY_START = 1;
    static final int PY_RESUME = 2;
    static final int PY_RETURN = 4;
    static final int PY_YIELD = 8;
    static final int CALL = 16;
    static final int LINE = 32;
    static final int INSTRUCTION = 64;
    static final int JUMP = 128;
    static final int BRANCH_LEFT = 256;
    static final int BRANCH_RIGHT = 512;
    static final int STOP_ITERATION = 1024;
    static final int RAISE = 2048;
    static final int EXCEPTION_HANDLED = 4096;
    static final int PY_UNWIND = 8192;
    static final int PY_THROW = 16384;
    static final int RERAISE = 32768;
    static final int C_RETURN = 65536;
    static final int C_RAISE = 131072;
    static final int BRANCH = 262144;

    /** The largest mask a tool may set: every bit through {@link #BRANCH}. */
    private static final int ALL_EVENTS = (BRANCH << 1) - 1;

    /**
     * {@code DISABLE}: what a callback returns to have this site not reported again.
     *
     * <p>It is a plain object here as it is in the reference, whose sentinel is also an {@code object()}.
     */
    static final PyObject DISABLE = new Sentinel();

    /** {@code MISSING}: what a tool reads for a callback it never registered. */
    static final PyObject MISSING = new Sentinel();

    /** Which tool owns each id, as the name it registered, or {@code null} where the id is free. */
    private static final PyObject[] names = new PyObject[TOOLS];

    /** The global event mask of each tool. */
    private static final int[] events = new int[TOOLS];

    /** The callback of each {@code (tool, event)} pair, filed under the event's bit value. */
    private static final Map<Integer, PyObject>[] callbacks = new Map[TOOLS];

    /** The local event mask each tool set for a code object, keyed by the code object itself. */
    private static final Map<PyObject, Integer>[] localEvents = new Map[TOOLS];

    private Monitoring() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("sys.monitoring");
        PyRuntime.markBuiltIn(module, "sys.monitoring");
        module.set("DEBUGGER_ID", PyInt.of(DEBUGGER_ID));
        module.set("COVERAGE_ID", PyInt.of(COVERAGE_ID));
        module.set("PROFILER_ID", PyInt.of(PROFILER_ID));
        module.set("OPTIMIZER_ID", PyInt.of(OPTIMIZER_ID));
        module.set("DISABLE", DISABLE);
        module.set("MISSING", MISSING);
        module.set("events", eventsNamespace());
        module.set("use_tool_id", plain("use_tool_id", Monitoring::useToolId));
        module.set("free_tool_id", plain("free_tool_id", Monitoring::freeToolId));
        module.set("get_tool", one("get_tool", Monitoring::getTool));
        module.set("clear_tool_id", one("clear_tool_id", Monitoring::clearToolId));
        module.set("register_callback",
                plain("register_callback", Monitoring::registerCallback));
        module.set("set_events", plain("set_events", Monitoring::setEvents));
        module.set("get_events", one("get_events", Monitoring::getEvents));
        module.set("set_local_events", plain("set_local_events", Monitoring::setLocalEvents));
        module.set("get_local_events", plain("get_local_events", Monitoring::getLocalEvents));
        module.set("restart_events", none("restart_events", args -> PyNone.INSTANCE));
        return module;
    }

    /** {@code sys.monitoring.events}: the twenty event constants, as a namespace object. */
    private static PyNamespace eventsNamespace() {
        PyNamespace namespace = new PyNamespace();
        namespace.set("PY_START", PyInt.of(PY_START));
        namespace.set("PY_RESUME", PyInt.of(PY_RESUME));
        namespace.set("PY_RETURN", PyInt.of(PY_RETURN));
        namespace.set("PY_YIELD", PyInt.of(PY_YIELD));
        namespace.set("CALL", PyInt.of(CALL));
        namespace.set("LINE", PyInt.of(LINE));
        namespace.set("INSTRUCTION", PyInt.of(INSTRUCTION));
        namespace.set("JUMP", PyInt.of(JUMP));
        namespace.set("BRANCH_LEFT", PyInt.of(BRANCH_LEFT));
        namespace.set("BRANCH_RIGHT", PyInt.of(BRANCH_RIGHT));
        namespace.set("STOP_ITERATION", PyInt.of(STOP_ITERATION));
        namespace.set("RAISE", PyInt.of(RAISE));
        namespace.set("EXCEPTION_HANDLED", PyInt.of(EXCEPTION_HANDLED));
        namespace.set("PY_UNWIND", PyInt.of(PY_UNWIND));
        namespace.set("PY_THROW", PyInt.of(PY_THROW));
        namespace.set("RERAISE", PyInt.of(RERAISE));
        namespace.set("C_RETURN", PyInt.of(C_RETURN));
        namespace.set("C_RAISE", PyInt.of(C_RAISE));
        namespace.set("BRANCH", PyInt.of(BRANCH));
        namespace.set("NO_EVENTS", PyInt.of(0));
        return namespace;
    }

    // ---- the calls -------------------------------------------------------------------------

    /** {@code use_tool_id(tool, name)}: claims an id for a name, refusing one already claimed. */
    private static PyObject useToolId(PyObject[] args) {
        Arguments.exactly("use_tool_id", args, null, 2);
        int tool = tool(args[0]);
        // The reference reads the name first: a non-str is refused even when the id is taken.
        if (args[1].getType() != PyType.STR) {
            throw Slots.valueError("tool name must be a str");
        }
        if (names[tool] != null) {
            throw Slots.valueError("tool %d is already in use", tool);
        }
        names[tool] = args[1];
        return PyNone.INSTANCE;
    }

    /** {@code free_tool_id(tool)}: releases the id, which the reference allows even when it is free. */
    private static PyObject freeToolId(PyObject[] args) {
        Arguments.exactly("sys.monitoring.free_tool_id()", args, null, 1);
        int tool = tool(args[0]);
        names[tool] = null;
        events[tool] = 0;
        callbacks[tool] = null;
        localEvents[tool] = null;
        return PyNone.INSTANCE;
    }

    /** {@code get_tool(tool)}: the name that owns the id, or {@code None}. */
    private static PyObject getTool(PyObject[] args) {
        int tool = tool(args[0]);
        PyObject name = names[tool];
        return name == null ? PyNone.INSTANCE : name;
    }

    /**
     * {@code clear_tool_id(tool)}: drops the tool's callbacks and event mask, keeping its name.
     *
     * <p>Which is what the reference does — {@code get_tool} still answers the name afterwards, and a
     * debugger that only cleared would still be the owner.
     */
    private static PyObject clearToolId(PyObject[] args) {
        int tool = tool(args[0]);
        events[tool] = 0;
        callbacks[tool] = null;
        localEvents[tool] = null;
        return PyNone.INSTANCE;
    }

    /**
     * {@code register_callback(tool, event, callback)}: files the callback and answers the one it
     * replaced, or {@code None}.
     *
     * <p>The reference does not check that the callback is callable, so neither does this. It does
     * insist on one event bit: a mask is the {@code set_events} spelling, not this one.
     */
    private static PyObject registerCallback(PyObject[] args) {
        Arguments.exactly("register_callback", args, null, 3);
        int tool = tool(args[0]);
        int event = Numbers.asIndex(args[1]).intValue();
        if (Integer.bitCount(event) != 1) {
            throw Slots.valueError("The callback can only be set for one event at a time");
        }
        if ((event & ~ALL_EVENTS) != 0) {
            throw Slots.valueError("invalid event %d", event);
        }
        Map<Integer, PyObject> filed = callbacks[tool];
        if (filed == null) {
            filed = new HashMap<>();
            callbacks[tool] = filed;
        }
        PyObject previous = filed.put(event, args[2]);
        return previous == null ? PyNone.INSTANCE : previous;
    }

    /** {@code set_events(tool, events)}: replaces the tool's global mask. */
    private static PyObject setEvents(PyObject[] args) {
        Arguments.exactly("set_events", args, null, 2);
        int tool = tool(args[0]);
        int mask = Numbers.asIndex(args[1]).intValue();
        // The mask is read before the tool's state, so a bad event set is reported on a free id too.
        if ((mask & ~ALL_EVENTS) != 0) {
            throw Slots.valueError("invalid event set 0x%s", Integer.toHexString(mask));
        }
        requireInUse(tool);
        events[tool] = expandBranch(mask);
        return PyNone.INSTANCE;
    }

    /** {@code get_events(tool)}: the tool's global mask, or {@code 0} when nothing is registered. */
    private static PyObject getEvents(PyObject[] args) {
        int tool = tool(args[0]);
        return PyInt.of(events[tool]);
    }

    /** {@code set_local_events(tool, code, events)}: records a per-code mask. */
    private static PyObject setLocalEvents(PyObject[] args) {
        Arguments.exactly("set_local_events", args, null, 3);
        int tool = tool(args[0]);
        PyObject code = args[1];
        // The reference checks the argument's shape before the tool's state, so a bad code object is
        // reported even on a free tool.
        if (code.getType() != PyType.CODE) {
            throw Slots.typeError("code must be a code object");
        }
        requireInUse(tool);
        int mask = Numbers.asIndex(args[2]).intValue();
        if ((mask & ~ALL_EVENTS) != 0) {
            throw Slots.valueError("invalid event set 0x%s", Integer.toHexString(mask));
        }
        Map<PyObject, Integer> filed = localEvents[tool];
        if (filed == null) {
            filed = new IdentityHashMap<>();
            localEvents[tool] = filed;
        }
        filed.put(code, expandBranch(mask));
        return PyNone.INSTANCE;
    }

    /** {@code get_local_events(tool, code)}: the mask that tool set for that code, or {@code 0}. */
    private static PyObject getLocalEvents(PyObject[] args) {
        Arguments.exactly("get_local_events", args, null, 2);
        int tool = tool(args[0]);
        PyObject code = args[1];
        if (code.getType() != PyType.CODE) {
            throw Slots.typeError("code must be a code object");
        }
        Map<PyObject, Integer> filed = localEvents[tool];
        Integer mask = filed == null ? null : filed.get(code);
        return PyInt.of(mask == null ? 0 : mask);
    }

    // ---- the shared pieces ----------------------------------------------------------------

    /** {@code BRANCH} is reported as its two legs, and reading the mask back shows both. */
    private static int expandBranch(int mask) {
        if ((mask & BRANCH) == 0) {
            return mask;
        }
        return (mask & ~BRANCH) | BRANCH_LEFT | BRANCH_RIGHT;
    }

    /** The tool id of a call's first argument, or the reference's complaint. */
    private static int tool(PyObject value) {
        int tool = Numbers.asIndex(value).intValue();
        if (tool < 0 || tool >= TOOLS) {
            throw Slots.valueError("invalid tool %d (must be between 0 and %d)", tool, TOOLS - 1);
        }
        return tool;
    }

    private static void requireInUse(int tool) {
        if (names[tool] == null) {
            throw Slots.valueError("tool %d is not in use", tool);
        }
    }

    /**
     * A one-argument function, refused by its qualified name: {@code sys.monitoring.get_tool()}.
     */
    private static PyNativeFunction one(
            String name, java.util.function.Function<PyObject[], PyObject> body) {
        return new PyNativeFunction(name, (args, keywordNames) -> {
            refuseKeywords(name, keywordNames);
            Arguments.exactlyOne("sys.monitoring." + name + "()", args, null);
            return body.apply(args);
        });
    }

    /** A function whose arguments are counted: {@code set_events expected 2 arguments, got 1}. */
    private static PyNativeFunction plain(
            String name, java.util.function.Function<PyObject[], PyObject> body) {
        return new PyNativeFunction(name, (args, keywordNames) -> {
            refuseKeywords(name, keywordNames);
            return body.apply(args);
        });
    }

    /** A function taking nothing: {@code sys.monitoring.restart_events()}. */
    private static PyNativeFunction none(
            String name, java.util.function.Function<PyObject[], PyObject> body) {
        return new PyNativeFunction(name, (args, keywordNames) -> {
            Arguments.none("sys.monitoring." + name + "()", args, keywordNames);
            return body.apply(args);
        });
    }

    private static void refuseKeywords(String name, String[] keywordNames) {
        if (keywordNames != null) {
            throw Slots.typeError("sys.monitoring.%s() takes no keyword arguments", name);
        }
    }

    /** A sentinel whose repr is the reference's: {@code <object object at 0x...>}. */
    private static final class Sentinel extends PyObject {

        Sentinel() {
            super(PyType.OBJECT);
        }

        @Override
        public String repr() {
            return "<object object at 0x" + Integer.toHexString(System.identityHashCode(this))
                    + ">";
        }
    }
}
