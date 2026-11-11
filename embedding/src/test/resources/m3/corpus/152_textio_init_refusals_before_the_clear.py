r"""The refusals that come before the fields are cleared, and the one that comes after `ok` already fell.

`encoding` and `newline` are converted by the clinic wrapper, so a value of the wrong type is refused
before the impl runs and changes nothing: the stream is still UTF-8 with its handler, and `repr` still
answers. An `errors` is an ordinary object in that signature, and its conversion is the impl's first
act — after `ok` has been cleared, before any field has. That order is visible: the fields are still the
ones the stream opened with, but every row that checks the state refuses with `I/O operation on
uninitialized object`, while `closed` says True and the rows that reach no farther than the buffer
still answer the buffer's own answers.

`newline='x'` is a value rather than a type, and the check for it also runs before the clear, so its
tear looks like the `errors` one: old fields, a buffer that still answers, and no way back except
another `__init__`. The buffer saved before the first tear is what makes those calls possible, because
`sys.stdin.buffer` itself refuses once there is none.
"""

import sys

report = sys.stderr


def line(label, call):
    try:
        report.write(label + " " + repr(call()) + "\n")
    except BaseException as exc:
        report.write(label + " " + type(exc).__module__ + "." + type(exc).__name__ + " | " + str(exc) + "\n")


buffer = sys.stdin.buffer
line("encoding type", lambda: sys.stdin.__init__(buffer, encoding=1))
line("still", lambda: (sys.stdin.encoding, sys.stdin.errors))
line("repr", lambda: repr(sys.stdin))
line("newline type", lambda: sys.stdin.__init__(buffer, newline=1))
line("still again", lambda: (sys.stdin.encoding, sys.stdin.errors))
line("illegal newline", lambda: sys.stdin.__init__(buffer, newline="x"))
line("torn encoding", lambda: sys.stdin.encoding)
line("torn line_buffering", lambda: sys.stdin.line_buffering)
line("torn repr", lambda: repr(sys.stdin))
line("torn closed", lambda: sys.stdin.closed)
line("torn readable", lambda: sys.stdin.readable())
line("torn name", lambda: sys.stdin.name)
line("torn read", lambda: sys.stdin.read())
line("torn errors", lambda: sys.stdin.errors)
line("recover", lambda: sys.stdin.__init__(buffer, encoding="latin-1"))
line("read", lambda: sys.stdin.read())
line("errors type", lambda: sys.stdin.__init__(buffer, errors=1))
line("torn encoding old", lambda: sys.stdin.encoding)
line("torn repr again", lambda: repr(sys.stdin))
line("recover again", lambda: sys.stdin.__init__(buffer))
line("read again", lambda: sys.stdin.read())
