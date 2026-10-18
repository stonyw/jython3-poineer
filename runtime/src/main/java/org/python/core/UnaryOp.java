package org.python.core;

/**
 * The operators that take one operand.
 *
 * Each names a distinct Python slot, so unlike {@link BinaryOp} there is no reflected half to
 * negotiate: a refusal is simply a refusal, and {@link Slots} turns it into a {@code TypeError}.
 * {@code not} is not here — {@code UNARY_NOT} applies {@link Slots#truth} and negates it, because
 * what it asks is {@code __bool__}, not an operator slot.
 *
 * {@code ABSOLUTE} is here although no opcode spells it: {@code abs(x)} is {@code x.__abs__()}, and
 * giving it the same slot shape as the other three means one dispatch and one message format, and
 * only the message differs.
 */
public enum UnaryOp {

    NEGATIVE("-"),
    POSITIVE("+"),
    INVERT("~"),
    ABSOLUTE("abs()");

    private final String symbol;

    UnaryOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
