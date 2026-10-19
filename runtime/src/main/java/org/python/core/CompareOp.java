package org.python.core;

/**
 * The comparisons {@code COMPARE_OP} selects, and how its argument is packed.
 *
 * The argument is a bit field, not an index: {@code arg >> 5} is CPython's {@code Py_LT..Py_GE}
 * slot, and bit 4 asks for the result to be replaced by its own truth value, which is what an
 * {@code if a < b} emits ({@code 18} is {@code <} plus that bit) where a bare expression emits
 * {@code 2}. The remaining low bits select the wording of a failed comparison's message and are
 * ignored here.
 */
public enum CompareOp {

    LT("<"),
    LE("<="),
    EQ("=="),
    NE("!="),
    GT(">"),
    GE(">=");

    /** The slot index CPython uses, in {@code Py_LT, Py_LE, Py_EQ, Py_NE, Py_GT, Py_GE} order. */
    private static final int SLOT_SHIFT = 5;

    private static final int BOOL_RESULT = 16;

    /**
     * @param arg a {@code COMPARE_OP} argument
     * @return the comparison it selects, or null if the packed slot number is out of range
     */
    public static CompareOp of(int arg) {
        int slot = arg >> SLOT_SHIFT;
        return slot >= 0 && slot < values().length ? values()[slot] : null;
    }

    /** Whether the emitted code already wants a {@code bool} rather than whatever the slots return. */
    public static boolean wrapsInBool(int arg) {
        return (arg & BOOL_RESULT) != 0;
    }

    /** The comparison the *other* operand's slots would implement, for the reflected protocol. */
    public CompareOp reflected() {
        return switch (this) {
            case LT -> GT;
            case LE -> GE;
            case GT -> LT;
            case GE -> LE;
            case EQ, NE -> this;
        };
    }

    private final String symbol;

    CompareOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** The method this comparison asks of an operand: {@code __lt__} for {@link #LT}. */
    public String dunder() {
        return "__" + name().toLowerCase() + "__";
    }
}
