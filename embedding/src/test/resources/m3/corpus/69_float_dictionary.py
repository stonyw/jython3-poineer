"""The forty-seven rows ``float`` files, and the four kinds it files them with.

``len(float.__dict__)`` is forty-seven and ``len(dir(float))`` is sixty. The thirteen names the second has
and the first does not are ``object``'s, which a ``float`` borrows without writing a line for: ``__str__``,
``__init__``, ``__sizeof__``, ``__dir__``, ``__getattribute__`` and the rest of that half. ``dir`` is the
union of the walk down the mro, so it counts ``object``'s rows once and none of them twice, and the
difference between the two numbers is exactly how much of a ``float`` is not its own. There is no
``__setformat__`` at all: ``floatobject.c`` files the getter and leaves the matching setter in the C file
that reads it, so asking for it is an ``AttributeError`` rather than a row of the table.

The forty-seven are written down in four kinds, and each kind says who has to be there when it is called.
``__new__`` is one of one: a ``builtin_function_or_method`` that names ``float.__new__`` and carries no
``__objclass__``, because ``tp_new`` is reached through ``type.__new__`` and takes its class as a plain
argument. Thirty rows are ``wrapper_descriptor``: ``__repr__`` and ``__hash__``, the six orderings, the eight
arithmetic pairs that ``float_as_number`` fills — add, subtract, multiply, remainder, divmod, power, floor
divide and true divide, each with its reflected half — and the six unary and conversion slots ``neg``,
``pos``, ``abs``, ``bool``, ``int`` and ``float``. There is no bitwise row and no ``__index__``: a ``float``
is not a number an operator like ``&`` can be asked of it. Thirteen rows are the members of
``float_methods``: nine ``method_descriptor``, plus a ``classmethod_descriptor`` each for ``from_number``,
``fromhex`` and ``__getformat__``, and ``__format__`` as a tenth ``method_descriptor``. Two are
``getset_descriptor`` — ``real`` and ``imag`` — and the last row is ``__doc__``, the only one that is a
string and not a descriptor, which is why it is the only row with no ``__qualname__``.

Six of the rows hand back the receiver it was called with, because all six are the same C function
``float_float``: unary plus, ``float()``, ``__float__``, ``conjugate``, ``real`` and ``from_number``. A float
is already its own conjugate and its own real part, so the answer is the object, not a copy of it — and
``imag`` is the one that cannot do the same, because it answers a zero that belongs to no one, so
``x.imag is x`` is false while ``type(x.imag) is float`` is true. What the four kinds and the six identities
print below is the same table read twice: once as the proxy files it, and once as calling it answers.

What this case does not print is the size of a ``float`` as a number — ``__basicsize__`` and ``__flags__``
are answered here as zero, because a runtime value has no C layout to report — the hash of a ``nan``, which
3.14.8 draws from a per-process random seed, the repr of ``__new__``, which names an address, a subclass of
``float``, which this runtime refuses to build, and the kind of a member bound to a value, which this
runtime calls ``method`` where CPython calls ``builtin_function_or_method`` — a gap every value type shares
rather than one ``float`` writes for itself.
"""


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


def line(name):
    """One row of the table: what kind it is filed as, and what that kind carries."""
    value = OWNED[name]
    return "%-20s %-24s %-24s self=%-5s objclass=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__self__"),
        hasattr(value, "__objclass__"),
    )


OWNED = float.__dict__
ORDER = list(OWNED)
VALUE = 1234.5  # a real number, so the six identities below are observable rather than cached


def filed(name):
    return name in OWNED


def kinds(name):
    """The kind a row is filed as, which is what decides how it can be called."""
    return type(OWNED[name]).__name__


def offset(first, second):
    """How far apart two rows are, which is the order ``floatobject.c`` wrote them in."""
    return ORDER.index(second) - ORDER.index(first)


def of(kind):
    """Every row filed as one kind."""
    return [name for name in ORDER if kinds(name) == kind]


print("== the shape of the table ==")
show("len", lambda: len(OWNED))
show("dir", lambda: len(dir(float)))
show("the inherited half", lambda: sorted(set(dir(float)) - set(OWNED)))
show("inherited count", lambda: len(dir(float)) - len(OWNED))
show("mro", lambda: [c.__name__ for c in float.__mro__])
show("base", lambda: float.__base__ is object)
show("name", lambda: float.__name__)
show("qualname", lambda: float.__qualname__)
show("module", lambda: float.__module__)
show("proxy kind", lambda: type(OWNED).__name__)
show("itemsize", lambda: float.__itemsize__)
show("doc", lambda: float.__doc__)
show("doc is one line", lambda: len(float.__doc__.splitlines()))
show("doc width", lambda: len(OWNED["__doc__"]))
show("doc is a str row", lambda: kinds("__doc__"))
show("only row without a qualname", lambda: [n for n in ORDER if not hasattr(OWNED[n], "__qualname__")])
show("rows that say nothing", lambda: [n for n in ORDER if getattr(OWNED[n], "__doc__", "x") is None])
show("new is first", lambda: ORDER[0])
show("doc is last", lambda: ORDER[-1])
show("repr follows new", lambda: offset("__new__", "__repr__"))
show("hash follows repr", lambda: offset("__repr__", "__hash__"))
show("six orderings", lambda: offset("__lt__", "__ge__"))
show("the add radd pair", lambda: offset("__add__", "__radd__"))
show("number slots run to rtruediv", lambda: offset("__add__", "__rtruediv__"))
show("members follow the wrappers", lambda: offset("__rtruediv__", "from_number"))
show("members run to format", lambda: offset("from_number", "__format__"))
show("two properties then doc", lambda: [offset("__format__", "real"), offset("real", "imag"), offset("imag", "__doc__")])
show("what is not filed", lambda: [n for n in ("__str__", "__init__", "__dir__", "__getattribute__", "__sizeof__", "__setformat__", "__slots__", "__index__", "__and__", "__matmul__") if not filed(n)])
show("setformat is not inherited either", lambda: getattr(float, "__setformat__", "absent"))
show("str comes from object", lambda: float.__str__ is object.__str__)
show("sizeof comes from object", lambda: float.__sizeof__ is object.__sizeof__)
show("init comes from object", lambda: float.__init__ is object.__init__)
show("dir comes from object", lambda: float.__dir__ is object.__dir__)
show("no bitwise slots", lambda: [n for n in ("__and__", "__or__", "__xor__", "__lshift__", "__invert__", "__index__", "__matmul__", "__divmod__") if not filed(n)])

print("== the table by kind ==")
show("raw", lambda: of("builtin_function_or_method"))
show("wrappers", lambda: len(of("wrapper_descriptor")))
show("members", lambda: len([n for n in ORDER if kinds(n) in ("method_descriptor", "classmethod_descriptor")]))
show("class rows", lambda: of("classmethod_descriptor"))
show("method rows", lambda: len(of("method_descriptor")))
show("getsets", lambda: of("getset_descriptor"))
show("str rows", lambda: of("str"))
show("the four kinds", lambda: sorted({kinds(n) for n in ORDER}))
show("every row has an objclass but one", lambda: [n for n in ORDER if not hasattr(OWNED[n], "__objclass__")])
show("every objclass is float", lambda: sorted({OWNED[n].__objclass__ is float for n in ORDER if hasattr(OWNED[n], "__objclass__")}))
show("every qualname is float's", lambda: sorted({OWNED[n].__qualname__.split(".")[0] for n in ORDER if hasattr(OWNED[n], "__qualname__")}))
show("no row is bound", lambda: [n for n in ORDER if hasattr(OWNED[n], "__self__")])

print("== all forty-seven rows, as the table files them ==")
for name in ORDER:
    print(line(name))

print("== what the kinds answer when read back ==")
show("a wrapper reads as a wrapper", lambda: repr(OWNED["__add__"]))
show("a method reads by name", lambda: repr(OWNED["hex"]))
show("a class row reads by name", lambda: repr(OWNED["fromhex"]))
show("a getset reads as an attribute", lambda: repr(OWNED["real"]))
show("reading a class row off the class", lambda: repr(float.fromhex).startswith("<built-in method fromhex of type object"))
show("reading a method off the class", lambda: float.hex is OWNED["hex"])
show("reading a wrapper off the class", lambda: float.__add__ is OWNED["__add__"])
show("reading a getset off the class", lambda: float.real is OWNED["real"])
show("bound carries the value", lambda: VALUE.hex.__self__ is VALUE)
show("bound is not shared", lambda: (1.5).hex is not (2.5).hex)
show("wrapper of a value", lambda: type((1.5).__add__).__name__)
show("equal descriptors", lambda: float.__add__ == OWNED["__add__"])
show("bound to nothing", lambda: OWNED["real"].__get__(None, float))

print("== the six that hand back the receiver ==")
show("pos", lambda: (+VALUE) is VALUE)
show("float", lambda: float(VALUE) is VALUE)
show("dunder float", lambda: VALUE.__float__() is VALUE)
show("conjugate", lambda: VALUE.conjugate() is VALUE)
show("real", lambda: VALUE.real is VALUE)
show("from_number", lambda: float.from_number(VALUE) is VALUE)
show("new", lambda: float.__new__(float, VALUE) is VALUE)
show("imag is not the receiver", lambda: VALUE.imag is VALUE)
show("imag is a float", lambda: type(VALUE.imag) is float)
show("imag answers zero", lambda: [VALUE.imag, (-VALUE).imag, (0.0).imag])
show("imag is not shared", lambda: (1.0).imag is not (2.0).imag)
show("real of a negative", lambda: [(-VALUE).real, (-VALUE).imag, -VALUE])
show("the four that are one function", lambda: [kinds(n) for n in ("__pos__", "__float__", "conjugate", "real")])

print("== the sentence each filed name carries ==")
for name in ORDER:
    print("%-20s %r" % (name, getattr(OWNED[name], "__doc__", "-")))
