r"""Universal translation, the endings it remembers, and the lock the first read puts on the fields.

`sys.stdin` is opened with `newline='\n'`, which is the mode that translates nothing. Reconfiguring it
to `newline=None` is the only way to move it, and the move has to happen before the descriptor is read:
`has_read1` is set by the first chunk that brings octets back, so `read(0)` leaves the stream open to
reconfiguration and everything after the first real line does not. The lock covers all three decoder
fields rather than the two the sentence names — an `errors='strict'` handed over after a read is refused
in the same words — and it is asked before the newline value is checked, so even an illegal one gets the
read sentence rather than the value sentence. A bare call, or one that only moves the two flags, is
still allowed.

The pipe is read in three-octet chunks so that a '\r' lands at the end of one and its '\n' at the start
of the next: the translation holds the '\r' back until the character after it decides the ending, which
is why the two are never seen apart. `newlines` is filled in by the same pass and reported in the
reference's fixed order — CR, LF, CRLF — rather than in the order the endings arrived, and the closing
'\r' of the pipe is an ending of its own because there is no character after it.
"""

import sys

sys.stdin._CHUNK_SIZE = 3
sys.stdin.reconfigure(newline=None)


def show(label, call):
    try:
        print(label, repr(call()))
    except BaseException as exc:
        print(label, type(exc).__module__ + "." + type(exc).__name__, "|", exc)


print("newlines before", repr(sys.stdin.newlines))
show("read zero", lambda: sys.stdin.read(0))
show("reconfigure none", lambda: sys.stdin.reconfigure(newline=None))
print("read zero again", repr(sys.stdin.read(0)))

while True:
    line = sys.stdin.readline()
    if not line:
        break
    print("line", repr(line), repr(sys.stdin.newlines))
print("newlines", repr(sys.stdin.newlines))

show("encoding same", lambda: sys.stdin.reconfigure(encoding="utf-8"))
show("encoding none", lambda: sys.stdin.reconfigure(encoding=None))
show("errors none", lambda: sys.stdin.reconfigure(errors=None))
show("errors strict", lambda: sys.stdin.reconfigure(errors="strict"))
show("newline none", lambda: sys.stdin.reconfigure(newline=None))
show("newline bad", lambda: sys.stdin.reconfigure(newline="x"))
show("flags", lambda: sys.stdin.reconfigure(line_buffering=True, write_through=True))
show("bare", lambda: sys.stdin.reconfigure())
show("read rest", lambda: sys.stdin.read())
show("iteration", lambda: list(sys.stdin))

# Writing never reads, so the lock is still off on the stream that only writes.
sys.stdout.write("")
sys.stdout.flush()
show("stdout encoding", lambda: sys.stdout.reconfigure(encoding="utf-8"))
show("stdout newline", lambda: sys.stdout.reconfigure(newline=None))
show("stdout errors", lambda: sys.stdout.reconfigure(errors="strict"))
print("stdout newlines", repr(sys.stdout.newlines))
