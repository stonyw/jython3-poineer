r"""`utf-16` takes its byte order from the mark, and a surrogate pair may be cut by the chunk.

The pipe is little-endian with the mark in front of it, read two octets at a time so that the high half of
the astral character is the whole of one chunk. The pair is not a character until its second unit arrives,
so the decoder holds the high surrogate for the chunk after it — the read answers one character of two code
points, not a broken pair — and the mark is gone from the first answer.

The stream says `'utf-16'` while the decoder underneath reads the order it settled on.
"""

import sys

sys.stdin._CHUNK_SIZE = 2
sys.stdin.reconfigure(encoding="utf-16")
print("encoding", repr(sys.stdin.encoding), repr(sys.stdin.errors))
print("first", repr(sys.stdin.read(1)))
print("rest", repr(sys.stdin.read()))
