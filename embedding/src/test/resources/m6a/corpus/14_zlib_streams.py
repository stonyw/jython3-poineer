"""`zlib`: the compression calls, byte for byte, and the incremental bookkeeping around them.

The streams this prints are compared with the reference interpreter's own, so every line is a
compressed byte string or a decoded value rather than a statement that something round-trips.
Window bits below 15 are the one thing the JVM cannot express -- `Deflater` always uses the 15-bit
window -- so the corpora stay at 15 and in the raw and gzip containers, where the JVM and zlib agree
exactly.
"""
import zlib

text = b"the quick brown fox jumps over the lazy dog " * 40

print("constants", zlib.DEFLATED, zlib.DEF_MEM_LEVEL, zlib.MAX_WBITS, zlib.DEF_BUF_SIZE)
print("levels", zlib.Z_NO_COMPRESSION, zlib.Z_BEST_SPEED, zlib.Z_BEST_COMPRESSION,
      zlib.Z_DEFAULT_COMPRESSION, zlib.Z_DEFAULT_STRATEGY)
print("strategy", zlib.Z_FILTERED, zlib.Z_HUFFMAN_ONLY, zlib.Z_RLE, zlib.Z_FIXED)
print("flush", zlib.Z_NO_FLUSH, zlib.Z_PARTIAL_FLUSH, zlib.Z_SYNC_FLUSH, zlib.Z_FULL_FLUSH,
      zlib.Z_FINISH, zlib.Z_BLOCK, zlib.Z_TREES)

for level in (-1, 0, 1, 6, 9):
    print("compress", level, zlib.compress(text, level).hex())

for level in (1, 6, 9):
    print("gzip", level, zlib.compress(text, level, wbits=31).hex())
for level in (1, 6, 9):
    print("raw", level, zlib.compress(text, level, wbits=-15).hex())

print("roundtrip", zlib.decompress(zlib.compress(text)) == text)
print("gzipround", zlib.decompress(zlib.compress(text, 9, 31), 31) == text)
print("rawround", zlib.decompress(zlib.compress(text, 9, -15), -15) == text)
print("autoround", zlib.decompress(zlib.compress(text, 9, 31), 47) == text)

obj = zlib.compressobj(9)
chunks = [obj.compress(text[i:i + 17]) for i in range(0, len(text), 17)]
print("incremental", (b"".join(chunks) + obj.flush()).hex())

for mode in (zlib.Z_SYNC_FLUSH, zlib.Z_FULL_FLUSH):
    obj = zlib.compressobj(6)
    print("flushmode", mode, (obj.compress(text[:100]) + obj.flush(mode)
                              + obj.compress(text[100:]) + obj.flush()).hex())

obj = zlib.compressobj(6, zlib.DEFLATED, 15, 9, zlib.Z_FILTERED)
print("filtered", (obj.compress(text) + obj.flush()).hex())
obj = zlib.compressobj(6, zlib.DEFLATED, 15, 9, zlib.Z_HUFFMAN_ONLY)
print("huffonly", (obj.compress(text) + obj.flush()).hex())

obj = zlib.compressobj(zdict=b"quick brown")
dict_stream = obj.compress(text) + obj.flush()
print("dict", dict_stream.hex())
print("dictround", zlib.decompressobj(zdict=b"quick brown").decompress(dict_stream) == text)

obj = zlib.compressobj(6)
stream = obj.compress(text) + obj.flush()
first = zlib.decompressobj().decompress(stream, 100)
slack = zlib.decompressobj()
part = slack.decompress(stream, 100)
print("maxlen", len(first), len(part), part == text[:100])
print("tail", len(slack.unconsumed_tail), slack.eof, slack.unused_data)
rest = slack.decompress(slack.unconsumed_tail) + slack.flush()
print("maxlenrest", rest == text[100:], slack.eof)

print("unused", zlib.decompressobj().decompress(stream + b"trailing"))

short = zlib.decompressobj()
print("incomplete", len(short.decompress(stream[:-3])), short.eof)

print("adler", zlib.adler32(b"abc"), zlib.adler32(b"abc", 1), zlib.adler32(b"", 7),
      zlib.adler32(b"abc", -1))
print("crc", zlib.crc32(b"abc"), zlib.crc32(b"abc", 0), zlib.crc32(b""), zlib.crc32(b"abc", 123),
      zlib.crc32(b"abc", -1))

for call in ("zlib.compress(b'x', 10)", "zlib.compress(b'x', -2)",
             "zlib.decompress(zlib.compress(b'x'), 8)",
             "zlib.decompress(b'garbage')",
             "zlib.decompress(zlib.compress(b'x')[:-2])",
             "zlib.compressobj(10)", "zlib.decompressobj(3)",
             "zlib.compressobj().flush(6)",
             "zlib.decompressobj().flush(-1)"):
    try:
        print(call, "->", repr(eval(call)))
    except Exception as error:
        print(call, "!!", type(error).__name__ + ":", error)

pairs = [(b"", 15), (b"z", 9), (b"tail", 15)]
for payload, bits in pairs:
    try:
        print("badwbits", payload, bits, zlib.decompress(zlib.compress(payload), bits))
    except Exception as error:
        print("badwbits", payload, bits, type(error).__name__ + ":", error)
