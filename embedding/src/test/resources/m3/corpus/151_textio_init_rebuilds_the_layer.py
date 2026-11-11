r"""A second `__init__` replaces the layer rather than being a no-op.

`TextIOWrapper.__init__` is the reference's constructor body run again: it clears the decoder, the
encoder and every count, stores the buffer it was handed, asks that buffer which directions it goes,
and rebuilds the codec from the `encoding` and `errors` it was given. The stream here is the same
object throughout — `sys.stdin.__init__(sys.stdin.buffer, ...)` hands it the layer it already holds —
so what changes is the layer above the octets, not the octets.

Three things show the replacement. The codec is `latin-1` afterwards, so the `0xe9` the UTF-8 stream
would have refused is one character; the flags take the truth of whatever was passed, not the integers
a stricter signature might demand; and `_CHUNK_SIZE`, set to three before the call, is the reference's
default again after it, because the constructor clears the field before anything else fills it.

The last call passes only the buffer. Everything else goes back to its default — UTF-8, `'strict'`,
both flags false — which is what makes the second read, at the end of the pipe, answer nothing rather
than whatever the previous layer had carried.
"""

import sys

report = sys.stderr


def line(label, call):
    try:
        report.write(label + " " + repr(call()) + "\n")
    except BaseException as exc:
        report.write(label + " " + type(exc).__module__ + "." + type(exc).__name__ + " | " + str(exc) + "\n")


buffer = sys.stdin.buffer
sys.stdin._CHUNK_SIZE = 3
line("before", lambda: (sys.stdin.encoding, sys.stdin.errors, sys.stdin.line_buffering, sys.stdin.write_through))
line("chunk before", lambda: sys.stdin._CHUNK_SIZE)
line("init", lambda: sys.stdin.__init__(buffer, encoding="latin-1", errors="replace", newline="\r\n",
                                        line_buffering=1, write_through="x"))
line("after", lambda: (sys.stdin.encoding, sys.stdin.errors, sys.stdin.line_buffering, sys.stdin.write_through))
line("chunk after", lambda: sys.stdin._CHUNK_SIZE)
line("buffer is", lambda: sys.stdin.buffer is buffer)
line("name", lambda: sys.stdin.name)
line("directions", lambda: (sys.stdin.readable(), sys.stdin.writable(), sys.stdin.seekable()))
line("closed", lambda: sys.stdin.closed)
line("mode", lambda: sys.stdin.mode)
line("read", lambda: sys.stdin.read())
line("newlines", lambda: sys.stdin.newlines)
line("defaults", lambda: sys.stdin.__init__(buffer))
line("default fields", lambda: (sys.stdin.encoding, sys.stdin.errors, sys.stdin.line_buffering,
                                sys.stdin.write_through))
line("read again", lambda: sys.stdin.read())
