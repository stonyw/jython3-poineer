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
 * <p>What is not here is the one name of CPython's that answers what a running interpreter writes into a code
 * array: {@code _co_code_adaptive} is the instruction stream plus the specialization its caches have settled
 * on, and this runtime writes no cache, so the name answers the plain bytes {@code co_code} does. {@code
 * co_branches()} is the other read that the instrumentation's own C file suggests this runtime cannot answer,
 * and it can: which six opcodes have two ways is a property of an array no monitor has ever touched, so its
 * rows are here beside the two position reads. Three cursors then, over what the front-end decoded, and the
 * positions are what a Stage-B report would draw a caret from — the report this runtime leaves behind still
 * names a line and nothing beside it.
 *
 * <p>Beside those names, three rows answer a slot rather than a function of their own, and what they run is
 * here for that reason. {@code __sizeof__} measures a C struct this runtime has no half of; {@code
 * tp_richcompare} is one C function filed under six names and is the only thing in this family that asks
 * whether two code objects hold the same body; and {@code __new__} is the class's own creator, the one row of
 * its thirty-seven with no code object in front of it to take a code format from. All three are stated in the
 * reference's own arithmetic and measured against it rather than reproduced from it, because a code object
 * here has no layout and its two tables have whatever shape the front-end that read them gives them.
 */
public final class CodeMembers {

    /** {@code code_varname_from_oparg}'s clinic text: the table, then the warning that is all of its doc. */
    private static final String VARNAME_DOC =
            "($self, /, oparg)\n--\n\n(internal-only) Return the local variable name for the given oparg."
                    + "\n\nWARNING: this method is for internal use only and may change or go\naway.";

    /** {@code code_replace}'s clinic text, whose table {@code @text_signature} wrote rather than the flags. */
    private static final String REPLACE_DOC =
            "replace($self, /, **changes)\n--\n\nReturn a copy of the code object with new values for the"
                    + " specified fields.";

    /** The same function registered a second time, with the sentence {@code code_methods} hands it. */
    private static final String REPLACED_DOC =
            "__replace__($self, /, **changes)\n--\n\nThe same as replace().";

    /**
     * The eighteen names {@code code_replace} takes, in the order its clinic table converts them.
     *
     * <p>The order is the one a program feels when two of its values are wrong at once: {@code
     * co_consts=-1} is complained about as a tuple before {@code co_names=-1} is reached, and the seven
     * counts' {@code must be a positive integer} refusals come only after every converter has run.
     */
    private static final String[] FIELDS = {
        "co_argcount",
        "co_posonlyargcount",
        "co_kwonlyargcount",
        "co_nlocals",
        "co_stacksize",
        "co_flags",
        "co_firstlineno",
        "co_code",
        "co_consts",
        "co_names",
        "co_varnames",
        "co_freevars",
        "co_cellvars",
        "co_filename",
        "co_name",
        "co_qualname",
        "co_linetable",
        "co_exceptiontable"
    };

    /** How many of the eighteen clinic reads into a C {@code int}, and therefore refuses a negative one. */
    private static final int COUNTS = 7;

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
     * The seven methods {@code code} declares.
     *
     * <p>A member table rather than seven more cases in {@link #getAttr}, because the table is what decides
     * the shape of the answer: CPython reads {@code co.co_lines} out of the type's method table and binds it,
     * so an argument is complained about with the type's name in front — {@code code.co_lines() takes no
     * arguments (1 given)} — while {@code code.co_lines} without a code object is the unbound descriptor that
     * takes the receiver as its first argument and refuses one of another type. Both shapes come from {@link
     * Members} and from nothing else here.
     *
     * <p>The four beside them are the rows of {@code code_methods} that need no code object's format to
     * answer. {@code __sizeof__} is registered with no {@code ml_doc} at all, so its table comes from its
     * {@code METH_NOARGS} and its sentence is {@code None} — the way the three cursors are registered too,
     * each of them over a C function that only starts a walk. {@code _varname_from_oparg} is the one row here
     * that clinic parsed rather than hand-registered: its text carries its table and its warning, its two
     * arity refusals name the function without the type in front of them, and its converter is a C {@code
     * int}. {@code replace} and {@code __replace__} are one function filed twice, and the only route this
     * runtime has to a code object's builder that has a code object to take its format from; {@link
     * #construct} is the route that has none.
     */
    private static final Members METHODS = Members.of(PyType.CODE)
            .addNoArgument("__sizeof__", self -> PyInt.of(sizeOf((PyCode) self)))
            .addNoArgument(
                    "co_lines",
                    self -> PyIterator.of(PyType.LINE_ITERATOR, ((PyCode) self).lineRanges().elements()))
            .addNoArgument(
                    "co_branches",
                    self ->
                            PyIterator.of(
                                    PyType.BRANCHES_ITERATOR, ((PyCode) self).branches().elements()))
            .addNoArgument(
                    "co_positions",
                    self ->
                            PyIterator.of(
                                    PyType.POSITIONS_ITERATOR, ((PyCode) self).positions().elements()))
            .add(
                    "_varname_from_oparg",
                    VARNAME_DOC,
                    (self, args, kwNames) -> localName((PyCode) self, args, kwNames))
            .add("replace", REPLACE_DOC, (self, args, kwNames) -> replace(self, args, kwNames))
            .add("__replace__", REPLACED_DOC, (self, args, kwNames) -> replace(self, args, kwNames));

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
     * {@code code_new}: eighteen fields read in clinic's own order, then the builder of the format this
     * interpreter is running.
     *
     * <p>Two refusals come before any value is looked at, and both name the class rather than the row: the
     * keyword check, because every one of the eighteen is positional-only, and the count check, whose
     * minimum is sixteen because {@code freevars} and {@code cellvars} are the two fields the table gives a
     * default. {@code code(15)} and {@code code(x=1)} are therefore refused in that order whichever way the
     * call was made, and {@code code.__new__(code, x=1)} reaches the same two sentences after the four gates
     * {@link TypeDict} runs before it hands the tail over.
     *
     * <p>What follows is the order the generated C converts in, and it is the order a program with two wrong
     * values hears one of them: six counts read into a C {@code int} — so a float is refused as {@code
     * 'float' object cannot be interpreted as an integer} and {@code 2 ** 70} overflows — then the three
     * bytes and four tuples and three strings of the middle, then the fourteenth count, then the two tables.
     * The types are named by position, {@code code() argument 7 must be bytes, not str}, which is what
     * {@code replace} does not have to say because its parameters are keywords. Only after all that do
     * {@code code_new}'s own four refusals come, in the order its body reads them, and then the four name
     * runs are checked for holding nothing but strings — a refusal the C makes here, in its own words, and
     * never again in {@code intern_strings}, which is why the {@code SystemError} that names a non-string in
     * a code slot answers only a copy made with {@code replace()}.
     *
     * <p>The builder is the one thing this route has no receiver for, and a {@link PyCode} is where decision
     * D2 keeps the format. The frame that made the call answers for one: its code object came from whichever
     * front-end this interpreter is running, and asking it is the same trade {@code super()} with no
     * arguments and a class body's {@code __module__} already make. Reached from Java with no frame running
     * there is no format to name, which is a case no Python program can fall into.
     *
     * <p>Three steps of the C are not here. Its audit hook runs before the four refusals, and this runtime
     * writes no audit events; the copy {@code validate_and_copy_tuple} makes of each name run is left out,
     * because a name run of this runtime is derived afresh on every read and so has no identity for the
     * copy to change; and the guard on the keyword refusal — {@code type == &PyCode_Type ||
     * type->tp_init == PyCode_Type.tp_init} — is a question only a subclass of {@code code} can ask, and
     * none is buildable here.
     */
    public static PyObject construct(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("code()", kwNames);
        Arguments.between("code", args, kwNames, 16, 18);
        int argCount = Numbers.cInteger(args[0]);
        int posOnlyArgCount = Numbers.cInteger(args[1]);
        int kwOnlyArgCount = Numbers.cInteger(args[2]);
        int nLocals = Numbers.cInteger(args[3]);
        int stackSize = Numbers.cInteger(args[4]);
        int flags = Numbers.cInteger(args[5]);
        PyBytes code = (PyBytes) argument(7, "bytes", args[6]);
        PyTuple constants = (PyTuple) argument(8, "tuple", args[7]);
        PyTuple names = (PyTuple) argument(9, "tuple", args[8]);
        PyTuple varNames = (PyTuple) argument(10, "tuple", args[9]);
        PyStr fileName = (PyStr) argument(11, "str", args[10]);
        PyStr name = (PyStr) argument(12, "str", args[11]);
        PyStr qualName = (PyStr) argument(13, "str", args[12]);
        int firstLine = Numbers.cInteger(args[13]);
        PyBytes lineTable = (PyBytes) argument(15, "bytes", args[14]);
        PyBytes exceptionTable = (PyBytes) argument(16, "bytes", args[15]);
        int given = Arguments.positional(args, kwNames);
        PyTuple freeVars = given < 17 ? PyTuple.of() : (PyTuple) argument(17, "tuple", args[16]);
        PyTuple cellVars = given < 18 ? PyTuple.of() : (PyTuple) argument(18, "tuple", args[17]);
        negative("argcount", argCount);
        negative("posonlyargcount", posOnlyArgCount);
        negative("kwonlyargcount", kwOnlyArgCount);
        negative("nlocals", nLocals);
        nameTuple(names);
        nameTuple(varNames);
        nameTuple(freeVars);
        nameTuple(cellVars);
        Frame caller = Interpreter.currentFrame();
        if (caller == null) {
            throw Slots.systemError("code.__new__() outside a running frame has no code format to build");
        }
        return (PyObject)
                caller
                        .code()
                        .newCode(
                                new PyCode.Fields(
                                        argCount,
                                        posOnlyArgCount,
                                        kwOnlyArgCount,
                                        nLocals,
                                        stackSize,
                                        flags,
                                        code,
                                        constants,
                                        names,
                                        varNames,
                                        freeVars,
                                        cellVars,
                                        fileName.getValue(),
                                        name.getValue(),
                                        qualName.getValue(),
                                        firstLine,
                                        lineTable,
                                        exceptionTable));
    }

    /** {@code code_new}'s own four refusals, made after every converter of the eighteen has run. */
    private static void negative(String count, int value) {
        if (value < 0) {
            throw Slots.valueError("code: %s must not be negative", count);
        }
    }

    /**
     * {@code validate_and_copy_tuple}: the four name runs hold strings and nothing else.
     *
     * <p>The sentence names no parameter, which is what makes it a different complaint from {@link
     * #argument}'s — the run's own contents are what the C found fault with, and it reaches this after the
     * whole clinic table has converted, so {@code code(*values, names=(1,))} with a bad {@code co_code}
     * hears about the code first. An item that is a {@code str} <em>subclass</em> passes the check and is
     * flattened to an exact string there; nothing here can build one, since {@code class X(str)} is not
     * implemented.
     */
    private static void nameTuple(PyTuple names) {
        for (PyObject item : names.getArray()) {
            if (!(item instanceof PyStr)) {
                throw Slots.typeError(
                        "name tuples must contain only strings, not '%s'",
                        item.getType().filedName());
            }
        }
    }

    /**
     * {@code _PyArg_BadArgument("code", "argument N", kind, value)} for one of the twelve the clinic table
     * checks rather than converts.
     */
    private static PyObject argument(int at, String kind, PyObject value) {
        Class<?> type =
                switch (kind) {
                    case "bytes" -> PyBytes.class;
                    case "tuple" -> PyTuple.class;
                    default -> PyStr.class;
                };
        if (!type.isInstance(value)) {
            throw Slots.typeError(
                    "code() argument %d must be %s, not %s", at, kind, value.getType().filedName());
        }
        return value;
    }

    /**
     * {@code code_replace}: eighteen keyword-only fields, each defaulting to the receiver's own value, then
     * the builder of whichever format that receiver was read from.
     *
     * <p>The defaults are read as attributes rather than from the C's struct fields, which is what makes one
     * route serve both {@code replace} and the {@code __replace__} that copy machinery looks for: a code
     * object's eighteen names are answered by {@link #getAttr} and by the front-end underneath it, and the
     * three runs a nested scope shares are derived from the localsplus array exactly as {@code
     * get_localsplus_names} derives them. {@code co_code} is the one default the C works for rather than
     * copies — {@code _PyCode_GetCode} unzips the specialized array — and this runtime hands out the plain
     * instruction stream, which is what that call answers in an interpreter that has specialized nothing.
     *
     * <p>Two of the C's steps are named rather than copied. Its audit hook runs before the builder, and this
     * runtime has no audit machinery to write to; its {@code co_filename} and {@code co_name} are {@code
     * unicode} converters that keep the object handed over, and the {@link PyCode} interface answers the
     * three names as Java strings, so a {@code str} subclass is flattened here and kept there — which is a
     * difference nothing can reach yet, since {@code class X(str)} is not implemented and the {@code
     * SystemError} the C raises for a subclass inside {@code co_names} has the same wall in front of it.
     *
     * <p>{@code co_stacksize} is the one field whose answer is not what was given: {@code init_code} refuses
     * to build a frame that cannot hold a value, so nought comes back as one.
     */
    private static PyObject replace(PyObject code, PyObject[] args, String[] kwNames) {
        PyObject[] given = Arguments.unpackKeywords("replace", args, kwNames, FIELDS, 0, 0);
        int[] counts = new int[COUNTS];
        PyObject[] values = new PyObject[FIELDS.length - COUNTS];
        for (int at = 0; at < FIELDS.length; at++) {
            PyObject value = given[at] == null ? code.getAttr(FIELDS[at]) : given[at];
            if (at < COUNTS) {
                counts[at] = Numbers.cInteger(value);
            } else {
                values[at - COUNTS] = kept(FIELDS[at], value, at);
            }
        }
        for (int at = 0; at < COUNTS; at++) {
            if (counts[at] < 0) {
                throw Slots.valueError("%s must be a positive integer", FIELDS[at]);
            }
        }
        PyCode.Fields fields =
                new PyCode.Fields(
                        counts[0],
                        counts[1],
                        counts[2],
                        counts[3],
                        counts[4],
                        counts[5],
                        (PyBytes) values[0],
                        (PyTuple) values[1],
                        (PyTuple) values[2],
                        (PyTuple) values[3],
                        (PyTuple) values[4],
                        (PyTuple) values[5],
                        text(values[6]),
                        text(values[7]),
                        text(values[8]),
                        counts[6],
                        (PyBytes) values[9],
                        (PyBytes) values[10]);
        return (PyObject) ((PyCode) code).newCode(fields);
    }

    /**
     * {@code object(subclass_of=…)} and {@code unicode} for one of the eleven: the value, or the sentence
     * {@code _PyArg_BadArgument} writes with the parameter's own name in it.
     *
     * <p>The types are checked in the order the clinic table declares them, which is what makes {@code
     * replace(co_names=-1, co_consts=-1)} complain about {@code co_consts} — the earlier of the two — even
     * though the call named the other one first.
     */
    private static PyObject kept(String field, PyObject value, int at) {
        String kind = switch (at) {
            case 7, 16, 17 -> "bytes";
            case 8, 9, 10, 11, 12 -> "tuple";
            default -> "str";
        };
        Class<?> type =
                switch (kind) {
                    case "bytes" -> PyBytes.class;
                    case "tuple" -> PyTuple.class;
                    default -> PyStr.class;
                };
        if (!type.isInstance(value)) {
            throw Slots.typeError(
                    "replace() argument '%s' must be %s, not %s",
                    field, kind, value.getType().name());
        }
        return value;
    }

    /** One of the three names, which the {@link PyCode} interface answers as text rather than as an object. */
    private static String text(PyObject value) {
        return ((PyStr) value).getValue();
    }

    /**
     * {@code code_richcompare}: the two equalities, and only between two code objects.
     *
     * <p>The four orderings decline whatever they are asked, so {@code CODE < CODE} is the {@code TypeError}
     * naming both types while {@code CODE == CODE} answers, and a non-code operand is declined rather than
     * answered — which is what makes {@code CODE == 3} {@code False} out of the operator's fallback to
     * identity while {@code CODE.__eq__(3)} hands the refusal back as the value {@code NotImplemented}.
     *
     * <p>Two of the C's thirteen questions are not answerable from the {@link PyCode} interface as the C
     * answers them, and this walk reaches each by the route this runtime has. The C compares {@code
     * co_linetable} as the octet run one format writes, and here it is the runs of {@code co_lines()}
     * together with the quadruples of {@code co_positions()} — two front-ends that pack one answer
     * differently are therefore equal here where the reference would call them unequal. {@code co_consts} has
     * the same gap in a smaller shape: the C keys every constant first with {@code _PyCode_ConstantKey}, so
     * {@code 0} and {@code False} never pass for each other and a bytes constant is never compared against a
     * string, while this asks {@code ==} of the values themselves. The exception table is answered as the C
     * answers it, on the octets the front-end hands out under that name, and that is the one table here that
     * can be weighed without asking what it means: decoding it is the interpreter's own question, and only
     * something unwinding through the body that wrote the rows has to answer it.
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
                && sameExceptionTable(left, right);
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

    /**
     * The exception table as the C compares it: the packed bytes, not what they mean to an interpreter.
     *
     * <p>Reaching them is the front-end's own answer to {@code co_exceptiontable}, which decision D2 keeps
     * there because only the reader that unpacked them can say what one is. Walking the handlers instead
     * would ask that reader to decode a table some other body wrote, and a row naming a unit no instruction
     * of this body starts at is a complaint the C never hears — it only ever compares the bytes it was given.
     */
    private static boolean sameExceptionTable(PyCode left, PyCode right) {
        return Slots.compareTrue(
                CompareOp.EQ,
                Slots.getAttr((PyObject) left, "co_exceptiontable"),
                Slots.getAttr((PyObject) right, "co_exceptiontable"));
    }

    /**
     * {@code co.name = value} and {@code del co.name}, which is the same refusal either way.
     *
     * <p>The two wordings that remain are CPython's own, and this runtime reaches both of them the way the
     * reference does: a name the type files as a row — a wrapper, a method or one of the twenty storage names
     * — is refused by that row or by the read-only branch of {@link GenericAttributes}, which says the same
     * sentence in its own words, and the walk that finds nothing on the class goes on to look for a dictionary
     * instead. A name nobody files at all is left to that same walk, which reads the type's own dictionary and
     * so calls {@code __doc__} read-only, reaches {@code object}'s getset for {@code __class__}, and says there
     * is no dictionary for a name nothing files.
     *
     * <p>The methods are the reason the walk has to be the one that answers: a row with no writing half is
     * found by the lookup before the dictionary is reached, so {@code co.co_branches = 1} is called read-only
     * rather than missing, exactly as the reference calls it.
     *
     * @throws PyRaise always, either with the wording of the table the name is in or as the generic refusal
     */
    public static void refuse(PyObject code, String name, PyObject value) {
        if (value == null) {
            GenericAttributes.delete(code, name);
        } else {
            GenericAttributes.write(code, name, value);
        }
    }
}
