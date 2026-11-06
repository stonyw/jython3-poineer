package org.python.core;

import java.io.ByteArrayOutputStream;

/**
 * The names a code object answers from what the {@link PyCode} interface already carries.
 *
 * <p>This is the runtime half of {@code code}'s attribute surface; the front-end keeps the other half,
 * because {@code co_code}, {@code co_linetable} and {@code co_exceptiontable} are the bytes one specific
 * format writes and only the reader of those bytes can hand them out. Everything here is a number, a name,
 * a tuple derived from the localsplus array, a run of positions the {@link PyCode} interface already merges
 * for it or a legacy byte table cut out of those same runs, so it is the same for a code object read from a
 * {@code .pyc} and one a native parser builds.
 *
 * <p>CPython's own split of the names is kept visible, because it is what a program feels: the fourteen
 * stored fields are {@code PyMemberDef}s marked read-only and answer a write with the generic complaint
 * about a read-only attribute, while the six derived ones are getsets whose {@code gd_set} is {@code NULL}
 * and name the attribute and the type. The difference is only in wording — none of them can be written — but
 * a script that prints the exception text of each refusal sees it, and it is the {@link TypeDict} table that
 * files both kinds, so the sentence a write raises comes from the row rather than from anything here.
 *
 * <p>What is not here is what CPython's {@code code} also publishes and this runtime cannot answer yet:
 * {@code co_branches()}, which would have to know which jumps the instrumentation considers branches;
 * {@code replace()} and {@code __replace__()}, which would have to rebuild the table from eighteen
 * keyword-only fields; and the live specialization state {@code _co_code_adaptive} shows, which this runtime
 * never writes and so answers as the plain instruction array. They are refused as missing or answered as the
 * same bytes. The two position reads — {@code co_lines()} and {@code co_positions()} — are here, and are what
 * a Stage-B report would draw a caret from: the report this slice leaves behind still names a line and
 * nothing beside it.
 *
 * <p>Beside those names, two rows answer a slot rather than a function of their own, and what they run is
 * here for that reason. {@code __sizeof__} measures a C struct this runtime has no half of; {@code
 * tp_richcompare} is one C function filed under six names and is the only thing in this family that asks
 * whether two code objects hold the same body. Both are stated in the reference's own arithmetic and
 * measured against it rather than reproduced from it, because a code object here has no layout and its two
 * tables have whatever shape the front-end that read them gives them.
 */
public final class CodeMembers {

    /** {@code code_varname_from_oparg}'s clinic text: the table, then the warning that is all of its doc. */
    private static final String VARNAME_DOC =
            "($self, /, oparg)\n--\n\n(internal-only) Return the local variable name for the given oparg."
                    + "\n\nWARNING: this method is for internal use only and may change or go\naway.";

    /**
     * The two numbers {@code code_sizeof} multiplies and adds, and the word it rounds the sum up to.
     *
     * <p>{@code offsetof(PyCodeObject, co_code_adaptive)} is {@code tp_basicsize} and {@code
     * sizeof(_Py_CODEUNIT)} is {@code tp_itemsize}, both of them properties of a C build of 3.14 rather than
     * of anything the {@link PyCode} interface answers; {@code __sizeof__} is measured against them because
     * no other answer would be the reference's, which is the same choice {@code int}'s {@code __sizeof__}
     * makes about a {@code PyLongObject}.
     */
    private static final int CODE_HEADER = 208;

    private static final int UNIT_WIDTH = 2;
    private static final int WORD = 8;

    /**
     * The four methods {@code code} declares.
     *
     * <p>A member table rather than four more cases in {@link #getAttr}, because the table is what decides
     * the shape of the answer: CPython reads {@code co.co_lines} out of the type's method table and binds it,
     * so an argument is complained about with the type's name in front — {@code code.co_lines() takes no
     * arguments (1 given)} — while {@code code.co_lines} without a code object is the unbound descriptor that
     * takes the receiver as its first argument and refuses one of another type. Both shapes come from {@link
     * Members} and from nothing else here.
     *
     * <p>The two beside them are the rows of {@code code_methods} that need no code object's format to answer.
     * {@code __sizeof__} is registered with no {@code ml_doc} at all, so its table comes from its {@code
     * METH_NOARGS} and its sentence is {@code None}. {@code _varname_from_oparg} is the one row here that
     * clinic parsed rather than hand-registered: its text carries its table and its warning, its two arity
     * refusals name the function without the type in front of them, and its converter is a C {@code int}.
     */
    private static final Members METHODS = Members.of(PyType.CODE)
            .addNoArgument("__sizeof__", self -> PyInt.of(sizeOf((PyCode) self)))
            .addNoArgument(
                    "co_lines",
                    self -> PyIterator.of(PyType.LINE_ITERATOR, ((PyCode) self).lineRanges().elements()))
            .addNoArgument(
                    "co_positions",
                    self ->
                            PyIterator.of(
                                    PyType.POSITIONS_ITERATOR, ((PyCode) self).positions().elements()))
            .add(
                    "_varname_from_oparg",
                    VARNAME_DOC,
                    (self, args, kwNames) -> localName((PyCode) self, args, kwNames));

    private CodeMembers() {
    }

    /**
     * {@code co.name} for the names derived from the interface.
     *
     * <p>The twenty storage names of {@code code}'s dictionary read through this method, so the row a
     * program asks for and the attribute it writes are one answer rather than two that happen to agree.
     *
     * @return the value, or {@link PyNotImplemented} for a name this half does not own — including the four
     *     byte tables, which the front-end answers, and a name no code object has
     */
    public static PyObject getAttr(PyCode code, String name) {
        return switch (name) {
            case "co_argcount" -> PyInt.of(code.argCount());
            case "co_posonlyargcount" -> PyInt.of(code.posOnlyArgCount());
            case "co_kwonlyargcount" -> PyInt.of(code.kwOnlyArgCount());
            case "co_stacksize" -> PyInt.of(code.stackSize());
            case "co_flags" -> PyInt.of(code.flags());
            case "co_firstlineno" -> PyInt.of(code.firstLine());
            case "co_filename" -> PyStr.of(code.fileName());
            case "co_name" -> PyStr.of(code.name());
            case "co_qualname" -> PyStr.of(code.qualName());
            case "co_consts" -> code.constants();
            case "co_names" -> code.names();
            // co_nlocals is the count of names that are plain locals, which is len(co_varnames); cells
            // and free variables are counted separately, as CPython's get_localsplus_counts does.
            case "co_nlocals" -> PyInt.of(code.varNames().size());
            case "co_varnames" -> code.varNames();
            case "co_cellvars" -> code.cellVars();
            case "co_freevars" -> code.freeVars();
            case "co_lnotab" -> legacyTable(code);
            // The methods, reached through a value and therefore bound to it.
            default -> Members.find((PyObject) code, name);
        };
    }

    /**
     * {@code co_lnotab}: the legacy byte pairs, rebuilt from the runs {@code co_lines()} answers.
     *
     * <p>3.14 no longer keeps this table and still hands it out, so the read walks the same runs the new
     * format was written from and cuts them back into pairs. Only a run that <em>reaches a new line</em>
     * writes one: the walk holds the line and the address it last wrote, and a run under the line it already
     * has is passed over with the address it brought, which is why a function's table never mentions the
     * line {@code co_firstlineno} names even though its first run sits on it. The rest is the width of a pair
     * forcing the shape: an address gap too wide for one byte is filed as {@code (255, 0)} runs until it
     * fits, and a line jump too wide for a signed byte as {@code (gap, 127)} then {@code (0, 127)} runs — or
     * -128 running downward, which is what the jump back to a loop's own line writes — with the address
     * riding on the first pair and nothing on the ones after it. A run with no line under it is skipped for
     * the same reason, and the walk starts at {@code co_firstlineno} and offset nought, which is why a
     * module's table opens by walking back to line nought.
     *
     * <p>The reference interpreter warns about the name on every read — {@code co_lnotab is deprecated, use
     * co_lines instead} — and this runtime has no warning machinery to write to a program's stderr, so no
     * differential case can print what this returns. It is measured against the captured pairs instead, for
     * the five shapes {@code LegacyLineTableTest} names.
     */
    private static PyObject legacyTable(PyCode code) {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        int previousLine = code.firstLine();
        int previousAddress = 0;
        for (PyObject entry : code.lineRanges().elements()) {
            PyTuple range = (PyTuple) entry;
            if (!(range.at(2) instanceof PyInt line)) {
                continue;
            }
            int address = ((PyInt) range.at(0)).getValue().intValue();
            int lineNumber = line.getValue().intValue();
            if (lineNumber == previousLine) {
                continue;
            }
            int addressGap = address - previousAddress;
            int lineJump = lineNumber - previousLine;
            while (addressGap > 255) {
                writePair(table, 255, 0);
                addressGap -= 255;
            }
            while (lineJump > 127) {
                writePair(table, addressGap, 127);
                addressGap = 0;
                lineJump -= 127;
            }
            while (lineJump < -128) {
                writePair(table, addressGap, -128);
                addressGap = 0;
                lineJump += 128;
            }
            writePair(table, addressGap, lineJump);
            previousLine = lineNumber;
            previousAddress = address;
        }
        return PyBytes.wrap(table.toByteArray());
    }

    /** One {@code (address, line)} pair of the legacy table, each half folded into a signed byte. */
    private static void writePair(ByteArrayOutputStream table, int address, int line) {
        table.write(address & 0xff);
        table.write(line & 0xff);
    }

    /**
     * {@code code_sizeof}: {@code _PyObject_VAR_SIZE} over this body's unit count.
     *
     * <p>The reference adds nothing else, because {@code co_extra} is the instrumentation's and this runtime
     * never runs it — a code object that has been traced in CPython reports a larger number than the same
     * object reports here, and the difference is the caches the interpreter grew over its instructions.
     */
    private static long sizeOf(PyCode code) {
        long raw = CODE_HEADER + (long) UNIT_WIDTH * code.unitCount();
        return (raw + WORD - 1) & -(long) WORD;
    }

    /**
     * {@code code._varname_from_oparg(oparg)}: the name of the frame slot that oparg indexes.
     *
     * <p>Clinic asks its one positional parameter before its converter looks at anything, which is what makes
     * {@code _varname_from_oparg(index=0)} complain about the argument that never arrived rather than about
     * the name nobody has, and both of its arity sentences name the function with no type in front of them.
     * The converter is the narrowest one in CPython — {@code oparg: int} is a C {@code int}, so {@code 2 **
     * 70} overflows it where a wide {@code __index__} would have been accepted by a {@code Py_ssize_t}. What
     * the number indexes is {@code co_localsplusnames}, one slot wider than {@code co_varnames} wherever a
     * cell or a free variable exists, and {@code PyTuple_GetItem} answers a position past either end of it in
     * that tuple's own words.
     */
    private static PyObject localName(PyCode code, PyObject[] args, String[] kwNames) {
        PyObject[] given =
                Arguments.unpackKeywords(
                        "_varname_from_oparg", args, kwNames, new String[] {"oparg"}, 1, 1);
        int oparg = Numbers.cInteger(given[0]);
        PyTuple names = code.localNames();
        if (oparg < 0 || oparg >= names.size()) {
            throw Slots.indexError("tuple index out of range");
        }
        return names.at(oparg);
    }

    /**
     * {@code code_richcompare}: the two equalities, and only between two code objects.
     *
     * <p>The four orderings decline whatever they are asked, so {@code CODE < CODE} is the {@code TypeError}
     * naming both types while {@code CODE == CODE} answers, and a non-code operand is declined rather than
     * answered — which is what makes {@code CODE == 3} {@code False} out of the operator's fallback to
     * identity while {@code CODE.__eq__(3)} hands the refusal back as the value {@code NotImplemented}.
     *
     * <p>Two of the C's thirteen questions are not answerable from the {@link PyCode} interface, and this walk
     * substitutes the decoded form of each: the C compares {@code co_linetable} and {@code co_exceptiontable}
     * as the octet runs one format writes, and here the line table is the runs of {@code co_lines()} together
     * with the quadruples of {@code co_positions()} and the exception table is the handler every instruction
     * is asked for. Two front-ends that pack one answer differently are therefore equal here where the
     * reference would call them unequal. {@code co_consts} has the same gap in a smaller shape: the C keys
     * every constant first, so {@code 0} and {@code False} never pass for each other and a bytes constant is
     * never compared against a string, while this asks {@code ==} of the values themselves.
     */
    public static PyObject compare(CompareOp cmp, PyObject left, PyObject right) {
        if ((cmp != CompareOp.EQ && cmp != CompareOp.NE)
                || !(left instanceof PyCode one)
                || !(right instanceof PyCode two)) {
            return PyNotImplemented.INSTANCE;
        }
        return PyBool.of(cmp == CompareOp.EQ ? agrees(one, two) : !agrees(one, two));
    }

    /**
     * The C's walk in its own order: the name, the five numbers, the size, the instruction stream, then the
     * tables — and any one of them disagreeing ends it.
     */
    private static boolean agrees(PyCode left, PyCode right) {
        return left.name().equals(right.name())
                && left.argCount() == right.argCount()
                && left.posOnlyArgCount() == right.posOnlyArgCount()
                && left.kwOnlyArgCount() == right.kwOnlyArgCount()
                && left.flags() == right.flags()
                && left.firstLine() == right.firstLine()
                && left.unitCount() == right.unitCount()
                && sameInstructions(left.instructions(), right.instructions())
                && sameItems(left.constants(), right.constants())
                && sameItems(left.names(), right.names())
                && sameItems(left.localNames(), right.localNames())
                && sameItems(left.lineRanges(), right.lineRanges())
                && sameItems(left.positions(), right.positions())
                && sameHandlers(left, right);
    }

    /** The two streams instruction by instruction, with the unit offset each one sits at beside them. */
    private static boolean sameInstructions(CodeInstructions left, CodeInstructions right) {
        if (left.length() != right.length()) {
            return false;
        }
        for (int i = 0; i < left.length(); i++) {
            if (left.opcodeAt(i) != right.opcodeAt(i)
                    || left.argAt(i) != right.argAt(i)
                    || left.unitOffsetAt(i) != right.unitOffsetAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** {@code PyObject_RichCompareBool} over the items of two tuples, which is what the C's own walk does. */
    private static boolean sameItems(PyTuple left, PyTuple right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!Slots.compareTrue(CompareOp.EQ, left.at(i), right.at(i))) {
                return false;
            }
        }
        return true;
    }

    /** The exception table read the way the interpreter reads it: one handler per instruction. */
    private static boolean sameHandlers(PyCode left, PyCode right) {
        int steps = left.instructions().length();
        for (int i = 0; i < steps; i++) {
            PyCode.Handler one = left.handlerFor(i);
            PyCode.Handler two = right.handlerFor(i);
            if (one == null || two == null ? one != two : !one.equals(two)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code co.name = value} and {@code del co.name}, which is the same refusal either way.
     *
     * <p>The two wordings that remain are CPython's own: a name the type files as a row — a wrapper, a
     * method or one of the twenty storage names — is refused by that row or by the read-only branch of
     * {@link GenericAttributes}, which says the same sentence in its own words, while the three names this
     * runtime has no row for are refused here because a walk that finds nothing on the class goes on to look
     * for a dictionary instead. A name nobody files at all is left to that same walk, which reads the type's
     * own dictionary and so calls {@code __doc__} read-only, reaches {@code object}'s getset for {@code
     * __class__}, and says there is no dictionary for a name nothing files.
     *
     * @throws PyRaise always, either with the wording of the table the name is in or as the generic refusal
     */
    public static void refuse(PyObject code, String name, PyObject value) {
        if (method(name)) {
            throw Slots.attributeError(
                    "'code' object attribute '" + name + "' is read-only");
        }
        if (value == null) {
            GenericAttributes.delete(code, name);
        } else {
            GenericAttributes.write(code, name, value);
        }
    }

    /**
     * The methods {@code code} publishes, which a write reaches through the type and so cannot replace.
     *
     * <p>{@code co_lines}, {@code co_positions}, {@code __sizeof__} and {@code _varname_from_oparg} are filed
     * as rows now and answer their own refusal, so they are not named here; the three left are the ones this
     * runtime has no body for yet — {@code co_branches}, {@code replace} and {@code __replace__}, which
     * CPython's {@code code_methods} table files as {@code PyMethodDef} rows and which a program therefore
     * meets with the sentence for a read-only attribute rather than with a missing one.
     */
    private static boolean method(String name) {
        return switch (name) {
            case "co_branches", "replace", "__replace__" -> true;
            default -> false;
        };
    }
}
