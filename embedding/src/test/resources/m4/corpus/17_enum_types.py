# Subclassing an immutable built-in is a value with a dictionary beside it: `int` and `str` fix the
# value in `__new__`, `__init__` still runs afterwards, and the inherited slots answer in the
# subclass's name where CPython's do. `enum` is built on exactly that, so the second half pins one
# real Enum/IntEnum/StrEnum/Flag family: values and repr, iteration order, and the identity of a
# member with itself.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

from enum import Enum, IntEnum, StrEnum, Flag, IntFlag, auto

# --- int and str as bases ---------------------------------------------------------------


class Meter(int):
    pass


m = Meter(5)
print("int subclass", m, repr(m), type(m).__name__, isinstance(m, int))
print("value", int(m), m == 5, 5 == m, hash(m) == hash(5))
print("arithmetic", m + 1, type(m + 1).__name__, m * 2, type(-m).__name__)
m.label = "metres"
print("attributes", m.label, sorted(vars(m)))

print("int() exact", type(int(m)).__name__, int(m) == 5)


class Tag(str):
    pass


t = Tag("abc")
print("str subclass", t, repr(t), type(t).__name__, isinstance(t, str))
print("methods", t.upper(), type(t.upper()).__name__, t.replace("b", "x"), len(t))
print("slices", t[1:], type(t[1:]).__name__, t + "d", type(t + "d").__name__)
t.owner = "me"
print("attributes", t.owner, sorted(vars(t)))

print("str() exact", type(str(t)).__name__, str(t) == "abc")


class Fixed(int):
    def __new__(cls, value):
        print("new", cls.__name__, value)
        return super().__new__(cls, value * 2)

    def __init__(self, value):
        print("init", int(self), value)
        self.seen = value


f = Fixed(3)
print("new fixes, init sees", f, type(f).__name__, f.seen, int(f))


class Shout(str):
    def __new__(cls, value):
        return super().__new__(cls, value.upper())

    def __init__(self, value):
        self.original = value


s = Shout("quiet")
print("new then init", s, s.original, type(s).__name__)

# --- several bases, and the order arguments travel in -----------------------------------


class Mixin:
    def describe(self):
        return "mixin:" + type(self).__name__


class Number(int, Mixin):
    pass


n = Number(4)
print("mro", [c.__name__ for c in Number.__mro__])
print("both halves", n, n + 1, type(n + 1).__name__, n.describe())


class Counting(int):
    def __new__(cls, *values):
        print("Counting.__new__", values)
        return super().__new__(cls, values[0] if values else 0)


class Forward(Counting, Mixin):
    def __new__(cls, *values):
        print("Forward.__new__", values)
        return super().__new__(cls, *values)


g = Forward(9)
print("forward", g, type(g).__name__, g.describe())


# --- a real Enum ------------------------------------------------------------------------


class Color(Enum):
    RED = 1
    GREEN = 2
    BLUE = 3


print("members", [(c.name, c.value, str(c), repr(c)) for c in Color])
print("order", [c.name for c in Color])
print("identity", Color.RED is Color(1), Color(1) == Color.RED, Color.RED == 1)
print("lookup", Color["GREEN"], Color(3), Color.RED.name, Color.RED.value)
print("members map", list(Color.__members__))
print("class", repr(Color), Color.__name__, type(Color).__name__)
print("membership", Color.RED in Color, 1 in Color)

try:
    Color(9)
except ValueError as exc:
    print("missing", type(exc).__name__, exc)


class Shade(Enum):
    A = 1
    B = 1
    C = auto()
    D = auto()


print("alias", Shade.B is Shade.A, Shade.A.name, [member.name for member in Shade])
print("alias map", list(Shade.__members__))
print("alias lookup", Shade(1), Shade["B"])


# --- IntEnum: a member that is also int -------------------------------------------------


class Size(IntEnum):
    SMALL = 1
    LARGE = 3


print("intenum", Size.SMALL, repr(Size.SMALL), str(Size.SMALL))
print("intenum exact", int(Size.SMALL), type(int(Size.SMALL)).__name__, type(Size.SMALL).__name__)
print("intenum ops", Size.SMALL + 1, Size.LARGE > Size.SMALL, Size.SMALL == 1)
print("intenum int-ness", isinstance(Size.SMALL, int), hash(Size.SMALL) == hash(1))
print("intenum order", [member.name for member in Size])


# --- StrEnum: the same for str ----------------------------------------------------------


class Level(StrEnum):
    LOW = auto()
    HIGH = auto()


print("strenum", str(Level.LOW), repr(Level.LOW), type(str(Level.LOW)).__name__)
print("strenum equal", Level.LOW == "low", Level.LOW.upper(), Level.LOW + "!")


# --- Flag and IntFlag -------------------------------------------------------------------


class Perm(Flag):
    READ = auto()
    WRITE = auto()
    EXEC = auto()


print("flag order", [member.name for member in Perm])
print("flag values", [(member.name, member.value) for member in Perm])
combo = Perm.READ | Perm.WRITE
print("flag combo", combo, repr(combo), int(combo))
print("flag contains", Perm.READ in combo, Perm.EXEC in combo)
print("flag bool", bool(Perm.READ), bool(Perm(0)), Perm(0))


class Mask(IntFlag):
    X = 1
    Y = 2


print("intflag", Mask.X | Mask.Y, int(Mask.X | Mask.Y), repr(Mask.X | Mask.Y))
print("intflag order", [member.name for member in Mask])
