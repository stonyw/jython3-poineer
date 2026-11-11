r"""`utf-16` writes the native-order units and no mark, because the mark needs a stream that can seek.

The reference arms the window its mark depends on in the stream's constructor, and only for a descriptor
whose position it can ask about; stdout here is a pipe, so the window never opens and `utf-16` writes the
native-order units alone — little-endian on this machine, the same octets `utf-16-le` names outright. The
`utf-16-be` that follows shows the other order. Then the `ascii` codec is one octet per character, so the
accent has to be escaped, and which escape it gets is the handler's answer: `'backslashreplace'` writes
`\\xe9`, `'replace'` writes one `?`.

The last part moves the handler back to `'strict'` by naming an encoding and no handler, and the write that
trips over the accent is refused whole — the octets before the bad character never go out, because the
reference encodes the text before it writes any of it.
"""

import sys

report = sys.stderr


def line(label, value):
    report.write(label + " " + repr(value) + "\n")


sys.stdout.reconfigure(encoding="utf-16")
line("utf-16", (sys.stdout.encoding, sys.stdout.errors))
sys.stdout.write("ok\n")
sys.stdout.reconfigure(encoding="utf-16-le")
line("utf-16-le", (sys.stdout.encoding, sys.stdout.errors))
sys.stdout.write("hi\n")
sys.stdout.reconfigure(encoding="utf-16-be")
line("utf-16-be", (sys.stdout.encoding, sys.stdout.errors))
sys.stdout.write("A\n")
sys.stdout.reconfigure(encoding="ascii", errors="backslashreplace")
line("ascii", (sys.stdout.encoding, sys.stdout.errors))
sys.stdout.write("\u00e9\n")
sys.stdout.reconfigure(errors="replace")
line("replace", (sys.stdout.encoding, sys.stdout.errors))
sys.stdout.write("\u00e9\n")
try:
    sys.stdout.reconfigure(encoding="ascii")
    line("strict", sys.stdout.write("a\u00e9\n"))
except UnicodeEncodeError as exc:
    line("refused", (type(exc).__name__, str(exc)))
sys.stdout.write("a\n")
line("end", (sys.stdout.encoding, sys.stdout.errors))
