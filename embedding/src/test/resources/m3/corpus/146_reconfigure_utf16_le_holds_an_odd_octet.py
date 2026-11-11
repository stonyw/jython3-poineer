r"""`utf-16-le` has no mark to read and no order to decide, but it still cuts at units.

The pipe is ten octets read three at a time, so a chunk ends inside the astral character twice: once on
the high surrogate's first octet alone and once on the byte after the pair. Both halves wait for the chunk
that finishes them, which is why `read(2)` answers `'hi'` out of five octets and the rest of the line
answers the one astral character and its ending.
"""

import sys

sys.stdin._CHUNK_SIZE = 3
sys.stdin.reconfigure(encoding="utf-16-le")
print("encoding", repr(sys.stdin.encoding), repr(sys.stdin.errors))
print("two", repr(sys.stdin.read(2)))
print("rest", repr(sys.stdin.read()))
