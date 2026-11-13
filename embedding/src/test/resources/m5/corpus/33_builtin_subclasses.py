import sys

MT = type(sys)

# module_new leaves the dictionary empty; module_init files the five names, and only those.
fresh = MT.__new__(MT)
print("new leaves:", fresh.__dict__)
print("made by init:", sorted(MT("named").__dict__))


class Mod(MT):
    a = 1

    def __init__(self, name):
        MT.__init__(self, name)

    def __getattribute__(self, name):
        print("get", name)
        return MT.__getattribute__(self, name)

    def __setattr__(self, name, value):
        print("set", name)
        MT.__setattr__(self, name, value)

    def __delattr__(self, name):
        print("del", name)
        MT.__delattr__(self, name)


mod = Mod("m")
print("class member through the value:", mod.a, mod.__name__)
mod.b = 2
print("stored:", mod.__dict__["b"])
print("dir:", dir(mod))
del mod.b
print("gone:", "b" in mod.__dict__)

try:
    mod.__dict__ = {}
except AttributeError as error:
    print("dict is readonly:", error)
try:
    Mod.__new__(Mod, "ignored")
    print("new ignores arguments")
except TypeError as error:
    print("new refuses:", error)


class Number(complex):
    __slots__ = ["prec"]

    def __new__(cls, value, prec=2):
        result = complex.__new__(cls, value)
        result.prec = prec
        return result

    def __str__(self):
        return "%s+%sj" % (self.real, self.imag)

    __repr__ = __str__


n = Number(3.5, 6)
print(type(n).__name__, n.prec, n.real, n.imag, str(n), repr(n))
print("value equality:", n == 3.5 + 0j, hash(n) == hash(3.5 + 0j))
print("inherited slots:", n + 1, n.conjugate(), isinstance(n, complex))


class Plain(complex):
    pass


p = Plain(1, 2)
print(type(p).__name__, p, p == 1 + 2j, complex.__new__(Plain, 3) == 3 + 0j)

# 参考自身拒绝作基的类型（没有 Py_TPFLAGS_BASETYPE）：TypeError 点名，而不是运行时的 NotImplementedError。
for name, base in (
    ("NoneType", type(None)),
    ("bool", bool),
    ("memoryview", memoryview),
    ("range", range),
    ("slice", slice),
):
    try:
        class Refused(base):
            pass
    except TypeError as error:
        print(name, "refused:", error)

