package org.python.core;

/**
 * The names a code object answers from what the {@link PyCode} interface already carries.
 *
 * <p>This is the runtime half of {@code code}'s attribute surface; the front-end keeps the other half,
 * because {@code co_code}, {@code co_linetable} and {@code co_exceptiontable} are the bytes one specific
 * format writes and only the reader of those bytes can hand them out. Everything here is a number, a name
 * or a tuple derived from the localsplus array, so it is the same for a code object read from a {@code
 * .pyc} and one a native parser builds.
 *
 * <p>CPython's own split of the names is kept visible, because it is what a program feels: the fourteen
 * stored fields are {@code PyMemberDef}s marked read-only and answer a write with the generic complaint
 * about a read-only attribute, while the five derived ones are getsets with no setter and name the
 * attribute and the type. The difference is only in wording — none of them can be written — but a script
 * that prints the exception text of each refusal sees it.
 *
 * <p>What is not here is what CPython's {@code code} also publishes and this runtime cannot answer yet:
 * {@code co_lines()}, {@code co_positions()}, {@code replace()} and {@code co_branches()}, the
 * {@code co_lnotab} compatibility table, and the two private names {@code dis} reads. They are refused as
 * missing, and slice 9 of the plan is where the position iterators are worth adding, since an
 * {@code AttributeError} traceback needs them to point at a column.
 */
public final class CodeMembers {

    private CodeMembers() {
    }

    /**
     * {@code co.name} for the names derived from the interface.
     *
     * @return the value, or {@link PyNotImplemented} for a name this half does not own — including the
     *     three byte tables, which the front-end answers, and a name no code object has
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
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /**
     * {@code co.name = value} and {@code del co.name}, which is the same refusal either way.
     *
     * <p>The three wordings come from CPython's own tables, and they are kept separate even for the names
     * this runtime does not read yet — {@code co_lnotab} and the position iterators cannot be asked for
     * here, but a write to one is refused with the sentence CPython uses, because it is the type's table
     * that decides the wording and not whether this runtime filled it in.
     *
     * @throws PyRaise always, with the wording CPython uses for that name's kind
     */
    public static void refuse(String name) {
        if (member(name)) {
            throw Slots.attributeError("readonly attribute");
        }
        if (derived(name)) {
            throw Slots.attributeError(
                    "attribute '" + name + "' of 'code' objects is not writable");
        }
        if (method(name)) {
            throw Slots.attributeError(
                    "'code' object attribute '" + name + "' is read-only");
        }
        throw Slots.attributeError(
                "'code' object has no attribute '"
                        + name
                        + "' and no __dict__ for setting new attributes");
    }

    /** The fourteen fields {@code code} stores, which it publishes as {@code PyMemberDef}s. */
    private static boolean member(String name) {
        return switch (name) {
            case "co_argcount", "co_posonlyargcount", "co_kwonlyargcount", "co_stacksize", "co_flags",
                    "co_firstlineno", "co_filename", "co_name", "co_qualname", "co_consts", "co_names",
                    "co_nlocals", "co_linetable", "co_exceptiontable" ->
                    true;
            default ->
                    false;
        };
    }

    /** The names {@code code} computes on request, published as getsets with no setter. */
    private static boolean derived(String name) {
        return switch (name) {
            case "co_code", "co_varnames", "co_cellvars", "co_freevars", "co_lnotab",
                    "_co_code_adaptive" ->
                    true;
            default -> false;
        };
    }

    /**
     * The methods {@code code} publishes, which a write reaches through the type and so cannot replace.
     *
     * <p>{@code co_lines}, {@code co_positions}, {@code co_branches} and {@code replace} are CPython's;
     * {@code _varname_from_oparg} is {@code dis}'s. Only the first pair matters to the report at slice 9,
     * where an {@code AttributeError} needs a column to point at.
     */
    private static boolean method(String name) {
        return switch (name) {
            case "co_lines", "co_positions", "co_branches", "replace", "_varname_from_oparg" -> true;
            default -> false;
        };
    }
}
