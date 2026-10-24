"""The dictionary ``bytes`` files, and what each of its sixty-five names does when called.

A built-in type's namespace is not a summary of what its values can do: it is the list of names the
mapping proxy hands out, and CPython fills it in the order its own tables say — ``__new__`` first, then the
nineteen slot wrappers ``tp_richcompare`` and friends are filed as, then the two names the pickling protocol
asks a value for, then the forty-two entries of ``bytes_methods`` in the order that table declares them (which
is alphabetical, with ``maketrans`` filed among them as a ``staticmethod``), then ``__doc__``.
``len(bytes.__dict__)`` is sixty-five because that is what the table holds and nothing else: ``bytes`` files no
``__init__``, no ``__call__`` and no ``__reduce__`` of its own, and those are read from ``object`` and from the
value class instead. All sixty-five are listed below, in that order, with the kind of object filed under each,
the ``__qualname__`` it answers — or a dash where it answers none — and whether it has a ``__self__`` or an
``__objclass__`` to answer at all.

The entries are one object each, and the reads agree: ``bytes.upper is bytes.__dict__["upper"]`` and ``bytes.upper
is bytes.upper`` both hold, because the table hands out the same descriptor every time. That is what makes the
unbound call interesting rather than academic. A ``wrapper_descriptor`` called with nothing complains
``descriptor '__len__' of 'bytes' object needs an argument``; called with an argument that is not a bytes, it
complains ``descriptor '__len__' requires a 'bytes' object but received a 'int'``; given a keyword it complains
``wrapper __len__() takes no keyword arguments``; and given two arguments it complains about the count. Those
are four gates, and they run in that order, so a call that gets three of them wrong names only the first. A
``method_descriptor`` such as ``upper`` checks its receiver the same way but says ``descriptor 'upper' for
'bytes' objects doesn't apply to a 'str' object``, and ``bytes.__dict__["upper"]("ab")`` answers ``'AB'``.

Then the four names that are not methods of a value at all. ``bytes.__dict__["__new__"]`` is a
``builtin_function_or_method`` that asks for the type it is to build and refuses anything else.
``bytes.__dict__["__buffer__"]`` is the slot that hands a value's memory to a caller, and refuses a flags
argument that is not an integer, that is too wide for C's ``int``, or that asks for writability a bytes does not
offer. ``bytes.__dict__["fromhex"]`` is a ``classmethod_descriptor``, so its first argument has to be a type —
and a subtype of ``bytes`` at that — before the hex text is looked at, and ``__get__`` runs the same two checks.
``bytes.__dict__["maketrans"]`` is a ``staticmethod`` wrapping the function ``bytes.maketrans`` is, and that
wrapper is the one name in the table with no ``__qualname__``, no ``__name__`` and no ``__module__`` of its own,
because a method table files it without ever handing the function to ``functools.wraps``.

What is *not* here is what the entries say about themselves. A built-in method's ``__doc__`` is the sentence
its ``ml_doc`` carries, and this runtime carries none of those forty-two sentences, so no line below prints a
doc of a method, a ``repr()`` of a bound method (CPython's names an address), or the number a ``__hash__`` call
works out.
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


OWNED = bytes.__dict__
TEXT = str.__dict__
sample = b"ab"

print("== the namespace of a built-in type ==")
show("kind", lambda: type(OWNED).__name__)
show("len", lambda: len(OWNED))
show("str len", lambda: len(TEXT))
show("new is first", lambda: list(OWNED)[0])
show("doc is last", lambda: list(OWNED)[-1])
show("upper filed once", lambda: OWNED["upper"] is OWNED["upper"])
show("read agrees", lambda: bytes.upper is OWNED["upper"])
show("twice read", lambda: bytes.upper is bytes.upper)
show("value read differs", lambda: b"ab".upper is bytes.upper)
show("value read twice", lambda: b"ab".upper == b"ab".upper)
show("doc is a string", lambda: type(OWNED["__doc__"]).__name__)
show("in", lambda: [n for n in ("upper", "__len__", "__buffer__", "__init__", "__call__") if n in OWNED])
show("not filed", lambda: [n for n in ("__init__", "__call__", "__reduce__", "__iter__")
                           if n not in OWNED])

print("== all sixty-five names, as the table files them ==")
for name in OWNED:
    print(line(bytes, name))

print("== the four gates an unbound wrapper runs ==")
show("no argument", lambda: OWNED["__len__"]())
show("wrong receiver", lambda: OWNED["__len__"](42))
show("keyword", lambda: OWNED["__len__"](b"ab", x=1))
show("too many", lambda: OWNED["__len__"](b"ab", b"c"))
show("gate order keyword then receiver", lambda: OWNED["__len__"](42, x=1))
show("gate order receiver then count", lambda: OWNED["__len__"](42, 43, 44))
show("no argument, keyword only", lambda: OWNED["__len__"](x=1))
show("arity names itself", lambda: OWNED["__buffer__"](b"ab", 0, 0))
show("wrapped call", lambda: OWNED["__len__"](b"abcd"))
show("wrapped index", lambda: OWNED["__getitem__"](b"abcd", 2))
show("wrapped slice", lambda: OWNED["__getitem__"](b"abcd", slice(1, 3)))
show("wrapped repr", lambda: OWNED["__repr__"](b"ab"))
show("wrapped str", lambda: OWNED["__str__"](b"ab"))
show("wrapped hash is an int", lambda: type(OWNED["__hash__"](b"ab")).__name__)
show("wrapped hash agrees", lambda: OWNED["__hash__"](b"ab") == hash(b"ab"))
show("wrapped iter", lambda: list(OWNED["__iter__"](b"ab")))
show("wrapped contains", lambda: OWNED["__contains__"](b"ab", 97))
show("wrapped add", lambda: OWNED["__add__"](b"a", b"b"))
show("wrapped mul", lambda: OWNED["__mul__"](b"ab", 2))
show("wrapped rmul", lambda: OWNED["__rmul__"](b"ab", 2))
show("wrapped eq", lambda: OWNED["__eq__"](b"ab", b"ab"))
show("wrapped lt", lambda: OWNED["__lt__"](b"ab", b"b"))
show("wrapped ge", lambda: OWNED["__ge__"](b"ab", b"b"))
show("wrapped ne", lambda: OWNED["__ne__"](b"ab", 1))

print("== what the slots refuse ==")
show("add an int", lambda: OWNED["__add__"](b"ab", 1))
show("mul a float", lambda: OWNED["__mul__"](b"ab", 2.5))
show("mul a str", lambda: OWNED["__mul__"](b"ab", "2"))
show("index an int", lambda: OWNED["__getitem__"](b"ab", 9))
show("index a float", lambda: OWNED["__getitem__"](b"ab", 1.5))
show("contains a str", lambda: OWNED["__contains__"](b"ab", "a"))
show("eq declines", lambda: OWNED["__eq__"](b"ab", 1) is NotImplemented)
show("operator still answers", lambda: b"ab" == 1)
show("upper on a str", lambda: OWNED["upper"]("ab"))
show("upper on an int", lambda: OWNED["upper"](42))
show("method repr kind", lambda: type(OWNED["upper"]).__name__)
show("objclass", lambda: OWNED["upper"].__objclass__ is bytes)
show("name", lambda: OWNED["upper"].__name__)
show("qualname", lambda: OWNED["upper"].__qualname__)
show("bound objclass", lambda: hasattr(b"ab".upper, "__objclass__"))

print("== the buffer slot ==")
show("no flags", lambda: OWNED["__buffer__"](b"ab"))
show("flags a str", lambda: OWNED["__buffer__"](b"ab", "x"))
show("flags an index", lambda: OWNED["__buffer__"](b"ab", 2 ** 63))
show("flags too wide", lambda: OWNED["__buffer__"](b"ab", 2 ** 32))
show("writable asked", lambda: OWNED["__buffer__"](b"ab", 1))
show("receiver wrong", lambda: OWNED["__buffer__"]("ab", 0))
show("receiver missing", lambda: OWNED["__buffer__"]())

print("== __new__ ==")
show("kind", lambda: type(OWNED["__new__"]).__name__)
show("doc", lambda: OWNED["__new__"].__doc__)
show("nothing", lambda: OWNED["__new__"]())
show("not a type", lambda: OWNED["__new__"](42))
show("str asked", lambda: OWNED["__new__"](str, "ab"))
show("bytes built", lambda: OWNED["__new__"](bytes, b"ab"))
show("bound through a class", lambda: OWNED["__new__"](bytes, 2))
show("attribute form", lambda: bytes.__new__(bytes, b"ab"))
show("read of new", lambda: bytes.__new__ is OWNED["__new__"])

print("== the class method, called from the table ==")
show("kind", lambda: type(OWNED["fromhex"]).__name__)
show("no argument", lambda: OWNED["fromhex"]())
show("receiver not a type", lambda: OWNED["fromhex"]("61"))
show("receiver a str type", lambda: OWNED["fromhex"](str, "61"))
show("bound call", lambda: OWNED["fromhex"](bytes, "61 62"))
show("read binds", lambda: OWNED["fromhex"].__get__(None, bytes).__self__ is bytes)
show("get with no class", lambda: OWNED["fromhex"].__get__(None, None))
show("get through a value", lambda: type(OWNED["fromhex"].__get__(b"ab")).__name__)
show("bound has no objclass", lambda: hasattr(bytes.fromhex, "__objclass__"))
show("objclass of the entry", lambda: OWNED["fromhex"].__objclass__ is bytes)
show("qualname", lambda: OWNED["fromhex"].__qualname__)

print("== the static method, and what its wrapper keeps ==")
make = OWNED["maketrans"]
show("kind", lambda: type(make).__name__)
show("unwraps", lambda: make.__func__ is bytes.maketrans)
show("wrapped name", lambda: make.__wrapped__ is bytes.maketrans)
show("no qualname", lambda: hasattr(make, "__qualname__"))
show("no name", lambda: hasattr(make, "__name__"))
show("no module", lambda: hasattr(make, "__module__"))
show("abstract flag", lambda: make.__isabstractmethod__)
show("callable", lambda: callable(make))
show("called through the table", lambda: make({97: 98}))
show("value read is the function", lambda: b"ab".maketrans is bytes.maketrans)
show("function qualname", lambda: bytes.maketrans.__qualname__)
show("self is none", lambda: bytes.maketrans.__self__)
show("no objclass", lambda: hasattr(bytes.maketrans, "__objclass__"))
show("two arguments", lambda: bytes.maketrans("ab", "cd"))
show("one argument", lambda: bytes.maketrans({97: 98}))
show("one str", lambda: bytes.maketrans("ab"))
show("three arguments", lambda: bytes.maketrans("ab", "cd", "e"))
show("second too long", lambda: bytes.maketrans("ab", "cde"))
show("third not int", lambda: bytes.maketrans("ab", "cd", "ef"))

print("== the two names the pickle protocol asks for ==")
show("getnewargs kind", lambda: type(OWNED["__getnewargs__"]).__name__)
show("getnewargs", lambda: OWNED["__getnewargs__"](b"ab"))
show("getnewargs extra", lambda: OWNED["__getnewargs__"](b"ab", 1))
show("getnewargs wrong", lambda: OWNED["__getnewargs__"]("ab"))
show("bytes dunder kind", lambda: type(OWNED["__bytes__"]).__name__)
show("bytes dunder", lambda: OWNED["__bytes__"](b"ab"))
show("bytes dunder identity", lambda: OWNED["__bytes__"](sample) is sample)
show("bytes dunder extra", lambda: OWNED["__bytes__"](b"ab", 1))
show("bytes dunder wrong", lambda: OWNED["__bytes__"]("ab"))
show("a value answers them", lambda: (b"ab".__getnewargs__(), b"ab".__bytes__()))
show("bytes of a str", lambda: "ab".__bytes__())
