"""Where CPython asks a value for {@code __int__} and {@code __float__}, and in whose words it refuses.

The two conversion slots are read by three readers, and the readers differ in what they do with a refusal.
{@code int()} borrows {@code PyNumber_Long}, which asks {@code __int__} first and {@code __index__} only
when there is no {@code __int__}; {@code float()} borrows {@code PyNumber_Float}, whose two slots are
{@code __float__} and {@code __index__} and never {@code __int__}; and {@code %d} borrows the first of them
under a sentence of its own. So one class can be seven to {@code int()}, refuse to {@code float()} and be
the {@code ValueError} raised inside its own method to {@code %d} — which is what the rows below measure.

Every class here is used through an instance, and nothing prints a value whose {@code repr} carries an
address.
"""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


class Int:
    def __int__(self):
        return 7


class Minus:
    def __int__(self):
        return -4


class IntBad:
    def __int__(self):
        return "seven"


class IntRaised:
    def __int__(self):
        raise ValueError("boom")


class IntNone:
    __int__ = None


class IntIndex:
    def __int__(self):
        return 7

    def __index__(self):
        return 3


class Flt:
    def __float__(self):
        return 2.5


class FltBad:
    def __float__(self):
        return 2


class FltRaised:
    def __float__(self):
        raise ValueError("boom")


class FltNone:
    __float__ = None


class FltIndex:
    def __float__(self):
        return 2.5

    def __index__(self):
        return 3


class Both:
    def __int__(self):
        return 7

    def __float__(self):
        return 2.5


class Index:
    def __index__(self):
        return 3


class Wide:
    def __index__(self):
        return 2 ** 100


class Bad:
    def __index__(self):
        return 1.5


class Nothing:
    pass


class Texty:
    def __str__(self):
        return "12"

    def __len__(self):
        return 2


CONVERTERS = ["Int", "Minus", "IntBad", "IntRaised", "IntNone", "IntIndex", "Flt", "FltBad",
              "FltRaised", "FltNone", "FltIndex", "Both", "Index", "Wide", "Bad", "Nothing", "Texty"]

print("== int()")
for name in CONVERTERS:
    value = globals()[name]()
    print("int(%-10s)       " % name, said(lambda v=value: int(v)))
print("int(True)          ", said(lambda: int(True)))
print("int(2.7)           ", said(lambda: int(2.7)))
print("int(-2.7)          ", said(lambda: int(-2.7)))
print("int(float('nan'))  ", said(lambda: int(float("nan"))))
print("int(float('inf'))  ", said(lambda: int(float("inf"))))
print("int(float('-inf')) ", said(lambda: int(float("-inf"))))
print("int(1e300) > 0     ", said(lambda: int(1e300) > 0))

print("== what int() hands back")
BIG = 2 ** 70
print("int(BIG) is BIG    ", said(lambda: int(BIG) is BIG))
print("type(int(True))    ", said(lambda: type(int(True)).__name__))
print("int(Int()) + 1     ", said(lambda: int(Int()) + 1))
print("int(IntIndex())    ", said(lambda: int(IntIndex())))
print("int(Wide()) bitlen ", said(lambda: int(Wide()).bit_length()))

print("== float()")
for name in CONVERTERS:
    value = globals()[name]()
    print("float(%-8s)      " % name, said(lambda v=value: float(v)))
print("float(2)           ", said(lambda: float(2)))
print("float(True)        ", said(lambda: float(True)))
print("float(1j)          ", said(lambda: float(1j)))
print("float('1.5')       ", said(lambda: float("1.5")))
print("float(b'1.5')      ", said(lambda: float(b"1.5")))
print("float(Wide())      ", said(lambda: float(Wide())))
print("float(2 ** 10000)  ", said(lambda: float(2 ** 10000)))
print("float(10 ** 400)   ", said(lambda: float(10 ** 400)))

print("== what float() hands back")
print("float(f) is f      ", said(lambda: (lambda f: float(f) is f)(1.5)))
print("float(2) == 2.0    ", said(lambda: float(2) == 2.0))
print("repr(float('-0'))  ", said(lambda: repr(float("-0"))))

print("== complex()")
for name in CONVERTERS:
    value = globals()[name]()
    print("complex(%-6s)    " % name, said(lambda v=value: complex(v)))
    print("complex(1, %-6s)" % name, said(lambda v=value: complex(1, v)))
    print("complex(%-6s, 1)" % name, said(lambda v=value: complex(v, 1)))
print("complex(1, 2)      ", said(lambda: complex(1, 2)))
print("complex('1+2j')    ", said(lambda: complex("1+2j")))
print("complex(z) is z    ", said(lambda: (lambda z: complex(z) is z)(1j)))
print("complex(real=Int())", said(lambda: complex(real=Int())))
print("complex(0, Flt())  ", said(lambda: complex(0, Flt())))
print("complex(imag=Int())", said(lambda: complex(imag=Int())))
print("complex(real='1')  ", said(lambda: complex(real="1")))
print("complex('1', 1)    ", said(lambda: complex("1", 1)))
print("complex(real=Int(), imag=Flt())", said(lambda: complex(real=Int(), imag=Flt())))

print("== %d, which rewords a TypeError")
for name in CONVERTERS:
    value = globals()[name]()
    print("'%%d' %% %-10s  " % name, said(lambda v=value: "%d" % v))
print("'%d' % 2.7         ", said(lambda: "%d" % 2.7))
print("'%d' % -2.5        ", said(lambda: "%d" % -2.5))
print("'%d' % float('nan')", said(lambda: "%d" % float("nan")))
print("'%d' % float('inf')", said(lambda: "%d" % float("inf")))
print("'%d' % True        ", said(lambda: "%d" % True))
print("'%d' % '12'        ", said(lambda: "%d" % "12"))
print("'%d' % 1j          ", said(lambda: "%d" % 1j))
print("'%i' % Int()       ", said(lambda: "%i" % Int()))
print("'%u' % Int()       ", said(lambda: "%u" % Int()))
print("'%5d' % Int()      ", said(lambda: "%5d" % Int()))
print("'%+d' % FltIndex() ", said(lambda: "%+d" % FltIndex()))
print("'%.2f' % FltIndex()", said(lambda: "%.2f" % FltIndex()))

print("== %x, which wants an index")
for name in CONVERTERS:
    value = globals()[name]()
    print("'%%x' %% %-10s  " % name, said(lambda v=value: "%x" % v))
print("'%x' % 2.7         ", said(lambda: "%x" % 2.7))
print("'%#x' % Index()    ", said(lambda: "%#x" % Index()))
print("'%08x' % Wide()    ", said(lambda: "%08x" % Wide()))
print("'%(a)x' % {'a': Int()}", said(lambda: "%(a)x" % {"a": Int()}))

print("== %f, which rewords nothing")
for name in CONVERTERS:
    value = globals()[name]()
    print("'%%f' %% %-10s  " % name, said(lambda v=value: "%f" % v))
print("'%f' % 1j          ", said(lambda: "%f" % 1j))
print("'%f' % '1.5'       ", said(lambda: "%f" % "1.5"))
print("'%.3f' % Flt()     ", said(lambda: "%.3f" % Flt()))
print("'%.1e' % FltIndex()", said(lambda: "%.1e" % FltIndex()))
print("'%g' % Wide()      ", said(lambda: "%g" % Wide()))
print("'%c' % FltIndex()  ", said(lambda: "%c" % FltIndex()))

print("== the rows the tower files")
print("(5).__int__()      ", said(lambda: (5).__int__()))
print("(5).__float__()    ", said(lambda: (5).__float__()))
print("(5).__index__()    ", said(lambda: (5).__index__()))
print("True.__int__()     ", said(lambda: True.__int__()))
print("2.7.__int__()      ", said(lambda: (2.7).__int__()))
print("2.7.__float__()    ", said(lambda: (2.7).__float__()))
print("1j has no __int__  ", said(lambda: getattr(1j, "__int__", "none")))
print("1j has no __float__", said(lambda: getattr(1j, "__float__", "none")))
print("int.__float__(2)   ", said(lambda: int.__float__(2)))
print("float.__int__(2.7) ", said(lambda: float.__int__(2.7)))
print("Int.__int__(Int()) ", said(lambda: Int.__int__(Int())))
print("hasattr(int, '__int__')", said(lambda: hasattr(int, "__int__")))
print("hasattr(float, '__index__')", said(lambda: hasattr(float, "__index__")))

print("== the readers nothing reaches")
for name in ("Int", "Flt", "Both", "Index"):
    value = globals()[name]()
    print("abs(%-8s)      " % name, said(lambda v=value: abs(v)))
    print("round(%-6s)    " % name, said(lambda v=value: round(v)))
    print("1.0 + %-7s " % name, said(lambda v=value: 1.0 + v))
    print("format(%-4s, 'd')" % name, said(lambda v=value: format(v, "d")))
    print("%-4s == 1.0     " % name, said(lambda v=value: v == 1.0))
