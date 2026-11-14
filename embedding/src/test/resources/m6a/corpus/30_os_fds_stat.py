import sys

# `tempfile` is the reference's, put on the path before anything is asked.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os
import pickle
import struct
import tempfile
import warnings

# `i` is a signed 4-byte int in the machine's order; the high bit decides the sign, and for
# little-endian input that is the last byte rather than the first.
print(struct.unpack("<i", b"\x8fO\xf8j"))
print(struct.unpack("<i", b"\xff\xff\xff\x7f"))
print(struct.unpack("<i", b"\x00\x00\x00\x80"))
print(struct.unpack("<H", b"\xff\xff"), struct.unpack("<I", b"\xff\xff\xff\xff"))
print(struct.unpack(">i", b"\x8fO\xf8j"), struct.unpack(">i", b"\xff\xff\xff\xff"))
print(struct.pack("<i", -2) == b"\xfe\xff\xff\xff")
print(struct.unpack("<q", b"\xff" * 8)[0] == -1)

# A value built from a sequence keeps the items as given and has no fraction to add.
built = os.stat_result(tuple(range(10)))
print(built)
print(built[7], type(built.st_atime).__name__, built.st_atime_ns, built.st_birthtime)
print(built.n_sequence_fields)
for bad in ((10,), "x"):
    try:
        os.stat_result(bad)
    except TypeError as error:
        print(type(error).__name__, error)

root = tempfile.mkdtemp()
os.chdir(root)
with open("data.txt", "w") as handle:
    handle.write("hello")
os.utime("data.txt", ns=(5_000_000_000, 5_000_000_000))
info = os.stat("data.txt")
print(info[7], type(info[7]).__name__, type(info.st_atime).__name__)
print(info.st_atime == 5.0, info.st_atime_ns == 5_000_000_000)
print(info[7] == int(info.st_atime), info[8] == int(info.st_mtime))
print(repr(info).startswith("os.stat_result(st_mode="))

for protocol in range(6):
    blob = pickle.dumps(info, protocol)
    again = pickle.loads(blob)
    print(protocol, b"stat_result" in blob, again == info, type(again).__name__)

read_fd = os.open("data.txt", os.O_RDONLY)
print(read_fd >= 3, os.get_inheritable(read_fd), os.get_inheritable(1))
os.set_inheritable(read_fd, True)
print(os.get_inheritable(read_fd))
copy = os.dup(read_fd)
print(copy != read_fd, os.get_inheritable(copy))
os.closerange(min(read_fd, copy), max(read_fd, copy) + 1)
for closed in (read_fd, copy):
    try:
        os.fstat(closed)
    except OSError as error:
        print(type(error).__name__, error.errno)
print(os.isatty(read_fd), os.isatty(9999))

# `os.open` takes its two required arguments by name as well as by position.
named = os.open(path="data.txt", flags=os.O_RDONLY)
print(named >= 3)
os.closerange(named, named + 1)
try:
    os.open()
except TypeError as error:
    print(type(error).__name__, error)
try:
    os.open("data.txt")
except TypeError as error:
    print(type(error).__name__, error)

# `os.fdopen` over a real descriptor answers text, and a bool is refused only through its warning.
handle = os.fdopen(os.open("data.txt", os.O_RDONLY), "r", encoding="utf-8")
print(handle.read(), handle.close())

with warnings.catch_warnings():
    warnings.simplefilter("error")
    try:
        os.fdopen(False, "r")
    except RuntimeWarning as error:
        print(type(error).__name__, error)
try:
    os.fdopen(2 ** 20, "r")
except OSError as error:
    print(type(error).__name__, error.errno)

os.remove("data.txt")
