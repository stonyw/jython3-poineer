r"""The names a writing `reconfigure` refuses, and the handler it keeps until a character fails.

A name that is no codec is refused by the registry, and neither that attempt nor the empty name moves the
stream: it is still UTF-8 and still refuses the accent until it is told otherwise. A handler is different.
The reference does not look a handler up when it is stored, so `'nope'` is accepted and the stream says so;
the registry is asked only by the write that actually meets a character the codec cannot carry, and it is
that write which answers `LookupError`.

That write writes nothing at all — the whole text is encoded before any of it goes out — so the `plain`
line before it is all stdout holds until `'replace'` lets the accent through as one `?`.
"""

import sys

report = sys.stderr


def line(label, value):
    report.write(label + " " + repr(value) + "\n")


def show(label, call):
    try:
        line(label, call())
    except BaseException as exc:
        line(label, (type(exc).__module__ + "." + type(exc).__name__, str(exc)))


line("opens as", (sys.stdout.encoding, sys.stdout.errors))
show("unknown", lambda: sys.stdout.reconfigure(encoding="nope"))
show("empty", lambda: sys.stdout.reconfigure(encoding=""))
line("unchanged", (sys.stdout.encoding, sys.stdout.errors))
show("named handler", lambda: sys.stdout.reconfigure(encoding="ascii", errors="nope"))
line("after handler", (sys.stdout.encoding, sys.stdout.errors))
sys.stdout.write("plain\n")
show("refused write", lambda: sys.stdout.write("\u00e9\n"))
show("replace", lambda: sys.stdout.reconfigure(errors="replace"))
show("accent", lambda: sys.stdout.write("\u00e9\n"))
line("end", (sys.stdout.encoding, sys.stdout.errors))
