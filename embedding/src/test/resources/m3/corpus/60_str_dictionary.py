"""The dictionary ``str`` files, and the four names at its tail that are not methods.

``len(str.__dict__)`` is seventy, and the order is CPython's own rather than anything alphabetical:
``__new__``, the eighteen slot wrappers that ``tp_richcompare``, ``tp_as_number`` and ``tp_as_sequence`` are
filed as, then the forty-six methods of ``unicode_methods`` in the order that table declares them — which is
the order a person reads the docs in, ``encode`` first and ``format_map`` last — then ``__format__``,
``maketrans``, ``__sizeof__`` and ``__getnewargs__``, then ``__doc__``. Every one of those seventy is listed
below with the kind of object filed under it, the ``__qualname__`` it answers (a dash where it answers none),
and whether it has a ``__self__`` or an ``__objclass__``.

Two comparisons with ``bytes``' own namespace are worth making, because both are about slots rather than
methods. ``bytes`` files nineteen wrappers and ``str`` eighteen: the name ``bytes`` has and ``str`` has not is
``__buffer__``, the slot that hands a value's memory to a caller — a str is its own characters and offers no
buffer. And where ``bytes`` files its two pickle names *before* its method table, ``str`` files
``__getnewargs__`` and ``__sizeof__`` *after* it, beside ``__format__`` and ``maketrans``; the four tail names
are what this case is mostly about. ``__format__`` is a ``method_descriptor`` like the methods above it,
``maketrans`` the one ``staticmethod`` in the table, ``__sizeof__`` and ``__getnewargs__`` builtins that answer
``__doc__`` of their own.

The wrappers run the same four gates ``bytes``' do, in the same order — missing receiver, receiver of the
wrong type, a keyword at all, the wrong count — and a call that fails three of them names only the first. The
method_descriptor half of the table checks its receiver with the other sentence, ``descriptor 'upper' for
'str' objects doesn't apply to a 'bytes' object``. ``__mod__`` is the one slot whose *result* is what a caller
came for: ``str.__dict__["__mod__"]("%s!", "ab")`` answers ``'ab!'``, and ``__rmod__`` reflected passes its
second argument the formatting job rather than its first, so ``str.__dict__["__rmod__"]("ab", "%s!")`` is the
same string; a receiver that is not a string declines to ``NotImplemented`` and leaves the operator to the
other operand.

What is *not* here is what the entries say about themselves: the forty-six ``ml_doc`` sentences of
``unicode_methods`` are text this runtime does not carry, so no line prints a method's ``__doc__``, a bound
``repr()`` (CPython's names an address), the size ``__sizeof__`` works out (CPython's counts the allocator's
header), or a hash.
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
    return "%-22s %-24s %-22s self=%-5s objclass=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__self__"),
        hasattr(value, "__objclass__"),
    )


TEXT = str.__dict__
OWNED = bytes.__dict__
sample = "ab"

print("== all seventy names, as the table files them ==")
for name in TEXT:
    print(line(str, name))

print("== the shape of the namespace ==")
show("len", lambda: len(TEXT))
show("first", lambda: list(TEXT)[0])
show("last", lambda: list(TEXT)[-1])
show("tail four", lambda: list(TEXT)[-5:-1])
show("wrapper count", lambda: [n for n in TEXT if type(TEXT[n]).__name__ == "wrapper_descriptor"])
show("bytes has a buffer", lambda: "__buffer__" in OWNED)
show("str does not", lambda: "__buffer__" in TEXT)
show("methods before the tail", lambda: [n for n in list(TEXT)[19:23]])
show("filed once", lambda: TEXT["upper"] is TEXT["upper"])
show("read agrees", lambda: str.upper is TEXT["upper"])
show("value read differs", lambda: "ab".upper is str.upper)
show("inherited names are not filed", lambda: [n for n in ("__init__", "__call__", "__reduce__")
                                              if n in TEXT])
show("a value answers them anyway", lambda: [n for n in ("__init__", "__call__", "__reduce__")
                                             if hasattr(sample, n)])

print("== the four gates, on this side ==")
show("no receiver", lambda: TEXT["__len__"]())
show("wrong receiver", lambda: TEXT["__len__"](b"ab"))
show("keyword", lambda: TEXT["__len__"]("ab", x=1))
show("too many", lambda: TEXT["__len__"]("ab", "cd"))
show("receiver before keyword", lambda: TEXT["__len__"](b"ab", x=1))
show("keyword does not become a receiver", lambda: TEXT["__len__"](x=b"ab"))
show("missing the second argument", lambda: TEXT["__contains__"]("ab"))
show("len", lambda: TEXT["__len__"]("abcd"))
show("index", lambda: TEXT["__getitem__"]("abcd", 2))
show("slice", lambda: TEXT["__getitem__"]("abcd", slice(1, 3)))
show("step slice", lambda: TEXT["__getitem__"]("abcdef", slice(None, None, 2)))
show("repr", lambda: TEXT["__repr__"]("ab"))
show("str", lambda: TEXT["__str__"](sample))
show("hash agrees", lambda: TEXT["__hash__"](sample) == hash(sample))
show("iter", lambda: list(TEXT["__iter__"]("ab")))
show("contains", lambda: TEXT["__contains__"]("ab", "a"))
show("contains a substring", lambda: TEXT["__contains__"]("abc", "bc"))
show("add", lambda: TEXT["__add__"]("a", "b"))
show("mul", lambda: TEXT["__mul__"]("ab", 2))
show("rmul", lambda: TEXT["__rmul__"]("ab", 3))
show("eq", lambda: TEXT["__eq__"](sample, "ab"))
show("le", lambda: TEXT["__le__"]("ab", "b"))
show("gt", lambda: TEXT["__gt__"]("ab", "b"))

print("== what the slots refuse ==")
show("add a bytes", lambda: TEXT["__add__"]("ab", b"ab"))
show("mul a float", lambda: TEXT["__mul__"]("ab", 1.5))
show("index a str", lambda: TEXT["__getitem__"]("ab", "a"))
show("index past the end", lambda: TEXT["__getitem__"]("ab", 9))
show("contains a bytes", lambda: TEXT["__contains__"]("ab", b"a"))
show("eq declines", lambda: TEXT["__eq__"](sample, 1) is NotImplemented)
show("cmp declines", lambda: TEXT["__lt__"](sample, 1) is NotImplemented)
show("operator answers False", lambda: sample == 1)
show("upper on a bytes", lambda: TEXT["upper"](b"ab"))
show("upper on an int", lambda: TEXT["upper"](42))
show("casefold through the table", lambda: TEXT["casefold"]("AB"))
show("objclass", lambda: TEXT["upper"].__objclass__ is str)
show("qualname", lambda: TEXT["upper"].__qualname__)
show("bound objclass", lambda: hasattr("ab".upper, "__objclass__"))

print("== the two formatting slots ==")
show("mod", lambda: TEXT["__mod__"]("%s!", "ab"))
show("mod a tuple", lambda: TEXT["__mod__"]("%d-%d", (1, 2)))
show("mod an int", lambda: TEXT["__mod__"]("%d", 3))
show("mod a bytes", lambda: TEXT["__mod__"]("%s", b"ab"))
show("mod wrong receiver", lambda: TEXT["__mod__"](b"ab", 1))
show("rmod", lambda: TEXT["__rmod__"]("ab", "%s!"))
show("rmod a str slot", lambda: TEXT["__rmod__"]("ab", "cd"))
show("rmod other side", lambda: OWNED["__rmod__"](b"ab", 1))
show("no second argument", lambda: TEXT["__mod__"]("%s"))
show("three arguments", lambda: TEXT["__mod__"]("%s", "a", "b"))
show("format empty spec", lambda: TEXT["__format__"](sample, ""))
show("format width", lambda: TEXT["__format__"]("ab", ">6"))
show("format center", lambda: TEXT["__format__"](sample, "^8"))
show("format zero fill", lambda: TEXT["__format__"](sample, "08"))
show("format an int spec", lambda: TEXT["__format__"](sample, 6))
show("format no spec", lambda: TEXT["__format__"](sample))
show("format two specs", lambda: TEXT["__format__"](sample, "", ""))
show("format wrong receiver", lambda: TEXT["__format__"](b"ab", ""))
show("a value formats", lambda: format(sample, "<4|"))
show("format is not the builtin", lambda: TEXT["format"] is TEXT["__format__"])

print("== __new__, and the str it is asked to make ==")
show("kind", lambda: type(TEXT["__new__"]).__name__)
show("doc", lambda: TEXT["__new__"].__doc__)
show("nothing", lambda: TEXT["__new__"]())
show("not a type", lambda: TEXT["__new__"]("ab"))
show("bytes asked", lambda: TEXT["__new__"](bytes, b"ab"))
show("built", lambda: TEXT["__new__"](str, "ab"))
show("built a count", lambda: TEXT["__new__"](str, 2))
show("read is the entry", lambda: str.__new__ is TEXT["__new__"])
show("bound", lambda: type(str.__new__).__name__)

print("== the static method, and what its wrapper keeps ==")
make = TEXT["maketrans"]
show("kind", lambda: type(make).__name__)
show("unwraps", lambda: make.__func__ is str.maketrans)
show("no qualname", lambda: hasattr(make, "__qualname__"))
show("no name", lambda: hasattr(make, "__name__"))
show("no module", lambda: hasattr(make, "__module__"))
show("abstract flag", lambda: make.__isabstractmethod__)
show("called through the table", lambda: make({97: 98}))
show("value read is the function", lambda: "ab".maketrans is str.maketrans)
show("function qualname", lambda: str.maketrans.__qualname__)
show("self is none", lambda: str.maketrans.__self__)
show("no objclass", lambda: hasattr(str.maketrans, "__objclass__"))
show("one dict", lambda: str.maketrans({97: 98}))
show("one str", lambda: str.maketrans("ab"))
show("two", lambda: str.maketrans("ab", "cd"))
show("three", lambda: str.maketrans("ab", "cd", "e"))
show("second too long", lambda: str.maketrans("ab", "cde"))
show("third too long", lambda: str.maketrans("ab", "cd", "ef"))
show("nothing", lambda: str.maketrans())
show("a str and a dict", lambda: str.maketrans("ab", {97: 98}))
show("four arguments", lambda: str.maketrans("ab", "cd", "e", "f"))
show("keyword", lambda: str.maketrans(x="ab"))

print("== the two tail names a pickle asks for ==")
show("sizeof kind", lambda: type(TEXT["__sizeof__"]).__name__)
show("sizeof doc kind", lambda: type(TEXT["__sizeof__"].__doc__).__name__)
show("sizeof no receiver", lambda: TEXT["__sizeof__"]())
show("sizeof wrong receiver", lambda: TEXT["__sizeof__"](b"ab"))
show("sizeof too many", lambda: TEXT["__sizeof__"](sample, 1))
show("sizeof answers an int", lambda: type(TEXT["__sizeof__"](sample)).__name__)
show("getnewargs kind", lambda: type(TEXT["__getnewargs__"]).__name__)
show("getnewargs", lambda: TEXT["__getnewargs__"](sample))
show("getnewargs wrong receiver", lambda: TEXT["__getnewargs__"](b"ab"))
show("getnewargs extra", lambda: TEXT["__getnewargs__"](sample, 1))
show("a value answers them", lambda: (sample.__getnewargs__(), type(sample.__sizeof__()).__name__))
show("bytes files them first", lambda: list(OWNED)[:3] == ["__new__", "__repr__", "__hash__"])
show("str files getnewargs last", lambda: list(TEXT).index("__getnewargs__") == len(TEXT) - 2)
