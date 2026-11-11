r"""A new `encoding` is what the next write encodes with, and a mark goes out with the encoder that owns it.

`utf-8-sig` is not one of the codecs the reference answers with a fast function: its incremental encoder
carries the mark itself, and writes it with the first text it is handed. The empty string counts — the
write here puts the mark on stdout before any character has — and so does every `reconfigure` that builds
a new encoder, which is why the mark appears again after `latin-1` has had its turn. Asking for the same
encoding twice is two encoders, the first of which wrote nothing.

`latin-1` is the codec the octets show: `'café'` is five octets with `0xe9` for the accent, where UTF-8
would have written two. Every label is written to `stderr`, which is another stream and is never
reconfigured, so what this script's stdout carries is only the text the switched codecs encoded.
"""

import sys

report = sys.stderr


def line(label, value):
    report.write(label + " " + repr(value) + "\n")


line("opens as", sys.stdout.encoding)
sys.stdout.reconfigure(encoding="utf-8-sig")
line("after sig", (sys.stdout.encoding, sys.stdout.errors))
line("empty write", sys.stdout.write(""))
sys.stdout.reconfigure(encoding="latin-1")
line("after latin", (sys.stdout.encoding, sys.stdout.errors))
line("latin count", sys.stdout.write("caf\u00e9\n"))
sys.stdout.reconfigure(encoding="utf-8-sig")
line("back to sig", (sys.stdout.encoding, sys.stdout.errors))
sys.stdout.write("b\n")
line("still sig", (sys.stdout.encoding, sys.stdout.errors))
