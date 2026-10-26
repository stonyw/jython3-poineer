"""The thirteen members ``float`` files, the two properties that come with them, and what each refuses.

Thirteen rows of ``float.__dict__`` are not slots but functions: ``float_methods`` names them and
``PyDescr`` files ten as ``method_descriptor`` and three as ``classmethod_descriptor`` — ``from_number``,
``fromhex`` and ``__getformat__`` are the three that are called on the class and handed the class as their
first argument. Two more rows are ``getset_descriptor``, ``real`` and ``imag``, and those four — two
readers, no writers — are the whole of ``float`` as a number with a shape.

Three of the thirteen answer a question about the value rather than about arithmetic. ``is_integer`` asks
whether the double has no fractional part, and answers ``False`` for both infinities and both ``nan``
rather than refusing: the C function compares the value against its own truncated self and lets the
specials fall through as they fall. ``as_integer_ratio`` answers the pair of integers that is *exactly* the
double, in lowest terms and with a positive denominator, and is the only member that refuses the specials
at all — ``OverflowError`` on an infinity, ``ValueError`` on a ``nan`` — because there is no integer to
name one. ``hex`` answers the same exactness in one string: sixteen hexadecimal digits, the point, and the
power of two the value really is, so ``0.1.hex()`` says ``0x1.999999999999ap-4`` and never ``0x1.8p-4``,
which is the difference between a spelling and a value. ``fromhex`` is its inverse and its own grammar:
optional whitespace, optional sign, ``0x``, a hexadecimal coefficient with a point somewhere in it, and an
exponent written with a ``p`` that is a power of *two*; the words ``inf``, ``infinity``, ``nan`` in either
case are also read, and anything else is one sentence, ``invalid hexadecimal floating-point string``.

``__round__`` is the one member with two shapes, and the second is the built-in's. Without a place it answers
an ``int`` by rounding half away from zero in C and then correcting the tie toward even, so ``round(2.5)`` is
2 and ``round(3.5)`` is 4. With a place it answers a ``float`` — even at ``ndigits=0``, where the answer is
``2.0`` — and the place is read as a machine integer, so a place too large for one is clipped rather than
refused, and the two clip ends are where the interesting answers live: above ``323`` no double has that
many places, so the value comes back unchanged, and below ``-308`` the answer is ``0.0`` times the value,
which keeps the sign of ``-1e-320`` while emptying it. A place that is not an integer is a different refusal
again — ``'str' object cannot be interpreted as an integer`` — because the slot asks ``PyNumber_AsSsize_t``
and not ``CONVERT_TO_DOUBLE``.

``round()`` is one lookup and no branches: ``__round__`` on the value's *own type*, then the place as its only
argument where one was named and no argument at all where the place was ``None``. Every answer this case prints
for the built-in comes from that. It reaches ``float.__round__`` and ``int.__round__`` exactly as their type
dictionaries file them, so a ``bool`` is rounded by ``int``'s method and the answer is an ``int``, and a place
that is not an integer is refused by the method underneath rather than by the built-in. It walks the type and
never the instance dictionary, so a value that keeps a ``__round__`` of its own under the name is still rounded
by its class. It refuses only where the lookup came back empty, so ``__round__ = None`` is not a refusal but a
*call*, and the sentence is the one about ``'NoneType' object is not callable``; where the name is really
missing, the complaint cuts the type's name at a hundred characters, because that is what the formatter is told
to do with it. And whatever the name holds is read through its own ``__get__`` before it is called, which is
why a class method is handed no receiver, a static method is handed only the place, a property is called
without being asked, and a ``float.__round__`` filed under another class's name refuses that class in the
descriptor's own words. The receiver is asked before the place is read, so ``round("x", "y")`` complains about
``__round__`` and not about ``str``.

What this case does not print is a user class that answers ``__float__`` or ``__index__``: ``from_number``
would have to fall back through ``PyFloat_AsDouble`` to reach it, and this runtime has no such fallback, so
``float.from_number(WithFloat())`` refuses here where 3.14.8 answers 2.5.
"""


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


def each(label, items, apply):
    """One line per item, so a refusal answers for itself instead of masking the items after it."""
    for index, item in enumerate(items):
        print(probe("%s[%d] %r" % (label, index, item), lambda item=item: apply(item)))


OWNED = float.__dict__
ORDER = list(OWNED)
HUGE = 10 ** 400
INF = float("inf")
NAN = float("nan")
TINY = 5e-324
TEXT = "a"
VALUE = 1234.5
MEMBERS = ("from_number", "conjugate", "__trunc__", "__floor__", "__ceil__", "__round__",
           "as_integer_ratio", "fromhex", "hex", "is_integer", "__getnewargs__", "__getformat__",
           "__format__")


def member(name):
    """One of the thirteen rows, called through the table rather than through the class."""
    return OWNED[name]


def kind(value):
    return type(value).__name__


print("== the thirteen rows and the three that are called on the class ==")
show("names", lambda: [n for n in MEMBERS if n not in OWNED])
show("order", lambda: ORDER[31:44] == list(MEMBERS))
show("kinds", lambda: [kind(member(n)) for n in MEMBERS])
show("the three class rows", lambda: [n for n in MEMBERS if kind(member(n)) == "classmethod_descriptor"])
show("qualnames", lambda: sorted({member(n).__qualname__.split(".")[0] for n in MEMBERS}))
show("objclasses", lambda: sorted({member(n).__objclass__.__name__ for n in MEMBERS}))
show("every member is called through the class too", lambda: [n for n in MEMBERS if getattr(float, n) is member(n)])

print("== from_number, the one that reads a real ==")
show("of an int", lambda: (member("from_number")(float, 3), kind(member("from_number")(float, 3))))
show("of a real", lambda: member("from_number")(float, VALUE) is VALUE)
show("of a bool", lambda: member("from_number")(float, True))
show("of text", lambda: member("from_number")(float, TEXT))
show("of bytes", lambda: member("from_number")(float, b"1"))
show("of none", lambda: member("from_number")(float, None))
show("of a complex", lambda: member("from_number")(float, 1j))
show("of a huge int", lambda: member("from_number")(float, HUGE))
show("of a big whole", lambda: member("from_number")(float, 2 ** 70))
show("through the class", lambda: float.from_number(3))
show("through a value", lambda: (1.5).from_number(3))
show("of nothing", lambda: member("from_number")(float))
show("of two", lambda: member("from_number")(float, 1, 2))
show("by keyword", lambda: member("from_number")(float, number=1))
show("with a str as the class", lambda: member("from_number")("a", 1))
show("asked of int", lambda: member("from_number")(int, 1))
show("inherited by an int", lambda: hasattr(int, "from_number"))

print("== conjugate, which is a float's own ==")
show("keeps the receiver", lambda: member("conjugate")(VALUE) is VALUE)
show("of a negative", lambda: member("conjugate")(-1.5))
show("of the specials", lambda: [member("conjugate")(INF), member("conjugate")(NAN) != NAN, member("conjugate")(-0.0)])
show("of zero", lambda: member("conjugate")(0.0))
show("through a value", lambda: VALUE.conjugate() is VALUE)
show("of an int receiver", lambda: member("conjugate")(1))
show("with an extra", lambda: member("conjugate")(1.5, 1))
show("by keyword", lambda: member("conjugate")(1.5, x=1))

print("== the three that answer an integer between, below or above ==")
show("trunc", lambda: [member("__trunc__")(v) for v in (1.7, -1.7, 0.5, -0.5, 2.0)])
show("trunc kinds", lambda: kind(member("__trunc__")(1.7)))
show("floor", lambda: [member("__floor__")(v) for v in (1.7, -1.7, 0.5, -0.5, 2.0)])
show("ceil", lambda: [member("__ceil__")(v) for v in (1.7, -1.7, 0.5, -0.5, -2.0)])
show("the three on a whole", lambda: [(lambda v: [member(n)(v) for n in ("__trunc__", "__floor__", "__ceil__")])(2.0)])
show("trunc of minus zero", lambda: repr(member("__trunc__")(-0.0)))
show("floor of minus zero", lambda: repr(member("__floor__")(-0.0)))
show("trunc of the specials", lambda: [member("__trunc__")(INF), member("__floor__")(INF), member("__ceil__")(-INF)])
show("floor of a nan", lambda: member("__floor__")(NAN))
show("trunc of a big whole", lambda: member("__trunc__")(2.0 ** 70))
show("through math-free values", lambda: [(1.7).__trunc__(), (-1.7).__floor__(), (1.2).__ceil__()])
show("trunc of an int receiver", lambda: member("__trunc__")(1))
show("trunc with an extra", lambda: member("__trunc__")(1.5, 1))
show("trunc by keyword", lambda: member("__trunc__")(1.5, x=1))

print("== round without a place, which answers an int ==")
show("the ties go even", lambda: [member("__round__")(v) for v in (0.5, 1.5, 2.5, 3.5, -0.5, -1.5, -2.5)])
show("kinds", lambda: kind(member("__round__")(1.5)))
show("the near misses", lambda: [member("__round__")(v) for v in (1.49999, 1.50001, -1.49999, 0.0)])
show("of minus zero", lambda: [member("__round__")(-0.0), repr(member("__round__")(-0.0))])
show("of a whole", lambda: member("__round__")(1234.0))
show("of the extremes", lambda: [repr(member("__round__")(1e308)), member("__round__")(INF), member("__round__")(NAN)])
show("of a big whole", lambda: member("__round__")(2.0 ** 70))
show("through the value", lambda: VALUE.__round__())

print("== round with a place, which answers a float ==")
show("of a None place", lambda: (member("__round__")(1.5, None), kind(member("__round__")(1.5, None))))
show("of a zero place", lambda: [repr(member("__round__")(1.5, 0)), repr(member("__round__")(2.5, 0))])
show("one place", lambda: [member("__round__")(1.25, 1), member("__round__")(1.49999, 1), member("__round__")(0.15, 1), member("__round__")(0.25, 1)])
show("two places on a tie that is not one", lambda: [member("__round__")(2.675, 2), member("__round__")(0.125, 2), member("__round__")(0.375, 2), member("__round__")(1.005, 2)])
show("a negative place", lambda: [member("__round__")(1234.0, -2), member("__round__")(123.456, -2), member("__round__")(543.21, -3)])
show("a place past the point", lambda: [member("__round__")(1.5, 20), member("__round__")(1.5, 323), member("__round__")(1.5, 324)])
show("a place past the other end", lambda: [repr(member("__round__")(1.5, -308)), repr(member("__round__")(1.5, -309)), member("__round__")(1.5, -1000)])
show("a place clipped from a big int", lambda: [member("__round__")(1.5, 2 ** 70), repr(member("__round__")(1.5, -(2 ** 70)))])
show("the sign of an emptied value", lambda: [repr(member("__round__")(-0.0, 1)), repr(member("__round__")(-1e-320, 2)), repr(member("__round__")(1e-320, 2)), repr(member("__round__")(-0.04, 1))])
show("a zero times the sign", lambda: [repr(member("__round__")(0.0, -3)), repr(member("__round__")(-0.0, -3))])
show("the overflow at the end", lambda: [member("__round__")(1.7976931348623157e308, -308), member("__round__")(1e308, -308), member("__round__")(-1e308, -308)])
show("of the specials with a place", lambda: [member("__round__")(INF, 1) == INF, member("__round__")(NAN, 1) != NAN, member("__round__")(TINY, 2)])
show("the answer is a float of its own", lambda: (member("__round__")(VALUE, 1), repr(member("__round__")(VALUE, 20))))
show("a bool place", lambda: member("__round__")(1.25, True))
show("a text place", lambda: member("__round__")(1.5, TEXT))
show("a float place", lambda: member("__round__")(1.5, 1.0))
show("bytes place", lambda: member("__round__")(1.5, b"1"))
show("of two places", lambda: member("__round__")(1.5, 1, 2))
show("by keyword", lambda: member("__round__")(1.5, ndigits=1))
show("through the value", lambda: VALUE.__round__(1))

print("== the built-in round reaching the same code ==")
show("plain", lambda: [round(1.5), round(2.5), round(-0.5), round(123.456)])
show("with a place", lambda: [round(1.5, 1), round(2.675, 2), round(1234.0, -2)])
show("with a None place", lambda: (round(1.5, None), kind(round(1.5, None))))
show("kinds", lambda: [kind(round(1.5)), kind(round(1.5, 1)), kind(round(2 ** 70, 1))])
show("an int keeps its kind", lambda: [kind(round(3)), round(5, -1)])
show("a bool rounds to an int", lambda: [round(True), round(True, 2), kind(round(True))])
show("the receiver is refused first", lambda: round(TEXT, TEXT))
show("and without a place too", lambda: round(TEXT))
show("a bad place after a good receiver", lambda: round(1.5, TEXT))
show("a bad place on an int", lambda: round(5, TEXT))
show("a bad place on a bool", lambda: round(True, TEXT))
show("a float place on a bool", lambda: round(True, 1.5))
show("by keyword", lambda: round(1.5, ndigits=2))
show("too many", lambda: round(1.5, 2, 3))
show("the sign of a built-in zero", lambda: [repr(round(-0.0, 1)), repr(round(-1e-320, 2))])
show("the overflow of a built-in", lambda: round(1.7976931348623157e308, -308))

print("== the built-in round asking a class that answers ==")
class Answers:
    def __round__(self):
        return "the class"


class WantsPlace:
    def __round__(self, place):
        return ["the place", place]


class EitherPlace:
    def __round__(self, place=None):
        return ["either", place]


class Silent:
    __round__ = None


class Five:
    __round__ = 5


class FloatsRound:
    __round__ = float.__round__


class IntsRound:
    __round__ = int.__round__


class AsStatic:
    __round__ = staticmethod(lambda *rest: ("static", rest))


class AsClassMethod:
    __round__ = classmethod(lambda cls: cls.__name__)


class AsProperty:
    __round__ = property(lambda self: 7)


class Inherits(Answers):
    pass


class Overrides(Answers):
    def __round__(self):
        return "the subclass"


class PassesValue:
    def __round__(self):
        return VALUE


class ExceptionAnswers(BaseException):
    def __round__(self):
        return "raised"


class VeryLongNameForARoundRefusalThatGoesOnPastTheHundredCharactersTheFormatterIsToldToShowOfAndSoHasToBeCutSomewhereInTheMiddle:
    pass


def nested():
    class Inner:
        pass

    return Inner()


shadowed = Answers()
shadowed.__dict__["__round__"] = lambda: "the dictionary"

show("no place", lambda: round(Answers()))
show("with a place", lambda: round(Answers(), 2))
show("with a None place", lambda: round(Answers(), None))
show("the place arrives", lambda: round(WantsPlace(), 3))
show("the place is missing", lambda: round(WantsPlace()))
show("both forms answer", lambda: [round(EitherPlace()), round(EitherPlace(), 1)])
show("the place by keyword", lambda: round(EitherPlace(), ndigits="x"))
show("inherited", lambda: round(Inherits()))
show("overridden", lambda: round(Overrides()))
show("an exception class", lambda: round(ExceptionAnswers()))
show("the instance dictionary is not asked", lambda: round(shadowed))
show("an attribute read is", lambda: shadowed.__round__())
show("a None is called", lambda: round(Silent()))
show("a None with a place", lambda: round(Silent(), 1))
show("a number is called", lambda: round(Five()))
show("a float method on another class", lambda: round(FloatsRound()))
show("and with a place", lambda: round(FloatsRound(), 1))
show("an int method on another class", lambda: round(IntsRound()))
show("a static method", lambda: round(AsStatic()))
show("a static method with a place", lambda: round(AsStatic(), 2))
show("a class method", lambda: round(AsClassMethod()))
show("a class method with a place", lambda: round(AsClassMethod(), 1))
show("a property is not asked", lambda: round(AsProperty()))
show("the answer is the method's own", lambda: (lambda w: round(w) is VALUE)(PassesValue()))

print("== the built-in round naming a class that does not answer ==")
show("a list", lambda: round([]))
show("a dict", lambda: round({}))
show("a set", lambda: round({1}))
show("a function", lambda: round(probe))
show("a class", lambda: round(Answers))
show("a nested class, as its name is filed", lambda: round(nested()))
show("a name cut at a hundred", lambda: round(VeryLongNameForARoundRefusalThatGoesOnPastTheHundredCharactersTheFormatterIsToldToShowOfAndSoHasToBeCutSomewhereInTheMiddle()))
show("the same with a place", lambda: round(VeryLongNameForARoundRefusalThatGoesOnPastTheHundredCharactersTheFormatterIsToldToShowOfAndSoHasToBeCutSomewhereInTheMiddle(), 2))
show("and the name it cut off", lambda: len(VeryLongNameForARoundRefusalThatGoesOnPastTheHundredCharactersTheFormatterIsToldToShowOfAndSoHasToBeCutSomewhereInTheMiddle.__name__))

print("== as_integer_ratio ==")
show("of a whole", lambda: member("as_integer_ratio")(10.0))
show("of a quarter", lambda: member("as_integer_ratio")(0.25))
show("of zero", lambda: member("as_integer_ratio")(0.0))
show("of minus zero", lambda: member("as_integer_ratio")(-0.0))
show("of a negative", lambda: member("as_integer_ratio")(-0.75))
show("kinds", lambda: [kind(v) for v in member("as_integer_ratio")(0.25)])
show("of a tenth", lambda: member("as_integer_ratio")(0.1))
show("of a third", lambda: member("as_integer_ratio")(1 / 3))
show("of the smallest", lambda: member("as_integer_ratio")(TINY))
show("of the largest", lambda: member("as_integer_ratio")(1e308))
show("of a power", lambda: member("as_integer_ratio")(2.0 ** 1023))
show("of a subnormal", lambda: member("as_integer_ratio")(2.0 ** -1075))
show("it rebuilds exactly", lambda: [member("as_integer_ratio")(v)[0] / member("as_integer_ratio")(v)[1] == v for v in (0.1, 1e308, TINY, -0.75)])
show("the denominator is positive", lambda: [member("as_integer_ratio")(v)[1] > 0 for v in (0.1, -0.75, TINY, 1e308)])
show("of an infinity", lambda: [member("as_integer_ratio")(INF), member("as_integer_ratio")(-INF)])
show("of a nan", lambda: member("as_integer_ratio")(NAN))
show("of an int receiver", lambda: member("as_integer_ratio")(1))
show("with an extra", lambda: member("as_integer_ratio")(1.5, 1))
show("through the value", lambda: (0.25).as_integer_ratio())

print("== hex, and the fromhex that reads it back ==")
show("of a tenth", lambda: member("hex")(0.1))
show("of a negative tenth", lambda: member("hex")(-0.1))
show("of zero", lambda: [member("hex")(0.0), member("hex")(-0.0)])
show("of a whole", lambda: [member("hex")(1.0), member("hex")(65536.0), member("hex")(1e16)])
show("of the extremes", lambda: [member("hex")(TINY), member("hex")(1e308), member("hex")(2.0 ** 1023), member("hex")(1e-310)])
show("of a third", lambda: member("hex")(1 / 3))
show("of three quarters", lambda: [member("hex")(0.75), member("hex")(-0.75), member("hex")(0.5)])
show("of the specials", lambda: [member("hex")(INF), member("hex")(-INF), member("hex")(NAN), member("hex")(-NAN)])
show("of a big whole", lambda: [member("hex")(1234567890123456789.0), member("hex")(2.0 ** 70)])
show("of an int receiver", lambda: member("hex")(1))
show("with an extra", lambda: member("hex")(1.5, 1))
show("through the value", lambda: (3.14159).hex())
each("a plain hex", ("0x1.999999999999ap-4", "-0x1.999999999999ap-4"), lambda s: member("fromhex")(float, s))
each("whitespace around", (" 0x1.8p+1 ", "\t0x1\n", " -0x1p-1074 "), lambda s: member("fromhex")(float, s))
each("the shapes it reads", ("0x1.8p+1", "0x1", "0X1P4", "0x.8p0", "0x1.p+0", "0x000.1p+4", "+0x1", "-0x1"),
     lambda s: member("fromhex")(float, s))
each("without a point", ("0x123", "0x123p4"), lambda s: member("fromhex")(float, s))
# The prefix is optional, so a bare hexadecimal coefficient reads as one: 1 + 5/16, not one and a half.
each("with no prefix", ("1.5", "1.p", "ffff", "1.8p+1"), lambda s: member("fromhex")(float, s))
each("the words", ("inf", "Infinity", "+Infinity", "-infinity", "INF", "nan", "NaN", "-nan", "-nanx"),
     lambda s: member("fromhex")(float, s))
each("what it refuses", ("", "0x", "1.8p", "0x1z", "0x 1"), lambda s: member("fromhex")(float, s))
each("a half-written word", ("in", "n", "infinit", "infinityx", "nanx"), lambda s: member("fromhex")(float, s))
each("no exponent", ("0x1p", "0x1p+", "0x."), lambda s: member("fromhex")(float, s))
each("an underscore is not a digit", ("0x1_0", "0x1_p4"), lambda s: member("fromhex")(float, s))
each("out of reach", ("0x1p100000", "0x1p-100000", "0x1p1024"), lambda s: member("fromhex")(float, s))
each("the corner and the rounding",
     ("0x1.fffffffffffffp+1023", "0x1.fffffffffffff8p+1023", "0x1.00000000000004p0", "0x1.0000000000000c4p0"),
     lambda s: repr(member("fromhex")(float, s)))
each("the subnormal corner", ("0x0.00000000000008p-1022", "0x" + "0" * 100 + "1"),
     lambda s: repr(member("fromhex")(float, s)))
show("many digits", lambda: member("fromhex")(float, "0x1.23456789abcdef012345p+1"))
show("of bytes", lambda: member("fromhex")(float, b"0x1"))
each("of a number", (1, True, 1.0), lambda v: member("fromhex")(float, v))
show("with no string", lambda: member("fromhex")(float))
show("with two strings", lambda: member("fromhex")(float, "0x1", "0x2"))
show("by keyword", lambda: member("fromhex")(float, string="0x1"))
show("through the class", lambda: float.fromhex("0x1.8p+1"))
show("round trips", lambda: [member("fromhex")(float, member("hex")(v)) == v for v in (0.1, 1e308, TINY, -0.0, 1 / 3, -1e-310)])

print("== is_integer, getnewargs and the two that read the class ==")
show("is_integer", lambda: [member("is_integer")(v) for v in (2.0, 2.5, 0.0, -0.0, -3.0, 1e308, 2.0 ** 70)])
show("of the specials", lambda: [member("is_integer")(INF), member("is_integer")(-INF), member("is_integer")(NAN)])
show("kinds", lambda: [kind(member("is_integer")(2.0))])
show("of a subnormal", lambda: member("is_integer")(TINY))
show("of an int receiver", lambda: member("is_integer")(1))
show("with an extra", lambda: member("is_integer")(1.5, 1))
show("through the value", lambda: [(1.5).is_integer(), (2.0).is_integer()])
show("getnewargs", lambda: member("__getnewargs__")(1.5))
show("its one argument is the value", lambda: member("__getnewargs__")(VALUE)[0] is VALUE)
show("kinds", lambda: [kind(v) for v in member("__getnewargs__")(1.5)])
show("of nothing", lambda: member("__getnewargs__")())
show("with an extra", lambda: member("__getnewargs__")(1.5, 1))
show("rebuilds", lambda: float(*VALUE.__getnewargs__()) is VALUE)
show("a float has no args of its own", lambda: member("__getnewargs__")(0.0))

print("== getformat, which answers the machine ==")
show("double", lambda: member("__getformat__")(float, "double"))
show("float", lambda: member("__getformat__")(float, "float"))
show("the two names answer one thing", lambda: member("__getformat__")(float, "double") == member("__getformat__")(float, "float"))
show("it says IEEE", lambda: member("__getformat__")(float, "double").startswith("IEEE"))
show("through the class", lambda: float.__getformat__("double"))
show("another word", lambda: member("__getformat__")(float, "long"))
show("in another case", lambda: member("__getformat__")(float, "DOUBLE"))
show("an empty word", lambda: member("__getformat__")(float, ""))
show("of nothing", lambda: member("__getformat__")(float))
show("of two", lambda: member("__getformat__")(float, "double", "float"))
show("by keyword", lambda: member("__getformat__")(float, typestr="double"))
show("of bytes", lambda: member("__getformat__")(float, b"double"))
show("of a number", lambda: member("__getformat__")(float, 1))
show("of none", lambda: member("__getformat__")(float, None))
show("of a str as the class", lambda: member("__getformat__")(TEXT, "double"))

print("== format, which answers the value spelled ==")
show("the specs", lambda: [member("__format__")(VALUE, s) for s in ("", ".2f", ".2e", ">8.1f", "+.3%", "n", "010.3f", ".4g", "<12", "^11.2f", "=", "e", "E", "f", "%")])
show("the specials", lambda: [member("__format__")(INF, ""), member("__format__")(-INF, ".2e"), member("__format__")(NAN, "e"), member("__format__")(0.1, ".0f")])
show("of a zero place", lambda: [repr(member("__format__")(0.0, ".0f")), member("__format__")(TINY, ".0e"), member("__format__")(1e308, "g")])
show("of a bool", lambda: member("__format__")(VALUE, True))
show("of nothing", lambda: member("__format__")(VALUE))
show("of two specs", lambda: member("__format__")(VALUE, "", ""))
show("of a number", lambda: member("__format__")(VALUE, 3))
show("of bytes", lambda: member("__format__")(VALUE, b""))
show("of none", lambda: member("__format__")(VALUE, None))
show("a letter that is only a fill", lambda: member("__format__")(VALUE, "z"))
show("a fill with an align and no type", lambda: member("__format__")(VALUE, ">^"))
show("through the class", lambda: float.__format__(1.5, ".2f"))
show("through the value", lambda: format(VALUE, ",.2f"))
show("of an int receiver", lambda: member("__format__")(1, ".1f"))

print("== the two properties ==")
show("kinds", lambda: [kind(OWNED[n]) for n in ("real", "imag")])
show("names", lambda: [OWNED[n].__qualname__ for n in ("real", "imag")])
show("objclasses", lambda: [OWNED[n].__objclass__ is float for n in ("real", "imag")])
show("real is the receiver", lambda: OWNED["real"].__get__(VALUE) is VALUE)
show("imag answers a fresh zero", lambda: [OWNED["imag"].__get__(VALUE), kind(OWNED["imag"].__get__(VALUE))])
show("imag of a negative", lambda: [(-VALUE).imag, repr((-0.0).imag)])
show("two reads are two objects", lambda: (1.0).imag is not (2.0).imag)
show("the four reads on a value", lambda: [VALUE.real, VALUE.imag, (-VALUE).real, (-VALUE).imag])
show("of an int receiver", lambda: [OWNED[n].__get__(1) for n in ("real", "imag")])
show("of a str receiver", lambda: OWNED["real"].__get__(TEXT))
show("of a complex", lambda: OWNED["imag"].__get__(1j))
show("with no instance", lambda: OWNED["real"].__get__(None, float))
show("writing real", lambda: OWNED["real"].__set__(1.5, 1))
show("deleting real", lambda: OWNED["real"].__delete__(1.5))
show("writing through an attribute", lambda: setattr(VALUE, "real", 1.0))
show("imag is not writable either", lambda: (-VALUE).imag.__setattr__("real", 1.0))
show("no setter filed", lambda: [hasattr(OWNED[n], "__set__") for n in ("real", "imag")])
