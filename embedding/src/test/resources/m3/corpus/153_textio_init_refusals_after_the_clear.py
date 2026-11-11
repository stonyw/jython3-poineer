r"""The refusals that come after the buffer and the codec were cleared, and what they leave behind.

An unknown name is looked up before the buffer is stored, so the stream is left with no encoding and
no buffer at all: `encoding` answers None, and `buffer`, `readable`, `name` and `repr` refuse with
`I/O operation on uninitialized object` rather than answering the layer the stream used to hold. That
is why the buffer is saved before the call — reading `sys.stdin.buffer` afterwards is itself the
refusal.

A buffer that is not an object gets further: it is stored and the stream learns its encoding before
the first question reaches it, so the tear has an encoding and a `buffer` that is the value handed
over, and the questions aimed at that buffer answer its own `AttributeError`. Only a second `__init__`
with a buffer that can answer puts the stream back on its feet, and the read that follows is the text
the saved buffer still held.
"""

import sys

report = sys.stderr


def line(label, call):
    try:
        report.write(label + " " + repr(call()) + "\n")
    except BaseException as exc:
        report.write(label + " " + type(exc).__module__ + "." + type(exc).__name__ + " | " + str(exc) + "\n")


buffer = sys.stdin.buffer
line("unknown", lambda: sys.stdin.__init__(buffer, encoding="nope"))
line("torn encoding", lambda: sys.stdin.encoding)
line("torn closed", lambda: sys.stdin.closed)
line("torn mode", lambda: sys.stdin.mode)
line("torn repr", lambda: repr(sys.stdin))
line("torn buffer", lambda: sys.stdin.buffer)
line("torn readable", lambda: sys.stdin.readable())
line("torn name", lambda: sys.stdin.name)
line("torn newlines", lambda: sys.stdin.newlines)
line("recover", lambda: sys.stdin.__init__(buffer, encoding="latin-1"))
line("read", lambda: sys.stdin.read())
line("not a buffer", lambda: sys.stdin.__init__(1))
line("torn encoding again", lambda: sys.stdin.encoding)
line("torn buffer again", lambda: sys.stdin.buffer)
line("torn closed again", lambda: sys.stdin.closed)
line("torn readable again", lambda: sys.stdin.readable())
line("torn write", lambda: sys.stdin.write("x"))
line("torn read again", lambda: sys.stdin.read())
line("recover again", lambda: sys.stdin.__init__(buffer, encoding="latin-1"))
line("read again", lambda: sys.stdin.read())
