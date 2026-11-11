r"""`newline='\r'`: a lone '\r' ends a line and the '\n' of a '\r\n' starts the next one.

The single-character modes need no holding and no scanning for a pair: one character is the whole
ending, and what follows it is the next line's first character. That is what makes `'b\r'` and
`'\nc\n'` two lines out of `'b\r\nc\n'` — the '\r' is consumed as the terminator and the '\n' is not.
"""

import sys

sys.stdin._CHUNK_SIZE = 2
sys.stdin.reconfigure(newline="\r")


def show(label, call):
    try:
        print(label, repr(call()))
    except BaseException as exc:
        print(label, type(exc).__module__ + "." + type(exc).__name__, "|", exc)


while True:
    line = sys.stdin.readline()
    if not line:
        break
    print("line", repr(line), repr(sys.stdin.newlines))
print("newlines", repr(sys.stdin.newlines))
show("read rest", lambda: sys.stdin.read())
