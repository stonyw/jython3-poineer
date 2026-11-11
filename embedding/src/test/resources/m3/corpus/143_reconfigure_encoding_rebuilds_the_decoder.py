r"""A new `encoding` rebuilds the decoder, and the handler it arrives with is `'strict'`.

The stream is opened as UTF-8 and moved to `latin-1` before the first octet is read, so the `0xe9` at the
end of the pipe is a character rather than an error. The name is stored as the program spelled it and the
decoder under it follows the name, which is the half of the change this script watches: `read(1)` answers
`'c'`, and the rest of the line answers `'afé\n'` — one octet per character, which is not what UTF-8 would
have said about the same run.

An `encoding` given on its own resets the handler to `'strict'`; the reference does the same, and this
script prints the pair before the read so both facts are on the record. After the first read the three
decoder fields are locked, and the two flags are still free.
"""

import sys

sys.stdin._CHUNK_SIZE = 2
sys.stdin.reconfigure(encoding="latin-1")
print("encoding", repr(sys.stdin.encoding), repr(sys.stdin.errors))


def show(label, call):
    try:
        print(label, repr(call()))
    except BaseException as exc:
        print(label, type(exc).__module__ + "." + type(exc).__name__, "|", exc)


show("read one", lambda: sys.stdin.read(1))
show("encoding after read", lambda: sys.stdin.reconfigure(encoding="utf-8"))
show("errors after read", lambda: sys.stdin.reconfigure(errors="replace"))
show("newline after read", lambda: sys.stdin.reconfigure(newline=None))
show("flags after read", lambda: sys.stdin.reconfigure(line_buffering=True))
show("read rest", lambda: sys.stdin.read())
print("kept", repr(sys.stdin.encoding), repr(sys.stdin.errors))
