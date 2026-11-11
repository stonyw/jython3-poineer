r"""`newline=''`: all three endings end a line, and none of them is rewritten.

The difference from `None` is only in what comes back — the endings are recognised the same way — so
what this script watches is the spelling. A '\r' at the end of a chunk is held exactly as in the
translating mode, because the character after it still decides whether it is an ending of one character
or of two, and `newlines` still records what was met.

`read(n)` is where the holding shows: a count is a number of characters, and the held '\r' is not one
until the next chunk says what it belongs to. A whole `read()` goes through the rest of the descriptor
in one piece and comes back with the endings as they were written.
"""

import sys

sys.stdin._CHUNK_SIZE = 2
sys.stdin.reconfigure(newline="")


def show(label, call):
    try:
        print(label, repr(call()))
    except BaseException as exc:
        print(label, type(exc).__module__ + "." + type(exc).__name__, "|", exc)


show("read two", lambda: sys.stdin.read(2))
print("newlines", repr(sys.stdin.newlines))
show("read three", lambda: sys.stdin.read(3))
print("newlines", repr(sys.stdin.newlines))
show("read rest", lambda: sys.stdin.read())
print("newlines", repr(sys.stdin.newlines))
show("read again", lambda: sys.stdin.read())
print("newlines", repr(sys.stdin.newlines))
show("readline eof", lambda: sys.stdin.readline())
