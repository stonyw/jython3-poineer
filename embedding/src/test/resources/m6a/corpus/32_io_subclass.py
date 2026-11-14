# The `_io` types are subclassable, and the base bodies reach an override through ordinary
# attribute dispatch rather than through a Java field: `TextIOWrapper.read` asks its buffer for
# `read1`, and the buffered layer asks its raw for `readinto`.  Both sides print the same trace.
import sys

# The reference interpreter's own library, exactly as the other corpora do it, so both sides read
# the same `io.py` — the `io` module is not a builtin here, so the path comes first.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import io

trace = []


class Raw(io.RawIOBase):
    """A raw that only answers `readinto`, which is what the buffered layer asks of a descriptor."""

    def __init__(self, data):
        self.data = data
        self.pos = 0

    def readable(self):
        return True

    def readinto(self, b):
        n = min(len(b), len(self.data) - self.pos)
        b[:n] = self.data[self.pos:self.pos + n]
        self.pos += n
        trace.append(("readinto", n))
        return n


class Buffer(io.BufferedReader):
    """A buffer that records the `read1` calls the text layer makes on it."""

    def read1(self, size=-1):
        trace.append(("read1", size))
        return super().read1(size)


class Text(io.TextIOWrapper):
    """A text layer whose own `read` records the count the caller asked for."""

    def read(self, size=-1):
        trace.append(("read", size))
        return super().read(size)


# 1. The base `read1` body is reached through `super()` and reads the raw through `readinto`.
buffer = Buffer(Raw(b"alpha beta"))
print("read1:", buffer.read1(5))
print("trace:", trace)

# 2. `TextIOWrapper.read` dispatches to the buffer's `read1` override, and the override's
#    `super().read1` reaches the base body again.
trace.clear()
text = Text(Buffer(Raw(b"hello world")), encoding="utf-8")
print("text:", text.read(3))
print("trace:", [(name, size) for name, size in trace if name != "readinto"])

# 3. A `BytesIO` subclass answers through its own `read`, while the base keeps the store.
class Memory(io.BytesIO):

    def read(self, size=-1):
        trace.append(("memory-read", size))
        return super().read(size)


memory = Memory(b"xyz")
print("memory:", memory.read(2), memory.getvalue())

# 4. The abstract bases are subclassable too, and the rows they inherit read the value's own rows.
class Base(io.BufferedIOBase):

    def readable(self):
        return True


base = Base()
print("base:", base.readable(), base.writable(), isinstance(base, io.IOBase))

# 5. `BufferedWriter` and `BufferedRandom` name the same rows with their own direction.
class Writer(io.BufferedWriter):
    pass


class Random(io.BufferedRandom):
    pass


sink = io.BytesIO()
writer = Writer(sink)
writer.write(b"ok")
writer.flush()
print("writer:", sink.getvalue(), type(writer).__name__, isinstance(writer, io.BufferedWriter))

random = Random(io.BytesIO(b"seed"))
print("random:", random.read(2), type(random).__name__, isinstance(random, io.BufferedRandom))

# 6. A `FileIO` subclass takes the same path argument and writes through its own type.
class File(io.FileIO):
    pass


path = "/tmp/jy3_io_subclass_fixture.bin"
file = File(path, "wb")
file.write(b"data")
file.close()
reader = File(path, "rb")
print("file:", reader.read(), type(reader).__name__)
reader.close()
