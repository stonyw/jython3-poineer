import array

# The file surface is measured with a Python object that has the two rows `fromfile`/`tofile` use,
# because this runtime has no `io` module yet.
class Sink:
    def __init__(self):
        self.data = b""

    def write(self, chunk):
        self.data = self.data + bytes(chunk)
        return len(chunk)


class Source:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def read(self, size):
        chunk = self.data[self.pos:self.pos + size]
        self.pos = self.pos + len(chunk)
        return chunk

# The module and its one type.
print("module", array.__name__)
print("type", array.array.__name__, array.array.__module__)
print("typecodes", array.typecodes)

for code in array.typecodes:
    value = array.array(code)
    print("itemsize", code, value.itemsize, len(value.tobytes()), value.buffer_info()[1])

# Constructors.
print("list", array.array('i', [1, 2, 3]).tolist())
print("tuple", array.array('i', (4, 5)).tolist())
print("bytes", array.array('i', b'\x01\x00\x00\x00\x02\x00\x00\x00').tolist())
print("bytearray", array.array('i', bytearray(b'\x03\x00\x00\x00')).tolist())
print("memoryview", array.array('i', memoryview(b'\x01\x00\x00\x00')).tolist())
print("generator", array.array('i', (v * 2 for v in [1, 2, 3])).tolist())
print("set", sorted(array.array('i', {4, 2}).tolist()))
print("copy", array.array('i', array.array('i', [7, 8])).tolist())
print("b from b", array.array('b', [-1, 0, 127]).tolist())
print("float", array.array('f', [0.5, -1.25]).tolist())
print("double", array.array('d', [0.1]).tolist())
print("empty", array.array('i').tolist(), len(array.array('i')))

# Sequence surface.
a = array.array('i', [10, 20, 30])
print("get", a[0], a[-1], a[1:3].tolist(), a[::-1].tolist(), a[0:3:2].tolist())
a.append(40)
a.extend([50, 60])
print("append extend", a.tolist())
a.insert(0, 5)
a.insert(-100, 1)
a.insert(100, 99)
print("insert", a.tolist())
print("pop", a.pop(), a.pop(0), a.tolist())
a.remove(20)
print("remove", a.tolist())
print("index", a.index(30), a.index(30, 1), a.index(30, 0, 100))
print("count", a.count(30), a.count(31), 30 in a, 31 in a)
a[0] = 7
print("setitem", a.tolist())
del a[0]
del a[1:3]
print("delitem", a.tolist())
a[0:1] = array.array('i', [8, 9])
print("slice assign resize", a.tolist())
a[::2] = array.array('i', [1, 2])
print("slice assign stride", a.tolist())
b = array.array('i', [1, 2])
b += array.array('i', [3])
b *= 3
print("inplace", b.tolist())
print("add", (array.array('i', [1]) + array.array('i', [2])).tolist())
print("mul", (array.array('i', [1, 2]) * 2).tolist(), (array.array('i', [1]) * 0).tolist())
print("rmul", (2 * array.array('i', [3])).tolist())
print("eq", array.array('i', [1]) == array.array('l', [1]), array.array('i', [1]) == [1])
print("lt", array.array('i', [1]) < array.array('i', [2]))
print("repr", repr(array.array('i', [1, 2])), repr(array.array('i')), repr(array.array('w', 'a\nb\x00')))
print("sizeof", array.array('i', [1, 2, 3]).__sizeof__())

# The byte surface.
raw = array.array('i', [1, 2]).tobytes()
print("tobytes", raw.hex(), type(raw).__name__)
print("d tobytes", array.array('d', [1.5]).tobytes().hex())
c = array.array('i')
c.frombytes(b'\x01\x00\x00\x00\x02\x00\x00\x00')
print("frombytes", c.tolist())
c.byteswap()
print("byteswap", c.tolist(), c.tobytes().hex())
d = array.array('d', [1.5, 2.5])
d.reverse()
print("reverse", d.tolist())
print("buffer_info", array.array('i', [1, 2, 3]).buffer_info()[1], array.array('i').buffer_info()[1])
print("w", array.array('w', 'ab').tounicode())

# memoryview over an array.
arr = array.array('i', [1, 2])
view = memoryview(arr)
print("mv", view.format, view.itemsize, view.shape, view.strides, view.readonly, view.tolist())
view[0] = 9
print("mv write", arr.tolist())
arr.byteswap()
print("mv swap", arr.tolist(), view.tolist())
try:
    arr.append(3)
except Exception as e:
    print("mv resize", type(e).__name__, e)
view.release()
arr.append(4)
print("mv released", arr.tolist())

# Files.
source = array.array('i', [1, 2, 3])
sink = Sink()
source.tofile(sink)
print("tofile", sink.data.hex())
loaded = array.array('i')
loaded.fromfile(Source(sink.data), 3)
print("fromfile", loaded.tolist())
short = array.array('i')
try:
    short.fromfile(Source(b'\x01\x00\x00\x00'), 2)
except Exception as e:
    print("fromfile short", type(e).__name__, e, short.tolist())

# Refusals.
def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "!!", type(e).__name__, e)

show("bad code", lambda: array.array('x'))
show("long code", lambda: array.array('ii'))
show("empty code", lambda: array.array(''))
show("int code", lambda: array.array(1))
show("none code", lambda: array.array(None))
show("no args", lambda: array.array())
show("three args", lambda: array.array('i', [], 1))
show("keyword", lambda: array.array(typecode='i'))
show("str init", lambda: array.array('i', 'ab'))
show("bytes odd", lambda: array.array('i', b'abc'))
show("b overflow", lambda: array.array('b', [128]))
show("b underflow", lambda: array.array('b', [-129]))
show("B overflow", lambda: array.array('B', [256]))
show("B negative", lambda: array.array('B', [-1]))
show("h overflow", lambda: array.array('h', [32768]))
show("H negative", lambda: array.array('H', [-1]))
show("i overflow", lambda: array.array('i', [2 ** 31]))
show("I negative", lambda: array.array('I', [-1]))
show("l overflow", lambda: array.array('l', [2 ** 63]))
show("L negative", lambda: array.array('L', [-1]))
show("q overflow", lambda: array.array('q', [2 ** 63]))
show("Q negative", lambda: array.array('Q', [-1]))
show("float type", lambda: array.array('f', ['x']))
show("getitem str", lambda: array.array('i', [1])['x'])
show("getitem oob", lambda: array.array('i', [1])[3])
show("setitem oob", lambda: array.array('i', [1]).__setitem__(3, 0))
show("del oob", lambda: array.array('i', [1]).__delitem__(3))
show("slice list", lambda: array.array('i', [1]).__setitem__(slice(0, 1), [9]))
show("slice code", lambda: array.array('i', [1]).__setitem__(slice(0, 1), array.array('b', [9])))
show("slice length", lambda: array.array('i', [1, 2, 3]).__setitem__(slice(None, None, 2), array.array('i', [9])))
show("add list", lambda: array.array('i', [1]) + [2])
show("add code", lambda: array.array('i', [1]) + array.array('b', [2]))
show("iadd list", lambda: array.array('i', [1]).__iadd__([2]))
show("iadd code", lambda: array.array('i', [1]).__iadd__(array.array('b', [2])))
show("mul float", lambda: array.array('i', [1]) * 1.5)
show("pop empty", lambda: array.array('i').pop())
show("pop oob", lambda: array.array('i', [1]).pop(4))
show("remove missing", lambda: array.array('i', [1]).remove(2))
show("index missing", lambda: array.array('i', [1]).index(2))
show("index float start", lambda: array.array('i', [1]).index(1, 1.5))
show("append args", lambda: array.array('i').append(1, 2))
show("append keyword", lambda: array.array('i').append(x=1))
show("extend args", lambda: array.array('i').extend([1], [2]))
show("extend keyword", lambda: array.array('i').extend(iterable=[1]))
show("extend str", lambda: array.array('i').extend('ab'))
show("pop keyword", lambda: array.array('i').pop(index=1))
show("insert args", lambda: array.array('i').insert(0, 1, 2))
show("tounicode", lambda: array.array('i').tounicode())
show("fromunicode", lambda: array.array('i').fromunicode('a'))
show("frombytes type", lambda: array.array('i').frombytes('abc'))
show("frombytes odd", lambda: array.array('i').frombytes(b'abc'))
show("hash", lambda: hash(array.array('i')))
show("typecode write", lambda: setattr(array.array('i'), 'typecode', 'b'))
show("extra write", lambda: setattr(array.array('i'), 'x', 1))

# The iterator.
it = iter(array.array('i', [1, 2]))
print("iter", type(it).__name__, type(it).__module__, hasattr(it, '__length_hint__'))
print("iter values", list(it), it.__reduce__()[1])
fresh = iter(array.array('i', [1, 2, 3]))
print("iter setstate", next(fresh), fresh.__setstate__(2), list(fresh))
show("iter setstate float", lambda: fresh.__setstate__(1.5))

# Subclassing.
class X(array.array):
    pass

x = X('i', [1, 2])
print("sub", type(x).__name__, isinstance(x, array.array), x.tolist(), x.typecode)
print("sub repr", repr(x))
x.tag = "kept"
print("sub attr", x.tag, hasattr(x, 'tag'))
print("sub slice type", type(x[0:1]).__name__, type(x + array.array('i', [3])).__name__)
print("sub mutating", (lambda: (x.append(3), x.tolist()))())

class Y(array.array):
    def __init__(self, *args, **kwargs):
        self.seen = args

y = Y('i', [5])
print("sub init", type(y).__name__, y.tolist(), y.seen)

class Z(array.array):
    def __new__(cls, *args):
        return super().__new__(cls, *args)

print("sub new", Z('i', [6]).tolist())
