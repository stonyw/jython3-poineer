#!/usr/bin/env python3
"""Generate org/python/core/Opcodes.java from a CPython 3.14 interpreter.

The instruction set is the one thing the two front-end stages share (decision D2 in PLAN.md), so the
numbers live in ``runtime`` — but they are only trustworthy if they are read off an interpreter
rather than transcribed. Hence a generator, and hence ``--check``, which the build runs.

    python3.14 tools/gen_opcodes.py            # print the file
    python3.14 tools/gen_opcodes.py --check F  # exit 1 if F is stale
"""

import sys

MAJOR_MINOR = (3, 14)

HEADER = """package org.python.core;

/**
 * CPython 3.14 opcode numbers: the instruction set this VM executes.
 *
 * Generated, not transcribed —
 *
 * <pre>
 *   runtime/tools/gen_opcodes.py &gt; this file
 *   runtime/tools/gen_opcodes.py --check this file
 * </pre>
 *
 * The constants cover every number a {@code .pyc} can carry, which is the <em>unspecialized</em>
 * set: an adapted opcode ({@code BINARY_OP_ADD_INT} and its relations) exists only inside a running
 * interpreter's private copy of a code array and never in a stream {@code marshal} writes. One
 * reaching {@link Interpreter}'s dispatch loop is therefore a reader bug, and is reported as one
 * rather than executed as whatever base opcode its number resembles.
 */
public final class Opcodes {
"""

PER_OPCODE = "    public static final int %-32s = %3d;\n"
FOOTER = """
    /** Opcode number to name, dense, so an unsupported opcode can be named in a message. */
    private static final String[] NAMES = {
%s
    };

    /** {@code MAX_OPCODE + 1} in the interpreter this table came from. */
    public static final int COUNT = %d;

    private Opcodes() {
    }

    /** The name of an opcode number, or {@code <n>} for a number that has none. */
    public static String name(int opcode) {
        return opcode >= 0 && opcode < NAMES.length && NAMES[opcode] != null
                ? NAMES[opcode]
                : "<" + opcode + ">";
    }

    /** True for a number only an adapting interpreter produces — never valid in stored code. */
    public static boolean specialised(int opcode) {
        return opcode < 0 || opcode >= NAMES.length || NAMES[opcode] == null || !IN_BASE[opcode];
    }

    /**
     * Whether an opcode's argument is a relative jump.
     *
     * This is the interpreter's business, not a front-end's: the direction of a jump is part of the
     * instruction set the two stages share. How far apart the jumps sit in the stored code — the
     * cache units between them — is not, and stays with whoever packed the array.
     */
    public static boolean jumpsRelative(int opcode) {
        return opcode >= 0 && opcode < JUMPS.length && JUMPS[opcode];
    }

    /** Whether a relative jump's argument counts backwards from the instruction after the jump. */
    public static boolean jumpsBackward(int opcode) {
        return opcode >= 0 && opcode < BACKWARD.length && BACKWARD[opcode];
    }
}
"""


def emit() -> str:
    import dis
    import opcode

    if sys.version_info[:2] != MAJOR_MINOR:
        raise SystemExit(
            f"this table belongs to CPython {MAJOR_MINOR[0]}.{MAJOR_MINOR[1]}, "
            f"not {sys.version_info[0]}.{sys.version_info[1]}"
        )

    out = [HEADER, "    // Numbers, in opcode order.\n"]
    for name, number in sorted(opcode.opmap.items(), key=lambda kv: (kv[1], kv[0])):
        out.append(PER_OPCODE % (name, number))

    names = ["null" if n.startswith("<") else '"%s"' % n for n in dis._all_opname]
    base = set(opcode.opmap)
    dense = "\n".join(
        "        " + ", ".join(names[i : i + 8]) + ("," if i + 8 < len(names) else "")
        for i in range(0, len(names), 8)
    )
    flags = "\n".join(
        "        "
        + ", ".join("true" if dis._all_opname[i] in base else "false" for i in range(s, min(s + 20, len(names))))
        + ("," if s + 20 < len(names) else "")
        for s in range(0, len(names), 20)
    )
    out.append("\n    private static final boolean[] IN_BASE = {\n" + flags + "\n    };\n")
    out.append(bits("JUMPS", [i in dis.hasjrel for i in range(len(names))]))
    out.append(
        bits(
            "BACKWARD",
            [
                i in dis.hasjrel and "BACKWARD" in (dis._all_opname[i] or "")
                for i in range(len(names))
            ],
        )
    )
    out.append(FOOTER % (dense, len(names)))
    return "".join(out) + "\n"


def bits(name: str, values) -> str:
    rows = "\n".join(
        "        "
        + ", ".join("true" if values[i] else "false" for i in range(s, min(s + 20, len(values))))
        + ("," if s + 20 < len(values) else "")
        for s in range(0, len(values), 20)
    )
    return f"\n    private static final boolean[] {name} = {{\n{rows}\n    }};\n"


def main() -> int:
    text = emit()
    if len(sys.argv) > 2 and sys.argv[1] == "--check":
        with open(sys.argv[2], encoding="utf-8") as handle:
            if handle.read() != text:
                print(
                    f"{sys.argv[2]} is stale; regenerate it with tools/gen_opcodes.py",
                    file=sys.stderr,
                )
                return 1
        return 0
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
