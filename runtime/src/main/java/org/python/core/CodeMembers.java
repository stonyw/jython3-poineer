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
 * {@code replace()}, which would have to rebuild the table from eighteen keyword-only fields; the two private
 * names {@code dis} reads; and the live specialization state {@code _co_code_adaptive} shows, which this
 * runtime never writes and so answers as the plain instruction array. They are refused as missing or answered
 * as the same bytes. The two position reads — {@code co_lines()} and {@code co_positions()} — are here, and
 * are what a Stage-B report would draw a caret from: the report this slice leaves behind still names a line
 * and nothing beside it.
 */
public final class CodeMembers {

    /**
     * The two methods {@code code} declares.
     *
     * <p>A member table rather than two more cases in {@link #getAttr}, because the table is what decides
     * the shape of the answer: CPython reads {@code co.co_lines} out of the type's method table and binds
     * it, so an argument is complained about with the type's name in front — {@code code.co_lines() takes
     * no arguments (1 given)} — while {@code code.co_lines} without a code object is the unbound
     * descriptor that takes the receiver as its first argument and refuses one of another type. Both
     * shapes come from {@link Members} and from nothing else here.
     */
    private static final Members METHODS = Members.of(PyType.CODE)
            .addNoArgument(
                    "co_lines",
                    self -> PyIterator.of(PyType.LINE_ITERATOR, ((PyCode) self).lineRanges().elements()))
            .addNoArgument(
                    "co_positions",
                    self ->
                            PyIterator.of(
                                    PyType.POSITIONS_ITERATOR, ((PyCode) self).positions().elements()));

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
     * {@code co.name = value} and {@code del co.name}, which is the same refusal either way.
     *
     * <p>The two wordings that remain are CPython's own: a name the type files as a method — and the two
     * this runtime has not filed yet — is refused because a {@code method_descriptor} has no writing half,
     * while the twenty storage names are refused by their own rows, since {@link GenericAttributes} finds a
     * member or a getset on the class before it looks for a dictionary and runs what it finds. A name nobody
     * files is left to that same walk, which reads the type's own dictionary and so calls {@code __doc__}
     * read-only, reaches {@code object}'s getset for {@code __class__}, and says there is no dictionary for a
     * name nothing files.
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
     * <p>{@code co_lines} and {@code co_positions} are filed as rows and answer their own refusal, so they
     * are not named here; the five left are the ones this runtime has no body for yet — {@code co_branches},
     * {@code replace}, {@code __replace__}, {@code __sizeof__} and {@code _varname_from_oparg}, which
     * CPython's {@code code_methods} table files as {@code PyMethodDef} rows and which a program therefore
     * meets with the sentence for a read-only attribute rather than with a missing one.
     */
    private static boolean method(String name) {
        return switch (name) {
            case "co_branches", "replace", "__replace__", "__sizeof__", "_varname_from_oparg" -> true;
            default -> false;
        };
    }
}
