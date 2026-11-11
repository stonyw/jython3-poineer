r"""`newline='\r\n'`: the pair is the only ending, and a '\r' without a '\n' is text.

This is the mode the line scan cannot decide on its own: a chunk that ends on '\r' may be the first
half of an ending or an ordinary character, and only the next character says which. The scan therefore
refuses to take a closing '\r' it cannot pair, which is the other way of arriving at the same holding
the two universal modes do in translation. The '\r\r\n' in the pipe is the pair that proves it: the
first '\r' is text, the second belongs to the ending, and the line that ends there carries all three
characters.

`newlines` stays None in this mode: the reference only fills it in for the universal modes, whatever the
chunks carried. The pipe also ends on a lone '\r', which stays text at the end of the descriptor.
"""

import sys

sys.stdin._CHUNK_SIZE = 3
sys.stdin.reconfigure(newline="\r\n")


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
