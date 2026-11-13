"""The two recipes `object.__reduce_ex__` writes, and the slot wrappers a class may file a dunder with.

`object.__reduce_ex__` answers in one of two shapes.  Below protocol 2 the work is handed to
`copyreg._reduce_ex`, whose recipe names the first class in the order that is not a heap type; from
protocol 2 on it builds `copyreg.__newobj__` or, when `__getnewargs_ex__` supplied keywords,
`copyreg.__newobj_ex__`, and fills the five positions of the tuple with the arguments, the state, and
the items a list or dict keeps.  The state itself has two halves when a class declares `__slots__`:
the instance dictionary and the slots that were set, and only the halves that exist are reported.

The second half of the file is dispatch.  A right operand whose type is a proper subclass of the left
one and which writes the reflected method is asked before the left operand's slot, so `1 // B(1)`
finds `B.__rfloordiv__` rather than staying with `int`; the rule is the same for the modulus form of
`pow`.  A class may also file a wrapper descriptor under a dunder — `__neg__ = dict.copy` — which is
reached through its descriptor rather than as a plain function, and one of the wrong kind of value
refuses with a `TypeError` instead of being ignored.
"""


def normalize(label, value, proto):
    """The reduce tuple's five positions, with its iterator positions walked out."""
    recipe = tuple(value.__reduce_ex__(proto)) + (None, None, None)
    items = recipe[3] if recipe[3] is None else list(recipe[3])
    pairs = recipe[4] if recipe[4] is None else list(recipe[4])
    print(label, proto, recipe[0].__name__, repr(recipe[1]), repr(recipe[2]),
          repr(items), repr(pairs))


class Plain:
    pass


class Args:
    def __init__(self, a, b):
        self.a = a
        self.b = b

    def __getnewargs__(self):
        return (self.a, self.b)

    def __getstate__(self):
        return {"seen": (self.a, self.b)}


class Pair:
    def __init__(self, a, b, scale=1):
        self.a = a
        self.b = b
        self.scale = scale

    def __getnewargs_ex__(self):
        return ((self.a, self.b), {"scale": self.scale})


class Slotted:
    __slots__ = ("a", "b")

    def __init__(self, a, b):
        self.a = a
        self.b = b


class SlottedOne(Slotted):
    __slots__ = ()

    def __init__(self, a):
        self.a = a


class Listed(list):
    pass


class Mapped(dict):
    pass


plain = Plain()
plain.tag = "t"
args = Args(1, 2)
pair = Pair(3, 4, scale=5)
slotted = Slotted(6, 7)
one = SlottedOne(8)
listed = Listed((9, 10))
mapped = Mapped(left=11)

# Protocol 0 and 1 hand the walk to `copyreg._reduce_ex`; protocol 2 is the five-part recipe.
for proto in (0, 1, 2):
    normalize("plain", plain, proto)
    normalize("args", args, proto)
    normalize("pair", pair, proto)
    normalize("listed", listed, proto)
    normalize("mapped", mapped, proto)

for proto in (2,):
    normalize("slotted", slotted, proto)
    normalize("one", one, proto)


# Below protocol 2 the slots cannot travel without a `__getstate__` to say which ones were set, and
# the delegation refuses in `copyreg`'s own words.
try:
    Slotted(6, 7).__reduce_ex__(0)
except TypeError as error:
    print("slotted proto 0", type(error).__name__, error)


# `object.__getstate__` is the two-part answer itself, with the dictionary half reported as None when
# the instance has nothing in it.
print("state plain", repr(object.__getstate__(plain)))
print("state empty", repr(object.__getstate__(Plain())))
print("state slotted", repr(object.__getstate__(slotted)))
print("state listed", repr(object.__getstate__(listed)))


# A class whose `__getattr__` refuses every name still inherits the pickle helpers, and a class-level
# `__slotnames__` list stands in for the slots the layout does not carry.
class Hidden:
    __slotnames__ = ["spam"]

    def __getattr__(self, name):
        if name == "spam":
            return 42
        raise AttributeError


normalize("hidden", Hidden(), 2)
print("hidden dict", repr(Hidden().__dict__))


# The right operand is a proper subclass and writes the reflected method, so it is asked first.
class Plus(int):
    def __repr__(self):
        return "Plus(%r)" % int(self)

    def __add__(self, other):
        return Plus(int(self) + int(other))

    __radd__ = __add__


class Floor(int):
    def __rfloordiv__(self, other):
        return "Floor.__rfloordiv__"


class Left:
    def __floordiv__(self, other):
        return "Left.__floordiv__"

    def __rfloordiv__(self, other):
        return "Left.__rfloordiv__"


class Right(Left):
    def __floordiv__(self, other):
        return "Right.__floordiv__"

    def __rfloordiv__(self, other):
        return "Right.__rfloordiv__"


print("add", repr(Plus(1) + Plus(2)), repr(Plus(1) + 2), repr(1 + Plus(2)))
print("add type", type(1 + Plus(2)).__name__)
print("floor", 1 // Floor(1))
print("floor both", Left() // Right(), Right() // Left(), Left() // Left())


# The three-argument power is the ternary slot: the modulus travels as a third argument, and the same
# right-subclass rule decides who answers.
class Power(int):
    def __pow__(self, other, modulus=None):
        return Power(pow(int(self), int(other), modulus))

    def __rpow__(self, other, modulus=None):
        return "Power.__rpow__(%r, %r)" % (other, modulus)


print("pow plain", repr(pow(2, 3, 5)), repr(2 ** Power(3)))
print("pow", repr(pow(Power(2), Power(3), Power(5))), pow(2, Power(3), 5))


# A wrapper descriptor filed under a dunder is bound to the value and called; the two `dict` methods
# work on a `dict` subclass because that is what the subclass carries.
class Windows(dict):
    __setattr__ = dict.__setitem__
    __neg__ = dict.copy


window = Windows()
window.y = 42
print("window", repr(window), window == dict(y=42), repr(-window), type(-window).__name__)


# A wrapper descriptor of the wrong kind of value refuses rather than being skipped, so identity and
# the identity hash do not stand in for the comparison.
class Wrong:
    __eq__ = str.__eq__
    __hash__ = str.__hash__


wrong = Wrong()
for label, call in (("eq", lambda: wrong == wrong), ("hash", lambda: hash(wrong))):
    try:
        print("wrong", label, call())
    except TypeError as error:
        print("wrong", label, type(error).__name__)
