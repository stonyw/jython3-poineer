# `struct` and `base64` are the first two standard-library modules that want a C extension
# underneath them: `struct.py` is a thin wrapper over `_struct`, and `base64` reaches for only
# `struct` and `binascii`. This script imports both whole and pins the formats, the byte order,
# the offsets, and every sentence the two extensions print when a call is refused.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import binascii
import base64
import struct


def show(label, fn):
    try:
        print(label, "ok", fn())
    except Exception as exc:
        print(label, type(exc).__name__, exc)


print("sizes", struct.calcsize("<i"), struct.calcsize(">q"), struct.calcsize("!h"), struct.calcsize("@b0l"))
print("native align", struct.calcsize("bhl"), struct.calcsize("bhq"))
print("pack little", struct.pack("<i", 258), struct.pack("<I", 4294967295))
print("pack big", struct.pack(">2h", 1, -1), struct.pack("!Q", 2 ** 64 - 1))
print("pack signed", struct.pack("<bBhHiIlLqQ", -1, 255, -1, 65535, -1, 4294967295, -1, 4294967295, -1, 2 ** 64 - 1))
print("unpack signed", struct.unpack("<bBhHiIlLqQ", struct.pack("<bBhHiIlLqQ", -7, 200, -300, 60000, -70000, 4000000000, -5, 4000000000, -9, 18446744073709551615)))
print("bytes field", struct.pack("3s", b"abcdef"), struct.unpack("3s", b"abc"))
print("pascal", struct.pack("5p", b"hello"), struct.unpack("5p", struct.pack("5p", b"hello")))
print("bool", struct.pack("?", True), struct.unpack("??", b"\x00\x02"))
print("char", struct.pack("c", b"x"), struct.unpack("c", b"x"))
print("floats", struct.pack("<f", 1.5), struct.pack(">d", 1.5), struct.pack("<e", 1.5))
print("float round", struct.unpack("<f", struct.pack("<f", 0.1)), struct.unpack("<d", struct.pack("<d", 0.1)))
print("half round", struct.unpack("<e", struct.pack("<e", 65504.0)))
print("pointer", struct.pack("@P", 0), struct.pack("@N", 2 ** 64 - 1))
show("pointer prefix", lambda: struct.Struct("<P"))
print("complex", struct.pack("<F", 1.5), struct.pack(">D", 1.5))
show("half overflow", lambda: struct.pack("<e", 1e10))

compiled = struct.Struct("<2i")
print("compiled", compiled.size, compiled.format, repr(compiled))
print("compiled pack", compiled.pack(1, 2), compiled.unpack(b"\x01\x00\x00\x00\x02\x00\x00\x00"))
print("iter", list(compiled.iter_unpack(b"\x01\x00\x00\x00\x02\x00\x00\x00\x03\x00\x00\x00\x04\x00\x00\x00")))
buffer = bytearray(8)
struct.pack_into("<i", buffer, 4, 42)
print("into", bytes(buffer))
print("from", struct.unpack_from("<i", b"\x00\x00\x07\x00\x00\x00\x00\x00", 2))
print("from compiled", compiled.unpack_from(b"\xff" * 4 + b"\x01\x00\x00\x00\x02\x00\x00\x00", 4))
show("unpack short", lambda: struct.unpack("<i", b"\x01\x02"))
show("into read-only", lambda: struct.pack_into("<i", b"abcd", 0, 1))
show("into short", lambda: struct.pack_into("<i", bytearray(2), 0, 1))
show("bad format", lambda: struct.Struct("iZ"))
show("count without code", lambda: struct.Struct("10"))
show("no format", lambda: struct.Struct(foo=1))
show("arguments", lambda: struct.pack("<i", 1, 2))
show("too large", lambda: struct.pack("<b", 200))
show("no kwargs", lambda: compiled.pack(x=1))
print("cache", isinstance(struct.Struct("h").format, str), struct.Struct("h") is not struct.Struct("h"))

print("hex", binascii.hexlify(b"\xb9\x01\xef"), binascii.b2a_hex(b"\x00\xff"))
print("hex sep", binascii.hexlify(b"abcdef", b":", 2), binascii.hexlify(b"abcde", b":", -2))
print("unhex", binascii.unhexlify(b"b901ef"), binascii.a2b_hex("00ff"))
print("b64", binascii.b2a_base64(b"abc"), binascii.b2a_base64(b""), binascii.b2a_base64(b"abcd", newline=False))
print("a64", binascii.a2b_base64(b"YWJj"), binascii.a2b_base64(b"YQ==\n"))
print("a64 strict", binascii.a2b_base64(b"YQ==", strict_mode=True))
show("a64 strict char", lambda: binascii.a2b_base64(b"YQ==!", strict_mode=True))
show("a64 strict excess pad", lambda: binascii.a2b_base64(b"YQ===", strict_mode=True))
show("a64 strict excess data", lambda: binascii.a2b_base64(b"YQ==YQ==", strict_mode=True))
show("a64 padding", lambda: binascii.a2b_base64(b"YQ"))
show("odd hex", lambda: binascii.unhexlify(b"0"))
show("bad hex", lambda: binascii.unhexlify(b"zz"))
print("uu", binascii.b2a_uu(b"abc"), binascii.a2b_uu(b"#86)C\n"))
print("crc", binascii.crc32(b"abc"), binascii.crc32(b"abc", 1), binascii.crc_hqx(b"abc", 0))
print("qp", binascii.b2a_qp(b"a b \n\t"), binascii.a2b_qp(b"a=3Db"))

print("b64encode", base64.b64encode(b"hello world"), base64.b64decode(b"aGVsbG8="))
print("b64 altchars", base64.b64encode(b"\xfb\xff", altchars=b"-_"), base64.urlsafe_b64encode(b"\xfb\xff"))
print("b64 lines", base64.encodebytes(b"hello world" * 8) == base64.encodebytes(b"hello world" * 8))
print("b64 decode lines", base64.decodebytes(b"aGVsbG8gd29ybGQ=\n"))
print("b32", base64.b32encode(b"abc"), base64.b32decode(b"MFRGG==="))
print("b16", base64.b16encode(b"\x01\x02"), base64.b16decode(b"0102"))
print("b85", base64.b85encode(b"hello"), base64.b85decode(b"Xk~0{Zv"))
print("a85", base64.a85encode(b"hello"), base64.a85decode(b"BOu!rDZ"))
print("b85 pad", base64.b85encode(b"he", pad=True), base64.a85encode(b"he", adobe=True))
print("b64 strict module", base64.b64decode(b"aGVs\nbG8="), base64.b64decode(b"aGVsbG8=", validate=False))
show("b64 unpadded", lambda: base64.b64decode(b"aGVsbG8"))
show("b64 wrong padding", lambda: base64.b64decode(b"aGVsbG8=", validate=True))
print("struct in base64", base64.b85encode(b"\x00\x00\x00\x00"), base64.b85encode(b"    "))
