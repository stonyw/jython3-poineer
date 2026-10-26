"""The dictionary ``int`` files, and the two ways of calling it that disagree about a wrong count.

``len(int.__dict__)`` is sixty-two: ``__new__``, then forty-two ``wrapper_descriptor``s — the six orderings,
the eight arithmetic pairs, the five unary slots, the ten shift and bit slots, ``__int__``, ``__float__``,
``__index__`` beside the ``__repr__`` and ``__hash__`` that open the row — then the thirteen
``method_descriptor``s and the one ``classmethod_descriptor`` ``long_methods`` writes, the four
``getset_descriptor``s of the numeric properties, and the type's own sentence. ``dir`` reads seventy-four,
and the twelve names it borrows include ``__str__`` and ``__init__``. ``int`` leaves ``tp_str`` to
``object``, which asks ``__repr__`` back, and declares no initializer at all, so ``int.__init__`` is
``object``'s and swallows the arguments a value cannot take rather than refilling it.

The two spellings of the same wrong count are ``long_vectorcall`` and the clinic parser, and they are not
the same sentence: ``int(2, 3, 4)`` says ``int expected at most 2 arguments, got 3`` because the vectorcall
path counted the tuple itself, while ``int(2, 3, x=4)`` says ``int() takes at most 2 arguments (3 given)``
because a keyword sends the call to ``_PyObject_MakeTpCall`` and the parser. What decides which of the two
runs is whether the call named anything, so ``int.__new__(int, 2, 3, 4)`` — which never reaches
``long_vectorcall`` — always answers with the parser's wording. The base is read and range-checked before
``x`` is looked at, which puts the number a base should be ahead of the value it has nothing to do with:
``int(5, 1)`` is about the base being one, not about five not being a string. Naming a base at all commits
the call to the literal form, so ``int(2**70, 10)`` refuses a value ``int(2**70)`` accepts.

Every slot here gates on ``CHECK_BINOP``, which is why a float pair is declined rather than promoted:
``int.__eq__(5, 5.0)`` is ``NotImplemented``, and the ``True`` that ``5 == 5.0`` still answers comes from
the other side of that decline. Five of the answers are the receiver it was given — ``__pos__``,
``__trunc__``, ``__floor__``, ``__ceil__``, and ``__round__`` of a place that is ``None`` or a place at or
after the point — which is observable through ``is`` on a value too big for the small-int cache, and
``__int__`` and ``__index__`` answer with the same integer for a different reason: ``long_long`` copies
nothing.

The sections below print the table, the four groups of slots, the fourteen methods, the four properties and
the two calling halves. What they do not print is a bound ``repr()`` (CPython's names an address), ``~`` of a
``bool`` (CPython warns on stderr and this runtime has no warnings module), and a shift count big enough for
CPython to try allocating it — ``1 << 2**31`` is a 256 MB integer there and a ``MemoryError`` here. Neither
does it print a base that reaches ``__index__``, which this runtime does not ask. ``__sizeof__`` is printed
because the runtime counts the same 30-bit digits CPython does, so 28, 32 and 36 come out of both.
"""


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


def line(kind, name):
    """One name of a type's namespace, as the table filed it and as a read sees it."""
    value = kind.__dict__[name]
    return "%-20s %-24s %-20s self=%-5s objclass=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__self__"),
        hasattr(value, "__objclass__"),
    )


OWNED = int.__dict__
ORDER = list(OWNED)
BIG = 2 ** 70
FIVE = 5


def missing(names):
    """The names of the object half this table does not file."""
    return [name for name in names if name not in OWNED]


def offset(first, second):
    """How far apart two names are in the table, which is the order ``long_methods`` wrote them in."""
    return ORDER.index(second) - ORDER.index(first)


print("== the namespace of an int ==")
show("kind", lambda: type(OWNED).__name__)
show("len", lambda: len(OWNED))
show("dir is wider", lambda: len(dir(int)))
show("new is first", lambda: ORDER[0])
show("doc is last", lambda: ORDER[-1])
show("new is not a descriptor", lambda: type(OWNED["__new__"]).__name__)
show("new names itself", lambda: OWNED["__new__"].__qualname__)
show("new is bound to int", lambda: OWNED["__new__"].__self__ is int)
show("new has no objclass", lambda: hasattr(OWNED["__new__"], "__objclass__"))
show("repr follows new", lambda: type(OWNED["__repr__"]).__name__)
show("hash is second slot", lambda: offset("__new__", "__hash__"))
show("six orderings", lambda: offset("__lt__", "__ge__"))
show("add follows the orderings", lambda: offset("__ge__", "__add__"))
show("five pairs then pow", lambda: offset("__add__", "__pow__"))
show("unary half", lambda: offset("__neg__", "__invert__"))
show("shifts and bit slots", lambda: offset("__lshift__", "__ror__"))
show("conversions close the slots", lambda: offset("__int__", "__index__"))
show("methods follow slots", lambda: offset("__index__", "conjugate"))
show("fourteen methods", lambda: offset("conjugate", "is_integer"))
show("properties follow methods", lambda: offset("is_integer", "denominator"))
show("one classmethod", lambda: type(OWNED["from_bytes"]).__name__)
show("read agrees", lambda: int.bit_length is OWNED["bit_length"])
show("filed once", lambda: OWNED["to_bytes"] is OWNED["to_bytes"])
show("value read twice", lambda: (lambda t: t.bit_length == t.bit_length)(BIG))
show("nothing of object is filed", lambda: missing(["__str__", "__init__", "__dir__", "__sizeof_class__"]))
show("str is inherited", lambda: int.__str__ is object.__str__)
show("str asks the repr", lambda: str(-BIG) == OWNED["__repr__"](-BIG))
show("init is inherited", lambda: int.__init__ is object.__init__)
show("doc is the type's own", lambda: OWNED["__doc__"].splitlines()[0])
show("doc width", lambda: len(OWNED["__doc__"]))

print("== all sixty-two names, as the table files them ==")
for name in ORDER:
    print(line(int, name))

print("== the six orderings, each gated on two integers ==")
show("lt", lambda: OWNED["__lt__"](5, 6))
show("le", lambda: OWNED["__le__"](5, 5))
show("eq", lambda: OWNED["__eq__"](5, 6))
show("ne", lambda: OWNED["__ne__"](5, 6))
show("gt", lambda: OWNED["__gt__"](BIG, 5))
show("ge", lambda: OWNED["__ge__"](5, True))
show("eq a float declines", lambda: OWNED["__eq__"](5, 5.0) is NotImplemented)
show("ne a float declines", lambda: OWNED["__ne__"](5, 5.0) is NotImplemented)
show("lt a float declines", lambda: OWNED["__lt__"](5, 5.5) is NotImplemented)
show("lt a str declines", lambda: OWNED["__lt__"](5, "a") is NotImplemented)
show("operator still answers", lambda: 5 < 5.5)
show("and the other half", lambda: 5.0 > 5)
show("eq a bool", lambda: OWNED["__eq__"](5, True))
show("eq of a big and a small", lambda: OWNED["__eq__"](BIG, 5))
show("lt names its receiver", lambda: OWNED["__lt__"]("a", 5))
show("lt of nothing", lambda: OWNED["__lt__"](5))
show("lt of three", lambda: OWNED["__lt__"](5, 6, 7))
show("lt keyword", lambda: OWNED["__lt__"](5, 6, x=1))

print("== the eleven arithmetic pairs, and what each one builds ==")
show("add", lambda: OWNED["__add__"](5, 6))
show("add a bool", lambda: OWNED["__add__"](5, True))
show("add a str declines", lambda: OWNED["__add__"](5, "a") is NotImplemented)
show("add a float declines", lambda: OWNED["__add__"](5, 2.5) is NotImplemented)
show("radd swaps", lambda: OWNED["__radd__"](5, 6))
show("sub", lambda: OWNED["__sub__"](5, 6))
show("rsub of a negative", lambda: OWNED["__rsub__"](5, 2))
show("mul", lambda: OWNED["__mul__"](5, 6))
show("mul a str declines", lambda: OWNED["__mul__"](5, "a") is NotImplemented)
show("text repeats, an int does not", lambda: "ab" * 2)
show("rmul swaps", lambda: OWNED["__rmul__"](5, 6))
show("mod", lambda: OWNED["__mod__"](5, 3))
show("mod a negative", lambda: OWNED["__mod__"](5, -3))
show("rmod swaps", lambda: OWNED["__rmod__"](5, 13))
show("floordiv", lambda: OWNED["__floordiv__"](5, 2))
show("floordiv negative", lambda: OWNED["__floordiv__"](-5, 2))
show("rfloordiv swaps", lambda: OWNED["__rfloordiv__"](2, 5))
show("truediv is a float", lambda: (OWNED["__truediv__"](3, 2), type(OWNED["__truediv__"](3, 2)).__name__))
show("rtruediv swaps", lambda: OWNED["__rtruediv__"](2, 3))
show("truediv by zero", lambda: OWNED["__truediv__"](5, 0))
show("floordiv by zero", lambda: OWNED["__floordiv__"](5, 0))
show("mod by zero", lambda: OWNED["__mod__"](5, 0))
show("divmod", lambda: OWNED["__divmod__"](7, 3))
show("divmod negative", lambda: OWNED["__divmod__"](5, -3))
show("divmod a float declines", lambda: OWNED["__divmod__"](5, 2.5) is NotImplemented)
show("rdivmod swaps", lambda: OWNED["__rdivmod__"](5, 13))
show("pow", lambda: OWNED["__pow__"](2, 10))
show("pow of nothing is a float", lambda: OWNED["__pow__"](2, -1))
show("pow three args", lambda: OWNED["__pow__"](2, 10, 7))
show("pow with a None modulus", lambda: OWNED["__pow__"](2, 3, None))
show("pow declines a float exponent", lambda: OWNED["__pow__"](2, 0.5) is NotImplemented)
show("pow big three", lambda: OWNED["__pow__"](BIG, BIG, BIG))
show("rpow with a modulus", lambda: OWNED["__rpow__"](2, 3, 4))
show("rpow declines a str", lambda: OWNED["__rpow__"](2, 3, "x") is NotImplemented)
show("pow negative modulus", lambda: OWNED["__pow__"](2, 3, -4))
show("pow of zero to the zero", lambda: OWNED["__pow__"](0, 0))
show("pow by zero", lambda: 0 ** -1)
show("add names its receiver", lambda: OWNED["__add__"](2.5, 5))
show("add of nothing", lambda: OWNED["__add__"](5))
show("add of three", lambda: OWNED["__add__"](5, 6, 7))
show("pow of four", lambda: OWNED["__pow__"](2, 3, 4, 5))
show("add keyword", lambda: OWNED["__add__"](5, 6, x=1))

print("== the five unary slots, and the identities two of them keep ==")
show("neg", lambda: OWNED["__neg__"](5))
show("neg of a bool", lambda: OWNED["__neg__"](True))
show("neg of the negative", lambda: OWNED["__neg__"](-BIG))
show("pos keeps the value", lambda: OWNED["__pos__"](BIG) is BIG)
show("pos of a bool is an int", lambda: (lambda v: (v, type(v).__name__))(OWNED["__pos__"](True)))
show("abs", lambda: OWNED["__abs__"](-5))
show("abs of a bool", lambda: (lambda v: (v, type(v).__name__))(OWNED["__abs__"](True)))
show("abs of the positive", lambda: OWNED["__abs__"](BIG) is BIG)
show("invert", lambda: OWNED["__invert__"](5))
show("invert of the negative", lambda: OWNED["__invert__"](-1))
show("bool of zero", lambda: OWNED["__bool__"](0))
show("bool of a big", lambda: OWNED["__bool__"](BIG))
show("bool answers a bool", lambda: type(OWNED["__bool__"](BIG)).__name__)
show("neg of two", lambda: OWNED["__neg__"](5, 6))
show("abs of nothing", lambda: OWNED["__abs__"]())

print("== the ten shift and bit slots ==")
show("lshift", lambda: OWNED["__lshift__"](2, 10))
show("lshift a bool", lambda: OWNED["__lshift__"](5, True))
show("lshift a str declines", lambda: OWNED["__lshift__"](2, "a") is NotImplemented)
show("lshift a float declines", lambda: OWNED["__lshift__"](5, 2.0) is NotImplemented)
show("lshift negative", lambda: OWNED["__lshift__"](2, -1))
show("rlshift swaps", lambda: OWNED["__rlshift__"](2, 8))
show("rshift", lambda: OWNED["__rshift__"](1024, 3))
show("rshift of a big", lambda: OWNED["__rshift__"](BIG, 70))
show("rshift negative", lambda: OWNED["__rshift__"](2, -1))
show("rrshift swaps", lambda: OWNED["__rrshift__"](2, 8))
show("and", lambda: OWNED["__and__"](12, 10))
show("and of two bools", lambda: (lambda v: (v, type(v).__name__))(OWNED["__and__"](True, True)))
show("and a str declines", lambda: OWNED["__and__"](5, "a") is NotImplemented)
show("rand swaps", lambda: OWNED["__rand__"](12, 10))
show("xor", lambda: OWNED["__xor__"](12, 10))
show("xor of two bools", lambda: (lambda v: (v, type(v).__name__))(OWNED["__xor__"](True, True)))
show("rxor swaps", lambda: OWNED["__rxor__"](12, 10))
show("or", lambda: OWNED["__or__"](12, 10))
show("or a float declines", lambda: OWNED["__or__"](5, 2.0) is NotImplemented)
show("ror swaps", lambda: OWNED["__ror__"](12, 10))
show("shift of a str receiver", lambda: OWNED["__lshift__"]("a", 2))

print("== the three conversions ==")
show("int keeps the value", lambda: (lambda v: v is FIVE)(OWNED["__int__"](FIVE)))
show("int of a big", lambda: OWNED["__int__"](BIG) is BIG)
show("float", lambda: OWNED["__float__"](5))
show("float of a big", lambda: ("%.6e" % OWNED["__float__"](BIG)).startswith("1.180591"))
show("index", lambda: OWNED["__index__"](BIG) is BIG)
show("index names its receiver", lambda: OWNED["__index__"]("a"))
show("slice uses index", lambda: [1, 2, 3][BIG * 0:])
show("int of a float receiver", lambda: OWNED["__int__"](2.5))

print("== the fourteen methods, run through the descriptor ==")
show("conjugate", lambda: OWNED["conjugate"](-5))
show("conjugate keeps a big", lambda: OWNED["conjugate"](BIG) is BIG)
show("conjugate extra", lambda: OWNED["conjugate"](5, 1))
show("bit_length", lambda: [OWNED["bit_length"](v) for v in (0, 1, 37, -37, BIG)])
show("bit_count", lambda: [OWNED["bit_count"](v) for v in (0, 13, -13, BIG)])
show("bit_length of a str", lambda: OWNED["bit_length"]("a"))
show("bit_length keyword", lambda: (5).bit_length(x=1))
show("to_bytes", lambda: (1024).to_bytes(2, "big"))
show("to_bytes little", lambda: (1024).to_bytes(2, "little"))
show("to_bytes overflow", lambda: (1024).to_bytes(1, "big"))
show("to_bytes signed", lambda: (-1).to_bytes(1, "big", signed=True))
show("from_bytes is a classmethod", lambda: type(OWNED["from_bytes"]).__name__)
show("from_bytes", lambda: int.from_bytes(b"\x04\x00", "big"))
show("from_bytes signed", lambda: int.from_bytes(b"\xff", "big", signed=True))
show("from_bytes of a list", lambda: int.from_bytes([1, 2], "little"))
show("from_bytes wrong class", lambda: OWNED["from_bytes"].__func__(str, b"\x01", "big"))
show("as_integer_ratio", lambda: OWNED["as_integer_ratio"](-10))
show("as_integer_ratio of zero", lambda: (0).as_integer_ratio())
show("as_integer_ratio keeps a big", lambda: (lambda t: t[0] is BIG)(OWNED["as_integer_ratio"](BIG)))
show("trunc", lambda: OWNED["__trunc__"](BIG) is BIG)
show("floor", lambda: OWNED["__floor__"](BIG) is BIG)
show("ceil", lambda: OWNED["__ceil__"](-BIG) is -BIG)
show("round of nothing", lambda: OWNED["__round__"](5))
show("round of a None place", lambda: OWNED["__round__"](BIG, None) is BIG)
show("round a None small", lambda: OWNED["__round__"](5, None) is FIVE)
show("round a place", lambda: OWNED["__round__"](5, 1))
show("round a power", lambda: OWNED["__round__"](5, -1))
show("round halfway up", lambda: [OWNED["__round__"](v, -1) for v in (5, 15, 25, -5, -15, -1350)])
show("round of nine thousand", lambda: (9999).__round__(-4))
show("round past the digits", lambda: (1234).__round__(-5))
show("round of zero", lambda: (0).__round__(-3))
show("round a bool place", lambda: (1234).__round__(True))
show("round of text", lambda: OWNED["__round__"](5, "a"))
show("round two args", lambda: OWNED["__round__"](5, 1, 2))
show("round keyword", lambda: OWNED["__round__"](5, ndigits=1))
show("round of a str receiver", lambda: OWNED["__round__"]("a", 1))
show("getnewargs copies", lambda: (lambda t: (t[0] == BIG, t[0] is BIG))(OWNED["__getnewargs__"](BIG)))
show("getnewargs of a small", lambda: (lambda t: t[0] is FIVE)(OWNED["__getnewargs__"](FIVE)))
show("rebuild through new", lambda: int(*OWNED["__getnewargs__"](BIG)) is not BIG)
show("getnewargs extra", lambda: OWNED["__getnewargs__"](5, 1))
show("format", lambda: [OWNED["__format__"](v, s) for s in ("", "x", "b", "08b", ">10d", "+d")])
show("format of a big", lambda: OWNED["__format__"](BIG, ","))
show("format of a number", lambda: OWNED["__format__"](5, 3))
show("format of nothing", lambda: OWNED["__format__"](5))
show("format two", lambda: OWNED["__format__"](5, "x", 1))
show("sizeof of a small", lambda: OWNED["__sizeof__"](5))
show("sizeof grows a digit", lambda: OWNED["__sizeof__"](2 ** 30) - OWNED["__sizeof__"](2 ** 30 - 1))
show("sizeof ignores the sign", lambda: OWNED["__sizeof__"](-(2 ** 30)) == OWNED["__sizeof__"](2 ** 30))
show("sizeof extra", lambda: OWNED["__sizeof__"](5, 1))
show("is_integer", lambda: OWNED["is_integer"](0))
show("is_integer of a str", lambda: OWNED["is_integer"]("a"))
show("eight have no doc", lambda: sorted(n for n in ORDER if OWNED[n].__doc__ is None))

print("== the four properties, and the writes they refuse ==")
show("kinds", lambda: [type(OWNED[n]).__name__ for n in ("real", "imag", "numerator", "denominator")])
show("objclass", lambda: OWNED["real"].__objclass__ is int)
show("names", lambda: [OWNED[n].__qualname__ for n in ("real", "imag", "numerator", "denominator")])
show("read", lambda: [OWNED[n].__get__(BIG) for n in ("real", "imag", "numerator", "denominator")])
show("real keeps the value", lambda: OWNED["real"].__get__(BIG) is BIG)
show("numerator keeps the value", lambda: OWNED["numerator"].__get__(BIG) is BIG)
show("of a bool", lambda: [OWNED[n].__get__(True) for n in ("real", "imag", "numerator", "denominator")])
show("of a negative", lambda: [OWNED[n].__get__(-7) for n in ("real", "imag", "numerator", "denominator")])
show("attribute read", lambda: (BIG.real, BIG.imag, BIG.numerator, BIG.denominator))
show("bool attribute read", lambda: (True.real, True.imag, True.numerator, True.denominator))
show("no receiver", lambda: OWNED["real"].__get__(None))
show("a str receiver", lambda: OWNED["real"].__get__("a"))
show("set", lambda: OWNED["real"].__set__(5, 1))
show("delete", lambda: OWNED["real"].__delete__(5))
show("attribute write", lambda: setattr(5, "real", 1))
show("is not writable", lambda: (5).real == 5)

print("== the two ways of calling int ==")
show("of nothing", lambda: int())
show("of a bool", lambda: (lambda v: (v, type(v).__name__))(int(True)))
show("of a big", lambda: int(BIG) is BIG)
show("of text", lambda: int(" 12 "))
show("of underscores", lambda: int("1_0", 10))
show("of bytes", lambda: int(b"11", 2))
show("of a float", lambda: int(-2.7))
show("base sixteen", lambda: int("ff", 16))
show("base zero", lambda: [int("12", 0), int("0x12", 0), int("0o12", 0), int("0b11", 0)])
show("base one", lambda: int(5, 1))
show("base of text", lambda: int("1", "x"))
show("base thirty-seven", lambda: int(5, 37))
show("base of a bool", lambda: int("1", True))
show("non-string with a base", lambda: int(BIG, 10))
show("non-string without one", lambda: int(BIG))
show("missing string", lambda: int(base=16))
show("bad literal", lambda: int("0x1z", 0))
show("too many positional", lambda: int(2, 3, 4))
show("too many with a keyword", lambda: int(2, 3, x=4))
show("unpacked too many", lambda: int(*[1, 2, 3]))
show("named value", lambda: int(x=1))
show("unknown keyword", lambda: int(**{"x": 1}))
show("keyword base", lambda: int("2", base=16))
show("keyword order", lambda: int(base=16, x="ff"))
show("keyword then unpacked", lambda: int(base=16, *["ff"]))
show("through new", lambda: OWNED["__new__"](int, "ff", 16))
show("new of nothing", lambda: OWNED["__new__"](int))
show("new of too many", lambda: OWNED["__new__"](int, 2, 3, 4))
show("new with a keyword", lambda: OWNED["__new__"](int, x=1))
show("new of a float base", lambda: OWNED["__new__"](int, 5, 1.0))
show("new of bool", lambda: int.__new__(bool, True))
show("new of str", lambda: int.__new__(str))
show("object new of int", lambda: object.__new__(int))
show("new of a big base", lambda: int("1", 2 ** 70))
show("float base", lambda: int(5, 2.5))
