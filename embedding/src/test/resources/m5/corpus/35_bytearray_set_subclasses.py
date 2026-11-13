# 内建类型子类化：bytearray、set 与 frozenset（M5 切片 32）。
# bytearray 的值可变、__init__ 往已有值里填、repr/str 带子类名、+= 与 *= 保留子类；
# set/frozenset 的运算符与 copy 回落普通基类、|= 保留子类、frozenset 的 hash 按内容。

# ---- bytearray ---------------------------------------------------------------------------

class BA(bytearray):
    pass

b = BA(b"abc")
print("BA:", type(b).__name__, repr(b), str(b), len(b), b[0], isinstance(b, bytearray))
print("BA slice:", type(b[1:]).__name__, b[1:], type(b[0]).__name__, b[0])
print("BA concat:", type(b + b"d").__name__, b + b"d", type(b"d" + b).__name__,
      type(b * 2).__name__, type(2 * b).__name__)
print("BA copy:", type(b.copy()).__name__, b.copy())
print("BA text:", b.hex(), b.decode(), b.hex() == "616263")

print("BA empty:", repr(BA()), len(BA()), type(BA()).__name__)
print("BA new:", repr(bytearray.__new__(BA)), type(bytearray.__new__(BA)).__name__,
      type(bytearray(b"x")).__name__)

b3 = BA(b"xy")
b3 += b"z"
print("BA iadd:", repr(b3), type(b3).__name__)
b3 *= 2
print("BA imul:", repr(b3), type(b3).__name__)
b3[0] = 65
b3[1:1] = b"Q"
print("BA mutate:", repr(b3))

b.__init__(b"re")
print("BA reinit:", repr(b), type(b).__name__, len(b))

try:
    hash(BA(b"x"))
except TypeError as error:
    print("BA unhashable:", error)

class BAHash(bytearray):
    def __hash__(self):
        return 7

print("BA custom hash:", hash(BAHash(b"x")))

class BADict(bytearray):
    def __init__(self, value=b"", tag="t"):
        super().__init__(value)
        self.tag = tag
    def __repr__(self):
        return "BADict"
    def __str__(self):
        return "as-text"

bad = BADict(b"hi", "x")
print("BA class:", repr(bad), str(bad), bad.tag, type(bad).__name__, bad == b"hi")

class BARepr(bytearray):
    def __repr__(self):
        return "custom"

print("BA repr-only:", repr(BARepr(b"a")), str(BARepr(b"a")))

class BASlots(bytearray):
    __slots__ = ("tag",)

bs = BASlots(b"q")
bs.tag = 5
print("BA slots:", bs.tag, hasattr(bs, "__dict__"), repr(bs))

class BAEmpty(bytearray):
    def __bool__(self):
        return False

print("BA bool:", bool(BAEmpty(b"x")))

class BADeeper(BA):
    pass

print("BA deeper:", type(BADeeper(b"k")).__name__, repr(BADeeper(b"k")),
      type(BADeeper(b"k") + b"!").__name__)

# ---- set ---------------------------------------------------------------------------------

class S(set):
    pass

s = S([1, 2])
print("S:", type(s).__name__, repr(S([1])), repr(S()), isinstance(s, set), len(s), sorted(s), 1 in s)
print("S ops:", type(s | {3}).__name__, type(s & {1}).__name__, type(s - {1}).__name__,
      type(s ^ {3}).__name__)
print("S methods:", type(s.union({3})).__name__, type(s.copy()).__name__,
      type(s.difference({1})).__name__, type(s.intersection({1})).__name__)

s |= {4}
print("S ior:", type(s).__name__, sorted(s))
s -= {1}
print("S isub:", type(s).__name__, sorted(s))
print("S eq:", S([1]) == {1}, {1} == S([1]), bool(S()), bool(S([0])))
print("S set():", type(set(s)).__name__)
print("S new:", repr(set.__new__(S)), type(set.__new__(S)).__name__,
      repr(set.__new__(S, [1])))

try:
    hash(S([1]))
except TypeError as error:
    print("S unhashable:", error)

s2 = S([1, 2])
s2.__init__([3])
s2.tag = "t"
print("S reinit:", repr(s2), sorted(s2), type(s2).__name__, s2.tag)

class SSlots(set):
    __slots__ = ("tag",)

ss = SSlots([1])
ss.tag = 7
print("S slots:", ss.tag, hasattr(ss, "__dict__"), repr(ss))

class SRepr(set):
    def __repr__(self):
        return "custom"

print("S repr-only:", repr(SRepr([1])), str(SRepr([1])))

class SStr(set):
    def __str__(self):
        return "as-text"

print("S str:", repr(SStr([1])), str(SStr([1])))

class SOr(set):
    def __or__(self, other):
        return "custom-or"

print("S custom or:", repr(SOr([1]) | {2}), type({2} | SOr([1])).__name__)

class SPlain(set):
    pass

class SDeeper(SPlain):
    pass

print("S deeper:", type(SDeeper([1])).__name__, repr(SDeeper([1])),
      type(SDeeper([1]) | {2}).__name__)

try:
    S([1], [2])
except TypeError as error:
    print("S arity:", error)
try:
    S(x=1)
except TypeError as error:
    print("S keyword:", error)

# ---- frozenset ---------------------------------------------------------------------------

class FS(frozenset):
    pass

f = FS([1, 2])
print("FS:", type(f).__name__, repr(FS([1])), repr(FS()), isinstance(f, frozenset), len(f),
      sorted(f), 1 in f)
print("FS ops:", type(f | {3}).__name__, type(f & {1}).__name__, type(f - {1}).__name__,
      type(f ^ {3}).__name__)
print("FS methods:", type(f.union({3})).__name__, type(f.copy()).__name__, f.copy() is f)
print("FS hash:", hash(f) == hash(frozenset([1, 2])), f == frozenset([1, 2]),
      frozenset([1, 2]) == f)
print("FS frozenset():", type(frozenset(f)).__name__, frozenset(f) is f)
print("FS new:", repr(frozenset.__new__(FS, [1])), type(frozenset.__new__(FS, [1])).__name__)

g = f
g |= {5}
print("FS ior:", type(g).__name__, sorted(g))

class FSHash(frozenset):
    def __hash__(self):
        return 42

print("FS custom hash:", hash(FSHash([1])))

class FSInit(frozenset):
    def __init__(self, values):
        self.tag = "x"

fi = FSInit([1])
print("FS init:", repr(fi), fi.tag, type(fi).__name__)

class FSSlots(frozenset):
    __slots__ = ("tag",)

fs = FSSlots([1])
fs.tag = 3
print("FS slots:", fs.tag, hasattr(fs, "__dict__"), repr(fs))

try:
    FS([1], [2])
except TypeError as error:
    print("FS arity:", error)
try:
    FS(x=1)
except TypeError as error:
    print("FS keyword:", error)
