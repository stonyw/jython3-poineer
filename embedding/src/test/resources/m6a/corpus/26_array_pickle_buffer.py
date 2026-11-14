import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

# `array`'s two surfaces that leave the object itself: the pickle protocol (`__reduce_ex__` and the
# module-level `_array_reconstructor` it names) and the buffer a `memoryview` walks. Both are measured
# against the reference by the same script, so every line here is a byte the two must agree on.
import array
import copy
import pickle

# The pickle family, every protocol. `bytes.hex()` is what is compared: the pickle is a deterministic
# function of the value, so a difference is a difference in the reconstruction recipe, not in framing.
CASES = [
    ('b', [-1, 0, 127]),
    ('B', [0, 1, 255]),
    ('h', [-2, 3]),
    ('H', [2, 3]),
    ('i', [-3, 4]),
    ('I', [3, 4]),
    ('l', [-4, 5]),
    ('L', [4, 5]),
    ('q', [-5, 6]),
    ('Q', [5, 6]),
    ('f', [1.5, -2.25]),
    ('d', [1.5, -2.25]),
    ('w', ['a', 'b']),
]

for code, items in CASES:
    a = array.array(code, items)
    for proto in range(pickle.HIGHEST_PROTOCOL + 1):
        print("pickle", code, proto, pickle.dumps(a, proto).hex())
    back = pickle.loads(pickle.dumps(a, pickle.HIGHEST_PROTOCOL))
    print("load", code, back.typecode, back.tolist(), back == a, type(back).__name__)
    print("copy", code, copy.copy(a).tolist(), copy.deepcopy(a).tolist())
    print("reduce", code,
          a.__reduce_ex__(0)[0] is array.array,
          a.__reduce_ex__(2)[0] is array.array,
          a.__reduce_ex__(3)[0] is array._array_reconstructor,
          len(a.__reduce_ex__(3)[1]))

# A subclass keeps its class and its instance dict across the same recipe.
class Tagged(array.array):
    pass

t = Tagged('i', [1, 2])
t.name = "x"
data = pickle.dumps(t, pickle.HIGHEST_PROTOCOL)
print("sub pickle", data.hex())
u = pickle.loads(data)
print("sub load", type(u).__name__, u.tolist(), u.name)
print("sub copy", type(copy.copy(t)).__name__, type(copy.deepcopy(t)).__name__)
print("sub copy plain", copy.copy(t).tolist(), copy.deepcopy(t).tolist())

# The buffer a memoryview sees, per typecode.
print("typecodes", array.typecodes)
for code in "bBhHiIlLqQfdw":
    items = ['a', 'b'] if code == 'w' else [1, 2]
    a = array.array(code, items)
    m = memoryview(a)
    print("mv", code, m.format, m.itemsize, m.ndim, m.shape, m.strides, m.readonly)
    print("mv bytes", code, m.tobytes().hex(), m.hex())

# `cast` changes the format and the shape without copying.
a = array.array('i', [1, 2, 3, 4])
m = memoryview(a)
print("cast", m.cast('B').format, m.cast('B').shape, m.cast('B').tobytes().hex())
nd = m.cast('B', (2, 8))
print("cast nd", nd.format, nd.shape, nd.strides, nd.tobytes().hex())
print("cast back", nd.cast('i').shape, nd.cast('i').tobytes().hex())
print("cast d", memoryview(array.array('B', [1, 2, 3, 4, 5, 6, 7, 8])).cast('d').tolist())

# A stride view selects every other item, and `tobytes` walks it in order.
strided = memoryview(array.array('i', [10, 20, 30, 40]))[::2]
print("strided", strided.shape, strided.strides, strided.tobytes().hex(), strided.tolist())

def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "!!", type(e).__name__, e)

# The refusals `cast` keeps apart.
show("cast format", lambda: memoryview(array.array('i', [1])).cast('x'))
show("cast shape", lambda: memoryview(array.array('i', [1])).cast('B', (2,)))
show("cast wide", lambda: memoryview(array.array('i', [1])).cast('h'))
show("cast nd to nd", lambda: memoryview(array.array('i', [1, 2])).cast('B', (2, 4)).cast('B', (2, 2)))

# A released view answers four things and refuses the rest.
r = memoryview(array.array('i', [1, 2]))
r.release()
print("released repr", repr(r).split(" at ")[0], r.__class__.__name__)
print("released eq", r == r)
show("released obj", lambda: r.obj)
show("released format", lambda: r.format)
show("released tobytes", lambda: r.tobytes())
show("released cast", lambda: r.cast('B'))
show("released release", lambda: r.release())

# An export pins the length: a resize is refused until the view gives the export back.
pinned = array.array('b', [1, 2])
view = memoryview(pinned)
show("pinned append", lambda: pinned.append(3))
view.release()
pinned.append(3)
print("after release", pinned.tolist())

# Two views over the same run: releasing one leaves the other usable.
shared = array.array('i', [7])
first = memoryview(shared)
second = memoryview(first)
first.release()
print("shared", second.tolist(), memoryview(shared).tolist())
second.release()
