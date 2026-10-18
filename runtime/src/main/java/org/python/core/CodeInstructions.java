package org.python.core;

/**
 * The logical instruction stream of a {@link PyCode}.
 *
 * A front-end may pack instructions however it likes — CPython 3.14 uses two-byte units with
 * variable numbers of unused cache units following some opcodes, and prefixes large arguments with
 * EXTENDED_ARG units. Everything about that packing stays behind this interface: instructions are
 * addressed by their own index, and a unit offset is only ever a *key* for mapping a jump argument
 * back to an instruction, never something the interpreter decodes.
 */
public interface CodeInstructions {

    int length();

    int opcodeAt(int index);

    /** The argument with any EXTENDED_ARG prefix already folded in. */
    int argAt(int index);

    /** Where this instruction sits in the front-end's own code array, for jump-argument mapping. */
    int unitOffsetAt(int index);

    /** The instruction starting at {@code unit}, or -1 if none does. */
    int indexOfUnitAt(int unit);
}
