# Buffer protocol: what an exporter presents, and what a consumer reads out of it.
import array
import sys

# The launcher starts with an empty sys.path, so `io` and `re` are reached the same way the other
# corpus scripts reach their stdlib modules.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import io
import re


def show(label, fn):
    try:
        print(label, "->", fn())
    except Exception as error:
        print(label, "!!", type(error).__name__, error)


def describe(view):
    return (view.format, view.itemsize, view.ndim, view.shape, view.strides,
            view.readonly, view.c_contiguous, view.nbytes, len(view))


raw = b"abc"
mv = memoryview(raw)
print("bytes.view", describe(mv))
print("bytes.obj", mv.obj is raw, mv.tolist())
show("bytes.write", lambda: mv.__setitem__(0, 65))

run = bytearray(b"abc")
mv = memoryview(run)
print("bytearray.view", describe(mv))
mv[0] = 65
print("bytearray.after", bytes(run), mv.tolist())

ints = array.array("i", [1, 2, 3])
mv = memoryview(ints)
print("array.view", describe(mv))
print("array.items", mv[1], mv[-1], mv.tolist())
print("array.slice", describe(mv[1:]), mv[1:].tolist())
print("array.step", describe(mv[::2]), mv[::2].tolist())
print("array.reref", describe(memoryview(mv[1:])))
print("array.cast", describe(mv.cast("B")), mv.cast("B").tolist())
print("array.castround", mv.cast("B").cast("i").tolist())
mv[0] = 7
print("array.writeback", ints.tolist())

octets = array.array("B", range(6))
print("bytes.array.strided", describe(memoryview(octets)[1::2]),
      memoryview(octets)[1::2].tolist())

print("consume", bytes(memoryview(ints)).hex(), bytes(ints).hex(),
      bytearray(memoryview(ints)).hex())
one = io.BytesIO()
one.write(memoryview(b"xy"))
two = io.BytesIO()
two.write(octets)
three = io.BytesIO()
three.write(bytearray(b"z"))
print("iowrite", one.getvalue(), two.getvalue().hex(), three.getvalue())
show("iowrite.str", lambda: io.BytesIO().write("x"))
show("iowrite.int", lambda: io.BytesIO().write(3))

print("re.bytearray", re.search(b"[a-h]+", bytearray(b"abcdefgh")).group())
print("re.array", re.compile(b"bla").match(array.array("b")))
print("re.finditer", [m.group() for m in re.finditer(rb"\w+", bytearray(b"ab cd"))])
show("re.bytes_on_str", lambda: re.compile(b"a").match("a"))
show("re.str_on_bytes", lambda: re.compile("a").match(b"a"))

exported = array.array("i", [1, 2, 3])
view = memoryview(exported)
show("buffer.append", lambda: exported.append(4))
show("buffer.extend", lambda: exported.extend([4]))
show("buffer.pop", lambda: exported.pop())
show("buffer.remove", lambda: exported.remove(1))
show("buffer.delitem", lambda: exported.__delitem__(0))
show("buffer.setslice", lambda: exported.__setitem__(slice(0, 0), exported))
show("buffer.imul", lambda: exported.__imul__(2))
view.release()
exported.append(4)
print("buffer.after_release", exported.tolist())

show("released.len", lambda: len(view))
show("released.tobytes", lambda: view.tobytes())
show("released.getitem", lambda: view[0])
show("released.cast", lambda: view.cast("B"))
show("released.release", lambda: view.release())
show("released.exit", lambda: view.__exit__(None, None, None))

show("memoryview.int", lambda: memoryview(3))
show("extend.other", lambda: array.array("i").extend(array.array("b")))
print("extend.memoryview",
      array.array("B").extend(memoryview(bytes(b"hi"))) or "ok")
print("extend.bytearray",
      array.array("B").extend(bytearray(b"hi")) or "ok")

print("array.roundtrip", array.array("h").tobytes().hex(),
      array.array("h", [1, -1]).tobytes().hex())
copy = array.array("i")
copy.frombytes(array.array("i", [4, 5]).tobytes())
print("array.frombytes", copy.tolist())
# The first half of buffer_info() is a process address; jy3 answers 0 there and the
# divergence is named in docs/re-java-regex-divergences.md. The length half is compared.
print("array.buffer_info", array.array("i", [1, 2]).buffer_info()[1],
      len(array.array("i", [1, 2]).buffer_info()))

print("ndim.cast", memoryview(bytes(b"\x01\x00\x00\x00")).cast("i").tolist())
print("sys.byteorder", sys.byteorder)
