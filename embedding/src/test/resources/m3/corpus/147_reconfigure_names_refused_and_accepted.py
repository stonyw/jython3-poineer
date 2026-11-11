r"""The names `reconfigure` refuses, the ones it accepts, and the handler it never looks up.

`nope` and `''` are not codecs, and the registry's sentence is the answer; neither attempt moves the fields,
so the stream is still UTF-8 and still refuses `0x80` until it is told otherwise. An `errors` on its own
keeps the encoding, and a handler that names nothing is accepted rather than looked up — the reference only
consults the registry when a character actually fails, so `'nope'` is stored and never mentioned.

`encoding='locale'` is the one name not stored as written; on this machine the locale's codec is UTF-8, so
the stream says so and the handler is back to `'strict'`. The `ascii` handed over last is what the read
finally trips on, and the refusal is the one `bytes` would give for the same octets.
"""

import sys


def show(label, call):
    try:
        print(label, repr(call()))
    except BaseException as exc:
        print(label, type(exc).__module__ + "." + type(exc).__name__, "|", exc)


show("unknown", lambda: sys.stdin.reconfigure(encoding="nope"))
show("empty", lambda: sys.stdin.reconfigure(encoding=""))
print("unchanged", repr(sys.stdin.encoding), repr(sys.stdin.errors))
show("errors replace", lambda: sys.stdin.reconfigure(errors="replace"))
print("after errors", repr(sys.stdin.encoding), repr(sys.stdin.errors))
show("unknown handler", lambda: sys.stdin.reconfigure(errors="nope"))
print("after handler", repr(sys.stdin.errors))
show("locale", lambda: sys.stdin.reconfigure(encoding="locale"))
print("after locale", repr(sys.stdin.encoding), repr(sys.stdin.errors))
show("ascii", lambda: sys.stdin.reconfigure(encoding="ascii"))
print("after ascii", repr(sys.stdin.encoding), repr(sys.stdin.errors))
show("read", lambda: sys.stdin.read())
