# 内建类型子类化：float 与 bytes（M5 切片 31）。
# 值在 __new__ 定死、__init__ 其后照跑、__slots__ 走 (owner, name) 槽表、hash 与比较按值、
# 二元／一元槽回落；切片与拼接回落普通 bytes。

# ---- float ------------------------------------------------------------------------------

class F(float):
    __slots__ = ["prec"]
    def __new__(cls, value=0.0, prec=12):
        result = float.__new__(cls, value)
        result.prec = int(prec)
        return result
    def __repr__(self):
        return "%.*g" % (self.prec, self)

f = F(1.23456, 3)
print("F:", repr(f), type(f).__name__, f.prec)
print("float(f):", float(f), type(float(f)).__name__, type(float.__float__(f)).__name__)
print("pos/neg:", type(+f).__name__, type(-f).__name__, type(abs(f)).__name__)
print("mixed:", type(f + 1).__name__, type(f * 2).__name__, type(1 + f).__name__)
print("value:", f == 1.23456, hash(f) == hash(1.23456), {f: "v"}[float(f)])
print("inherited:", f.real, f.is_integer(), F(2.0).is_integer())
print("bool:", bool(F(0.0)), bool(F(1.0)))
print("new:", type(float.__new__(F, 2.5)).__name__, type(float.__new__(float, 2.5)).__name__)

class G(float):
    def __new__(cls, value=0.0, *rest):
        return float.__new__(cls, value)
    def __init__(self, value, extra):
        self.extra = extra
    def __bool__(self):
        return False

g = G(1.0, "x")
print("G:", g.extra, bool(g), type(g).__name__, g == 1.0)

class Plain(float):
    pass

class Deeper(Plain):
    pass

print("deeper:", type(Deeper(2.5)).__name__, Deeper(2.5) + 1, [c.__name__ for c in Deeper.__mro__])

# ---- bytes ------------------------------------------------------------------------------

class B(bytes):
    pass

b = B(b"abc")
print("B:", type(b).__name__, b, repr(b), len(b), b[0], type(b[1:]).__name__)
print("concat:", type(b + b"d").__name__, type(b"d" + b).__name__, type(b * 2).__name__)
print("hex:", b.hex(), type(b.hex()).__name__)
print("decode:", b.decode(), type(b.decode()).__name__)
print("eq/hash:", b == b"abc", hash(b) == hash(b"abc"), B(b"abc") == B(b"abc"))
print("buffer:", type(b.__buffer__(0)).__name__)
print("bytes(b):", type(bytes(b)).__name__)
print("methods:", b.upper(), type(b.upper()).__name__, b.split(b"b"), type(b.split(b"b")[0]).__name__)

try:
    b.hex(1)
except TypeError as error:
    print("hex refuse:", error)
try:
    b.decode(1)
except TypeError as error:
    print("decode refuse:", error)

class C(bytes):
    def __new__(cls, value=b"", *rest):
        return bytes.__new__(cls, value)
    def __init__(self, value, tag):
        self.tag = tag

c = C(b"hi", "t")
print("C:", c, c.tag, type(c).__name__, c == b"hi", hash(c) == hash(b"hi"))

class PlainBytes(bytes):
    pass

class DeeperBytes(PlainBytes):
    pass

print("deeper bytes:", type(DeeperBytes(b"xy")).__name__, DeeperBytes(b"xy") + b"z", type(DeeperBytes(b"xy")[0:1]).__name__)
