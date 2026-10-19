package org.python.core;

/**
 * The operators {@code BINARY_OP} selects.
 *
 * {@code BINARY_OP}'s argument is neither a slot index nor a contiguous enumeration: it is CPython's
 * internal op number, which lists the thirteen pure operators in an order that looks arbitrary
 * ({@code //} is 2, {@code -} is 10), then the same thirteen augmented, then {@code []}. This table
 * is that numbering, measured from {@code dis} rather than remembered.
 *
 * The augmented form names a different Python method ({@code __iadd__}) and, for a target that can
 * change in place, a different result: {@code a += b} extends a list where {@code a = a + b}
 * replaces it. That distinction belongs to the argument, not to the operator, so it survives as
 * {@link #augmented(int)}.
 *
 * Subscription is in the same argument space but is not an operator: it asks {@code __getitem__},
 * which has no reflected or in-place form, so it stays a number and not an enum constant.
 */
public enum BinaryOp {

    ADD("+"),
    AND("&"),
    FLOORDIV("//"),
    LSHIFT("<<"),
    MATMUL("@"),
    MUL("*"),
    MOD("%"),
    OR("|"),
    POW("**"),
    RSHIFT(">>"),
    SUB("-"),
    TRUEDIV("/"),
    XOR("^");

    /** The number of pure operators, which is where the augmented block starts. */
    private static final int PURE = values().length;

    /** {@code BINARY_OP}'s argument for {@code x[y]}, past the augmented block. */
    public static final int SUBSCR_ARG = 2 * PURE;

    /** CPython's numbering: the pure operators, then the same thirteen augmented. */
    private static final BinaryOp[] BY_ARG = {
        ADD, AND, FLOORDIV, LSHIFT, MATMUL, MUL, MOD, OR, POW, RSHIFT, SUB, TRUEDIV, XOR,
        ADD, AND, FLOORDIV, LSHIFT, MATMUL, MUL, MOD, OR, POW, RSHIFT, SUB, TRUEDIV, XOR,
    };

    /**
     * @param arg a {@code BINARY_OP} argument
     * @return the operator it selects, or null for a number that is not one — including
     *     {@link #SUBSCR_ARG}, which the caller handles as a subscript
     */
    public static BinaryOp of(int arg) {
        return arg >= 0 && arg < BY_ARG.length ? BY_ARG[arg] : null;
    }

    /** Whether {@code arg} falls in the augmented half of {@code BINARY_OP}'s range. */
    public static boolean augmented(int arg) {
        return arg >= PURE && arg < SUBSCR_ARG;
    }

    private final String symbol;

    BinaryOp(String symbol) {
        this.symbol = symbol;
    }

    /** The operator as Python spells it, which is what a {@code TypeError} quotes. */
    public String symbol() {
        return symbol;
    }

    /**
     * The method this operator asks of the left operand: {@code __add__} for {@link #ADD}.
     *
     * <p>The enum constants are CPython's slot names already, so the spelling is the name — which holds
     * for all thirteen, including the two that look like they would not: {@code //} is {@code __floordiv__}
     * and {@code @} is {@code __matmul__}.
     */
    public String dunder() {
        return "__" + name().toLowerCase() + "__";
    }

    /** {@code __radd__}: the method the right operand is asked for after a refusal. */
    public String reflectedDunder() {
        return "__r" + name().toLowerCase() + "__";
    }

    /** {@code __iadd__}: the method an augmented assignment tries before either of the other two. */
    public String inPlaceDunder() {
        return "__i" + name().toLowerCase() + "__";
    }

    /** {@code +=} for {@link #ADD}: the spelling an augmented statement reports when it fails. */
    public String augmentedSymbol() {
        return symbol + "=";
    }
}
