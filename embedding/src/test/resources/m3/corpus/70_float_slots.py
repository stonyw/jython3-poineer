"""The thirty slots a ``float`` files, the ladder a mixed pair of numbers walks, and the constructor.

Thirty of the forty-seven rows of ``float.__dict__`` are ``wrapper_descriptor``: they are the C slots
``floatobject.c`` hands to ``float_as_number``, plus ``tp_repr``, ``tp_hash`` and the two conversions, and
calling one is the same work an operator does with one fewer step. Because they are wrappers they are also
the one place a slot can be asked directly, and the answers they give are not always the ones the operator
gives: ``float.__lt__(1.5, "a")`` declines with ``NotImplemented``, while ``1.5 < "a"`` is the ``TypeError``
that the interpreter builds out of two declines. Every one of the thirty gates on the receiver first and
names it when it refuses, so ``float.__add__("a", 1.5)`` complains that ``can't concat`` something that is
a ``str`` and ``float.__lt__("a", 1.5)`` says ``'<' not supported between instances of 'str' and 'float'``.

The six orderings are ``float_richcompare`` and its ladder, and the ladder is why a ``float`` and an ``int``
compare exactly. A pair of reals is four tests and nothing else. A real against an integer is first asked
whether the integer is small enough to be one of the two special cases — an infinity answers before anything
else does — and then compared without converting either side, because converting ``2 ** 53 + 1`` to a double
rounds it to ``2 ** 53`` and would make ``9007199254740992.0 < 2 ** 53 + 1`` false when the two differ by a
whole integer. A ``nan`` fails all four tests, which is why it is neither less nor greater nor equal to
itself, and only a pair that is neither of those kinds gets ``NotImplemented``. Reading the ladder through
one operator in one direction is not enough to pin it, because the slot on the left is what answers and
``2 < 1.5`` asks ``float.__gt__(1.5, 2)``: the reflected half of the same table.

The eight arithmetic pairs are the same two rules over again — ``CONVERT_TO_DOUBLE`` accepts a real, an
integer that fits and a ``bool``, and declines anything else, so a huge integer that does not fit is an
``OverflowError`` rather than a decline, while a ``str`` or a ``complex`` is a decline and never an error.
Power is the one wrapper that reads an optional third argument, and it reads it only in position: asking
for the modulus by name is the wrapper's own ``takes no keyword arguments``, and a fourth argument is
``expected 1 or 2 arguments, got 3``. ``pow(2.0, 3, 4)`` reaches the same slot because the built-in
``pow`` parses the call and hands the three along positionally.

What this case does not print is a user class that could be coerced: ``float.__add__(1.5, x)`` where ``x``
has ``__float__`` declines here because this runtime has no ``CONVERT_TO_DOUBLE`` fallback to ``__float__``
or ``__index__``, which is recorded as the same gap the constructors have, and the hash of a ``nan``, which
3.14.8 seeds per process.
"""


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


OWNED = float.__dict__
HUGE = 10 ** 400
INF = float("inf")
NAN = float("nan")
TEXT = "a"
VALUE = 1234.5


def kind(value):
    """The name of what an operator answered, which the pairs decide by type as much as by value."""
    return type(value).__name__


def slot(name):
    """The wrapper a table row files, called directly so no reflected half can step in."""
    return OWNED[name]


print("== the six orderings ==")
show("a real against a real", lambda: [slot(n)(1.5, 2.5) for n in ("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__")])
show("against itself", lambda: [slot(n)(1.5, 1.5) for n in ("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__")])
show("the two that a tie decides", lambda: [slot(n)(-0.0, 0.0) for n in ("__eq__", "__lt__", "__ne__")])
show("nan against itself", lambda: [slot(n)(NAN, NAN) for n in ("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__")])
show("nan against a real", lambda: [slot(n)(NAN, 1.0) for n in ("__lt__", "__eq__", "__gt__")])
show("the six answer their own question", lambda: [n for n in ("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__") if slot(n).__qualname__ != "float." + n])
show("kinds", lambda: [kind(slot(n)(1.5, 2.5)) for n in ("__lt__", "__eq__", "__ne__")])

print("== the ladder a real and an integer walk ==")
show("lt an int", lambda: slot("__lt__")(1.5, 2))
show("gt an int", lambda: slot("__gt__")(1.5, 2))
show("eq an int", lambda: [slot("__eq__")(1.0, 1), slot("__eq__")(1.5, 1), slot("__eq__")(2.0, 2)])
show("hash of a whole matches the int", lambda: slot("__hash__")(42.0) == hash(42))
show("the operators both ways", lambda: [(1.5 < 2, 2 < 1.5), (1.5 > 2, 2 > 1.5), (1.5 <= 2, 2 >= 1.5), (1.5 == 2, 2 == 1.5)])
show("an operator that is a tie", lambda: [(1.0 == 1, 1 == 1.0), (1.0 <= 1, 1 >= 1.0), (0.1 + 0.2 == 0.3)])
show("sorting mixes the two", lambda: sorted([1.5, 2, 1.25, 1, 2.5]))
show("a dict keys them apart", lambda: len({1.5: "a", 2: "b", 2.0: "c", 1: "d"}))
show("the exact compare of two to the fifty-three", lambda: [slot("__lt__")(9007199254740992.0, 2 ** 53 + 1), slot("__eq__")(9007199254740992.0, 2 ** 53)])
show("the same pair as an operator", lambda: (9007199254740992.0 < 2 ** 53 + 1, 2 ** 53 + 1 > 9007199254740992.0))
show("a whole power", lambda: [slot("__lt__")(2.0 ** 49, 2 ** 49), slot("__le__")(2.0 ** 49, 2 ** 49), slot("__eq__")(2.0 ** 49, 2 ** 49)])
show("an infinity answers first", lambda: [slot("__lt__")(INF, HUGE), slot("__gt__")(-INF, HUGE), slot("__lt__")(-INF, HUGE), slot("__eq__")(INF, HUGE)])
show("a real against a huge int", lambda: [slot("__lt__")(1.5, HUGE), slot("__gt__")(1.5, HUGE), slot("__eq__")(1.5, HUGE)])
show("the same two against a tiny int", lambda: [slot("__lt__")(1.5, -HUGE), slot("__gt__")(1.5, -HUGE)])
show("a bool is an int here", lambda: [slot("__lt__")(1.0, True), slot("__eq__")(1.0, True), slot("__gt__")(0.5, False)])
show("a str declines", lambda: [slot("__lt__")(1.5, TEXT) is NotImplemented, slot("__eq__")(1.5, TEXT) is NotImplemented])
show("a complex declines", lambda: [slot("__lt__")(1.0, 1j) is NotImplemented, slot("__eq__")(1.0, 1j) is NotImplemented])
show("the interpreter builds the sentence", lambda: 1.5 < TEXT)
show("and the other way round", lambda: TEXT < 1.5)
show("a decline the other way", lambda: 1j == 1.0)

print("== the eight arithmetic pairs ==")
for pair in ("add", "sub", "mul", "mod"):
    show("%s a real" % pair, lambda p=pair: (slot("__%s__" % p)(5.5, 2.5), slot("__r%s__" % p)(5.5, 2.5)))
    show("%s an int" % pair, lambda p=pair: (slot("__%s__" % p)(5.5, 2), kind(slot("__%s__" % p)(5.5, 2))))
    show("%s a bool" % pair, lambda p=pair: slot("__%s__" % p)(5.5, True))
    show("%s a huge int" % pair, lambda p=pair: slot("__%s__" % p)(5.5, HUGE))
    show("%s of a str declines" % pair, lambda p=pair: slot("__%s__" % p)(5.5, TEXT) is NotImplemented)
    show("%s with a str receiver" % pair, lambda p=pair: slot("__%s__" % p)(TEXT, 5.5))
show("the reflected half swaps", lambda: [slot("__rsub__")(5.5, 2), slot("__rsub__")(2, 5.5), slot("__rtruediv__")(4.0, 2), slot("__rmod__")(5.5, 2)])
show("negative remainder", lambda: [slot("__mod__")(-5.5, 2.0), slot("__mod__")(5.5, -2.0), slot("__mod__")(-5.5, -2.0)])
show("remainder keeps the sign", lambda: [slot("__mod__")(5.0, 2.5), slot("__mod__")(5.5, 2.0)])
show("operators through the pairs", lambda: [(5.5 - 2, 2 - 5.5), (5.5 * 2, 2 * 5.5), (5.5 / 2, 2 / 5.5), (5.5 % 2, 2 % 5.5)])
show("text through an operator", lambda: 5.5 + TEXT)
show("text on the left", lambda: TEXT + 5.5)
show("complex declines too", lambda: slot("__add__")(1.5, 1j) is NotImplemented)
show("complex through the operator", lambda: 1.5 + 1j)

print("== divmod, floor divide, true divide and power ==")
show("divmod", lambda: [slot("__divmod__")(7.5, 2.0), slot("__divmod__")(7.5, 2), slot("__rdivmod__")(7.5, 2)])
show("divmod kinds", lambda: [kind(v) for v in slot("__divmod__")(7.5, 2.0)])
show("divmod a pair of ints", lambda: slot("__divmod__")(7, 2))
show("floor divide", lambda: [slot("__floordiv__")(5.5, 2.0), slot("__floordiv__")(5.5, 2), slot("__rfloordiv__")(5.5, 2), kind(slot("__floordiv__")(5.5, 2))])
show("floor divide keeps the real", lambda: [slot("__floordiv__")(-5.5, 2.0), slot("__floordiv__")(5.5, -2.0)])
show("true divide", lambda: [slot("__truediv__")(5.5, 2.0), slot("__truediv__")(5.5, 2), slot("__rtruediv__")(4.0, 2)])
show("by zero", lambda: [slot("__truediv__")(1.0, 0.0), slot("__truediv__")(0.0, 0.0), slot("__truediv__")(-1.0, 0.0)])
show("by int zero", lambda: slot("__truediv__")(1.0, 0))
show("by minus zero", lambda: [repr(slot("__truediv__")(1.0, -0.0)), repr(slot("__truediv__")(0.0, -0.0))])
show("floor divide by zero", lambda: slot("__floordiv__")(1.0, 0.0))
show("remainder by zero", lambda: [slot("__mod__")(1.0, 0.0), slot("__mod__")(0.0, 0.0), slot("__mod__")(1.0, 0)])
show("divmod by zero", lambda: [slot("__divmod__")(1.0, 0.0), slot("__rdivmod__")(1.0, 0.0)])
show("true divide by operator", lambda: 1.0 / 0.0)
show("floor divide by operator", lambda: 1.0 // 0.0)
show("remainder by operator", lambda: 1.0 % 0.0)
show("divmod by operator", lambda: divmod(1.0, 0.0))
show("power", lambda: [slot("__pow__")(2.0, 0.5), slot("__pow__")(2.0, 3), slot("__rpow__")(2.0, 3), slot("__pow__")(4.0, 0.5)])
show("negative base", lambda: [slot("__pow__")(-2.0, 0.5), slot("__pow__")(-8.0, 1 / 3), slot("__pow__")(-2.0, 3)])
show("zero to a negative", lambda: [slot("__pow__")(0.0, -1), slot("__pow__")(0.0, -1.0)])
show("power of the extremes", lambda: [repr(slot("__pow__")(1e308, 2.0)), repr(slot("__pow__")(1e-308, 2.0)), slot("__pow__")(INF, 0)])
show("minus zero powers", lambda: [repr(slot("__pow__")(-0.0, 3)), repr(slot("__pow__")(-0.0, 2))])
show("with a modulus", lambda: [slot("__pow__")(2.0, 3, 4), slot("__pow__")(2.0, 100, 7), slot("__rpow__")(2.0, 3, 4)])
show("with a real modulus", lambda: [slot("__pow__")(2.0, 3, 4.0), slot("__pow__")(2.5, 2, 3)])
show("with no modulus", lambda: slot("__pow__")(2.0, 3, None))
show("with a named modulus", lambda: slot("__pow__")(2.0, 3, modulus=4))
show("with too much", lambda: slot("__pow__")(2.0, 3, 4, 5))
show("the two-argument refusals", lambda: [slot("__pow__")(2.0), slot("__truediv__")(2.0), slot("__truediv__")(2.0, 3.0, 4.0)])
show("zero to the zero", lambda: slot("__pow__")(0.0, 0))
show("an int power asked of a float", lambda: pow(2, 3.0))

print("== the unary six ==")
show("neg", lambda: [slot("__neg__")(1.5), slot("__neg__")(-1.5), slot("__neg__")(0.0)])
show("neg keeps the sign of a zero", lambda: [repr(slot("__neg__")(0.0)), repr(slot("__neg__")(-0.0))])
show("neg of the specials", lambda: [repr(slot("__neg__")(INF)), repr(slot("__neg__")(NAN)), repr(slot("__abs__")(NAN))])
show("pos", lambda: [slot("__pos__")(1.5), slot("__pos__")(-1.5), slot("__pos__")(0.0)])
show("abs", lambda: [slot("__abs__")(-1.5), slot("__abs__")(1.5), slot("__abs__")(-0.0)])
show("abs of an int receiver", lambda: slot("__abs__")(-3))
show("bool", lambda: [slot("__bool__")(0.0), slot("__bool__")(-0.0), slot("__bool__")(1e-320), slot("__bool__")(NAN), slot("__bool__")(INF)])
show("int", lambda: [(slot("__int__")(1.7), kind(slot("__int__")(1.7))), slot("__int__")(-1.5), slot("__int__")(0.0), slot("__int__")(-0.0)])
show("int of the extremes", lambda: [repr(slot("__int__")(1e308)), slot("__int__")(INF), slot("__int__")(NAN)])
show("int of a huge whole", lambda: repr(slot("__int__")(2.0 ** 70)))
show("float", lambda: [slot("__float__")(1.5), slot("__float__")(3), slot("__float__")(True)])
show("float hands the same object back", lambda: slot("__float__")(VALUE) is VALUE)
show("repr", lambda: [slot("__repr__")(1.0), slot("__repr__")(-0.0), slot("__repr__")(1e16), slot("__repr__")(1e-5), slot("__repr__")(1e-4)])
show("repr of the specials", lambda: [slot("__repr__")(INF), slot("__repr__")(-INF), slot("__repr__")(-NAN), slot("__repr__")(0.1)])
show("hash", lambda: [slot("__hash__")(1.0), slot("__hash__")(2.5), slot("__hash__")(INF), slot("__hash__")(-INF), slot("__hash__")(3)])
show("hash of a whole", lambda: (slot("__hash__")(42.0) == hash(42), kind(slot("__hash__")(42.0))))
show("hash of the two zeroes", lambda: slot("__hash__")(-0.0) == slot("__hash__")(0.0))
show("unary operators", lambda: [(-1.5, +1.5, abs(-1.5), bool(0.0), int(-1.5), float(1.5))])
show("the unary half asked of an int", lambda: [slot("__neg__")(3), slot("__abs__")(-3), slot("__int__")(True)])
show("int of a str receiver", lambda: slot("__int__")(TEXT))
show("abs of a str receiver", lambda: slot("__abs__")(TEXT))
show("repr of an int receiver", lambda: slot("__repr__")(3))

print("== the gate every wrapper shares ==")
for name in ("__add__", "__lt__", "__eq__", "__mul__", "__pow__", "__neg__", "__abs__", "__int__", "__float__", "__hash__", "__repr__", "__bool__"):
    show("%s of nothing" % name, lambda n=name: slot(n)(1.5))
show("of three where two are asked", lambda: slot("__lt__")(1.0, 2.0, 3.0))
show("keyword where none is asked", lambda: slot("__lt__")(1.0, 2.0, x=1))
show("keyword on a binary slot", lambda: slot("__add__")(1.0, 2.0, x=1))
show("keyword on a unary slot", lambda: slot("__neg__")(1.0, x=1))
show("a bound wrapper is asked for the receiver", lambda: [(1.5).__add__(2.5), (1.5).__lt__(2.5), (1.5).__neg__()])
show("the receiver of a bound wrapper", lambda: (1.5).__add__.__self__ is not None)
show("a wrapper asked of the wrong kind", lambda: slot("__add__")(TEXT, TEXT))
show("and the wrong kind of unary", lambda: slot("__bool__")([]))

print("== the constructor and its three gates ==")
show("of nothing", lambda: float())
show("of an int", lambda: (float(3), kind(float(3))))
show("of a bool", lambda: (float(True), kind(float(True))))
show("of a real", lambda: (float(VALUE), float(VALUE) is VALUE))
show("of a negative", lambda: float(-3))
show("of a huge int", lambda: float(HUGE))
show("of a big whole", lambda: float(2 ** 70))
show("of none", lambda: float(None))
show("of a list", lambda: float([1]))
show("of a complex", lambda: float(1j))
show("of bytes", lambda: float(b"1.5"))
show("of a type", lambda: float(int))
show("of text", lambda: float("1.5"))
show("of spaced text", lambda: float(" 1.5\n"))
show("of underscored text", lambda: float("1_0.5"))
show("of the words", lambda: [float("inf"), float("-infinity"), float("+Inf"), float("nan")])
show("of bad text", lambda: float("1.2.3"))
show("of hex text", lambda: float("0x1.8p1"))
show("of two", lambda: float(1, 2))
show("of two with a keyword", lambda: float(1, 2, x=3))
show("named value", lambda: float(x=1))
show("new of nothing", lambda: OWNED["__new__"](float))
show("new of text", lambda: OWNED["__new__"](float, "1.5"))
show("new of bytes", lambda: OWNED["__new__"](float, b"1.5"))
show("new of an int", lambda: OWNED["__new__"](float, 3))
show("new hands back what it was given", lambda: OWNED["__new__"](float, VALUE) is VALUE)
show("new of a bool", lambda: kind(OWNED["__new__"](float, True)))
show("new of two", lambda: OWNED["__new__"](float, 1, 2))
show("new of three", lambda: OWNED["__new__"](float, 1, 2, 3))
show("new keyword", lambda: OWNED["__new__"](float, x=1))
show("new keyword and extra", lambda: OWNED["__new__"](float, 1.0, x=2.0))
show("new of nothing at all", lambda: OWNED["__new__"]())
show("only keywords", lambda: OWNED["__new__"](x=1))
show("the gate names a class before it counts", lambda: OWNED["__new__"](int, 1))
show("the gate before the keyword", lambda: OWNED["__new__"](int, x=1))
show("the gate before the count", lambda: OWNED["__new__"](int, 1, 2))
show("the gate of a str", lambda: OWNED["__new__"](str, "x"))
show("the gate of an object", lambda: OWNED["__new__"](object))
show("new is not bound", lambda: type(OWNED["__new__"]).__name__)
show("init swallows", lambda: (1.5).__init__(1))
show("object new of a float", lambda: object.__new__(float))
show("reduce rebuilds", lambda: float(*VALUE.__getnewargs__()) is VALUE)
