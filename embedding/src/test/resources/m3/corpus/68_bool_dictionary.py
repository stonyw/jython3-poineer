"""The dictionary ``bool`` files, and the ``int`` underneath it that answers everything else.

``len(bool.__dict__)`` is ten: ``__new__``, ``__repr__``, ``__invert__`` and the three bitwise pairs.
Everything else a ``bool`` answers — the six orderings, the eight arithmetic pairs, the shifts, the
conversions, the fourteen methods and the four properties — is read out of ``int``'s sixty-two rows, which
is why ``dir`` reads seventy-four for both and why ``bool.bit_length is int.bit_length`` holds: the same
descriptor, found by the walk of the method resolution order. What ``bool`` writes down for itself is only
what ``int`` would answer differently, and the two cases are ``PyBool_Check`` in the bitwise slots and a
constructor that hands out one of two singletons.

``bool_and``, ``bool_or`` and ``bool_xor`` are three lines each: if both operands are ``bool`` the answer is
one of the two singletons, and if either is not the slot calls straight through to ``PyLong_Type``'s own
number slot. That is what makes ``True & True`` a ``bool`` while ``True & 1`` is the ``int`` one, ``True ^
1`` a ``int`` zero, and ``True | 2`` three — the same pair of operators, decided by the type of what came
in rather than by what the answer means. A float never reaches either half: the slot declines with
``NotImplemented`` and ``bool``'s own ``__or__`` is not asked again, so ``True | 2.0`` is a ``TypeError``
about ``int`` because the int slot that finally answered named itself.

``bool_new`` runs its two refusals in an order that a plain call cannot see: ``_PyArg_NoKeywords`` is checked
before ``PyArg_UnpackTuple``, so ``bool(x=1)`` is about the keyword and not about the count, and only a call
that named nothing reaches the wording ``bool expected at most 1 argument, got 2``. A call that gave nothing
at all asks the truth of a default that is ``Py_False``, which is why ``bool()`` is ``False`` rather than the
truth of the class. Whatever the answer, it is one of the two singletons: ``bool(1) is True`` and ``int(True)
is 1`` both hold, while the ``1`` that ``int`` answers is an ``int`` and not the ``True`` it came from.

The sections below print the table, the three bitwise pairs, the ``int`` half a ``bool`` borrows, the
constructor and its gates. What they do not print is ``~`` of a ``bool``, whose answer this runtime agrees
with but whose ``DeprecationWarning`` it cannot emit, a bound ``repr()`` (CPython's names an address), and
the size of a ``bool`` as a number — only its kind is printed, and both runtimes read 28.
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
    return "%-16s %-24s %-22s self=%-5s objclass=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__self__"),
        hasattr(value, "__objclass__"),
    )


OWNED = bool.__dict__
ORDER = list(OWNED)
INT = int.__dict__
ONE = 1


def missing(names):
    """The names an int files that this table leaves to it."""
    return [name for name in names if name not in OWNED]


def offset(first, second):
    """How far apart two names are in the table, which is the order ``bool_methods`` wrote them in."""
    return ORDER.index(second) - ORDER.index(first)


def kind(value):
    """The name of what an operator answered, which is the half each bool slot decides for itself."""
    return type(value).__name__


print("== the namespace of a bool ==")
show("kind", lambda: type(OWNED).__name__)
show("len", lambda: len(OWNED))
show("dir is as wide as an int", lambda: (len(dir(bool)), len(dir(int))))
show("new is first", lambda: ORDER[0])
show("doc is last", lambda: ORDER[-1])
show("repr follows new", lambda: offset("__new__", "__repr__"))
show("invert is third", lambda: offset("__repr__", "__invert__"))
show("three pairs", lambda: offset("__invert__", "__ror__"))
show("and is fourth", lambda: offset("__invert__", "__and__"))
show("new is not a descriptor", lambda: type(OWNED["__new__"]).__name__)
show("new names itself", lambda: OWNED["__new__"].__qualname__)
show("new is bound to bool", lambda: OWNED["__new__"].__self__ is bool)
show("slots are wrappers", lambda: [type(OWNED[n]).__name__ for n in ORDER[1:9]])
show("qualnames are bool's", lambda: [OWNED[n].__qualname__ for n in ("__and__", "__rxor__", "__repr__")])
show("objclasses are bool's", lambda: OWNED["__or__"].__objclass__ is bool)
show("nothing else is filed", lambda: missing([
    "__lt__", "__eq__", "__hash__", "__add__", "__index__", "__format__", "real", "bit_length",
    "to_bytes", "__int__", "__init__", "__str__", "__reversed__", "__dir__"]))
show("hash comes from int", lambda: bool.__hash__ is INT["__hash__"])
show("bit_length comes from int", lambda: bool.bit_length is INT["bit_length"])
show("real comes from int", lambda: bool.__dict__.get("real") is None and bool.real == 1)
show("str is inherited", lambda: bool.__str__ is object.__str__)
show("init is inherited", lambda: bool.__init__ is object.__init__)
show("base is int", lambda: bool.__base__ is int)
show("order", lambda: [c.__name__ for c in bool.__mro__])
show("doc is the constructor", lambda: OWNED["__doc__"].splitlines()[0])
show("doc width", lambda: len(OWNED["__doc__"]))
show("int doc differs", lambda: INT["__doc__"] != OWNED["__doc__"])

print("== all ten names, as the table files them ==")
for name in ORDER:
    print(line(bool, name))

print("== the three pairs, decided by whether both operands are bools ==")
show("and both bools", lambda: (OWNED["__and__"](True, True), kind(OWNED["__and__"](True, True))))
show("and the false half", lambda: [OWNED["__and__"](a, b) for a in (True, False) for b in (True, False)])
show("and an int", lambda: (OWNED["__and__"](True, 1), kind(OWNED["__and__"](True, 1))))
show("and an int zero", lambda: (OWNED["__and__"](True, 0), kind(OWNED["__and__"](True, 0))))
show("and a big", lambda: OWNED["__and__"](True, 2 ** 70))
show("and a float declines", lambda: OWNED["__and__"](True, 2.0) is NotImplemented)
show("and a str declines", lambda: OWNED["__and__"](True, "a") is NotImplemented)
show("rand swaps", lambda: (OWNED["__rand__"](True, 12), kind(OWNED["__rand__"](True, 12))))
show("rand of a str", lambda: OWNED["__rand__"](True, "a") is NotImplemented)
show("xor both bools", lambda: [OWNED["__xor__"](a, b) for a in (True, False) for b in (True, False)])
show("xor keeps bools", lambda: kind(OWNED["__xor__"](True, False)))
show("xor an int", lambda: (OWNED["__xor__"](True, 1), kind(OWNED["__xor__"](True, 1))))
show("rxor swaps", lambda: (OWNED["__rxor__"](True, 2), kind(OWNED["__rxor__"](True, 2))))
show("rxor of a bool pair", lambda: (OWNED["__rxor__"](True, False), kind(OWNED["__rxor__"](True, False))))
show("rxor declines", lambda: OWNED["__rxor__"](True, 2.0) is NotImplemented)
show("or both bools", lambda: [OWNED["__or__"](a, b) for a in (True, False) for b in (True, False)])
show("or an int", lambda: (OWNED["__or__"](True, 2), kind(OWNED["__or__"](True, 2))))
show("or a float declines", lambda: OWNED["__or__"](True, 2.0) is NotImplemented)
show("ror swaps", lambda: (OWNED["__ror__"](False, 12), kind(OWNED["__ror__"](False, 12))))
show("operator and", lambda: [(True & True, kind(True & True)), (True & 1, kind(True & 1))])
show("operator or", lambda: [(True | 2, kind(True | 2)), (False | 0, kind(False | 0))])
show("operator xor", lambda: [(True ^ True, kind(True ^ True)), (True ^ 1, kind(True ^ 1))])
show("float through the operator", lambda: True | 2.0)
show("and of nothing", lambda: OWNED["__and__"](True))
show("and of three", lambda: OWNED["__and__"](True, 1, 2))
show("and keyword", lambda: OWNED["__and__"](True, 1, x=2))
show("and names its receiver", lambda: OWNED["__and__"](2.0, True))
show("invert comes from bool", lambda: INT["__invert__"] is not OWNED["__invert__"])
show("int slot of a bool", lambda: INT["__invert__"](True))

print("== the int half a bool borrows ==")
show("add", lambda: (True + True, kind(True + True)))
show("lt", lambda: (False < True, kind(False < True)))
show("eq an int", lambda: True == 1)
show("eq a float", lambda: True == 1.0)
show("identity is not equality", lambda: (lambda t: (t is ONE, t is not False))(True))
show("mul", lambda: True * 3)
show("neg", lambda: -True)
show("pos kind", lambda: kind(+True))
show("abs kind", lambda: kind(abs(True)))
show("truediv", lambda: True / True)
show("index", lambda: (True).__index__())
show("int of a bool", lambda: (int(True), kind(int(True))))
show("float of a bool", lambda: float(True))
show("shift", lambda: 1 << True)
show("bit_length", lambda: (True).bit_length())
show("bit_count", lambda: (False).bit_count())
show("conjugate", lambda: (True).conjugate())
show("as_integer_ratio", lambda: (True).as_integer_ratio())
show("to_bytes", lambda: (True).to_bytes(1, "big"))
show("from_bytes builds an int", lambda: kind(int.from_bytes(b"\x01", "big")))
show("round a power", lambda: (True).__round__(-1))
show("trunc", lambda: (True).__trunc__())
show("getnewargs", lambda: (lambda t: (t, kind(t[0])))((True).__getnewargs__()))
show("format", lambda: (True).__format__("d"))
show("is_integer", lambda: (True).is_integer())
show("sizeof kind", lambda: kind((True).__sizeof__()))
show("the four properties", lambda: (True.real, True.imag, True.numerator, True.denominator))
show("property kinds", lambda: [kind(v) for v in (True.real, True.imag, True.numerator, True.denominator)])
show("real is the int", lambda: INT["real"].__get__(True) == 1)
show("property write", lambda: setattr(True, "real", 2))
show("repr slot", lambda: OWNED["__repr__"](False))
show("int repr of a bool", lambda: INT["__repr__"](True))
show("str", lambda: [str(True), str(False), repr(True)])
show("hash agrees with one", lambda: hash(True) == hash(1))
show("hash of false", lambda: hash(False) == hash(0))
show("kind of hash", lambda: kind(OWNED["__hash__"](True)))
show("one dict key", lambda: len({True: 1, 1: 2}))
show("looked up as a one", lambda: {True: "x"}[1])
show("sorted mixes", lambda: sorted([False, 1, True, 0]))

print("== the constructor, its two refusals and its truth ==")
show("of nothing", lambda: bool())
show("of nothing is false", lambda: bool() is False)
show("of a list", lambda: bool([]))
show("of a filled list", lambda: bool([0]))
show("of a text zero", lambda: bool("0"))
show("of none", lambda: bool(None))
show("of a float zero", lambda: bool(0.0))
show("of a big", lambda: bool(2 ** 70))
show("singletons", lambda: [bool(1) is True, bool(-1) is True, bool(0) is False])
show("of a set", lambda: bool({0}))
show("of a class", lambda: bool(type))
show("kind", lambda: kind(bool(1)))
show("new of nothing", lambda: OWNED["__new__"](bool))
show("new of nothing is false", lambda: OWNED["__new__"](bool) is False)
show("new of a list", lambda: bool.__new__(bool, []))
show("new of a zero", lambda: bool.__new__(bool, 0) is False)
show("new keyword", lambda: bool.__new__(bool, x=1))
show("new of two", lambda: bool.__new__(bool, 1, 2))
show("call keyword", lambda: bool(x=1))
show("call of two", lambda: bool(1, 2))
show("unpacked two", lambda: bool(*[1, 2]))
show("new of int", lambda: bool.__new__(int, 1))
show("new of str", lambda: bool.__new__(str))
show("new of nothing at all", lambda: bool.__new__())
show("int new of bool", lambda: int.__new__(bool, True))
show("object new of bool", lambda: object.__new__(bool))
show("object new of str", lambda: object.__new__(str, "x"))
show("init swallows", lambda: True.__init__(1))
show("truth of a slot answer", lambda: kind(OWNED["__new__"](bool, [1, 2])))

print("== the sentence each filed name carries ==")
for name in ORDER:
    print("%-16s %r" % (name, OWNED[name].__doc__))
