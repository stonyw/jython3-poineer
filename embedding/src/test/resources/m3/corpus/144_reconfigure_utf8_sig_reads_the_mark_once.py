r"""`utf-8-sig` drops the mark to the stream's first octets, and to them only.

The pipe opens with the three octets of the mark and carries another one between its two letters, at a
two-octet chunk size so that the opening mark is itself cut in two: the decoder holds both halves until the
third arrives, and only then decides that they were a mark. The second mark is at the start of the text
rather than of the stream, so it is a character — `'\ufeff'` — and the read answers it as one.

Giving the encoding also resets the handler, which the opening print puts next to the name.
"""

import sys

sys.stdin._CHUNK_SIZE = 2
sys.stdin.reconfigure(encoding="utf-8-sig")
print("encoding", repr(sys.stdin.encoding), repr(sys.stdin.errors))
print("first", repr(sys.stdin.read(1)))
print("rest", repr(sys.stdin.read()))
