r"""The calling convention of a second `__init__`, settled before any of it runs.

The required `buffer` arrives positionally because it is the first parameter; one call leaves it out,
one passes seven arguments, one names something that is not a parameter, and one hands the buffer both
ways at once. Each is refused by the parser in the reference's own sentence and none of them moves the
stream — the fields still read as they did, and `repr` still answers.

The last call is the one the convention allows: the buffer by name, the codec by name, and the read
that follows is `latin-1` text.
"""

import sys

report = sys.stderr


def line(label, call):
    try:
        report.write(label + " " + repr(call()) + "\n")
    except BaseException as exc:
        report.write(label + " " + type(exc).__module__ + "." + type(exc).__name__ + " | " + str(exc) + "\n")


buffer = sys.stdin.buffer
line("missing buffer", lambda: sys.stdin.__init__())
line("too many", lambda: sys.stdin.__init__(buffer, "utf-8", "strict", "\n", 0, 0, 1))
line("unknown keyword", lambda: sys.stdin.__init__(buffer, bogus=1))
line("twice", lambda: sys.stdin.__init__(buffer, buffer=buffer))
line("still", lambda: (sys.stdin.encoding, sys.stdin.errors))
line("repr", lambda: repr(sys.stdin))
line("by name", lambda: sys.stdin.__init__(buffer=buffer, encoding="latin-1"))
line("after", lambda: (sys.stdin.encoding, sys.stdin.errors))
line("read", lambda: sys.stdin.read())
