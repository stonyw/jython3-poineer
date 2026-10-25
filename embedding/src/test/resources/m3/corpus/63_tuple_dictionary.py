"""The dictionary ``tuple`` files, and the same generic subscript read through an immutable sequence.

``len(tuple.__dict__)`` is twenty-one: ``__new__``, the fifteen slots a sequence is measured by, then
``__getnewargs__``, then the two methods of ``tuple_methods``, then the generic subscript and the type's own
sentence. Fifteen wrappers against ``list``'s eleven, and the difference is what a type is allowed to change:
there is no ``__init__``, no ``__setitem__``, no ``__delitem__`` and no in-place slot, because a tuple is
built by ``__new__`` alone and nothing may be done to it afterwards. The one sentence ``tuple_doc`` writes
about the constructor — that the return value is the same object when the argument is a tuple — is
``PySequence_Tuple``'s ``PyTuple_CheckExact``, and so it holds for a tuple and not for a subclass of one.

Two names read best against ``list``, which files them as different kinds of object. ``__hash__`` is a slot
here rather than ``None`` — the same name is the whole difference between a dictionary key and the
``TypeError`` a list gets. ``__getitem__`` is the ``sq_item`` copy rather than ``list``'s
``method_descriptor``, so its count is refused with the bare ``expected 1 argument, got 0`` where the list
answers ``list.__getitem__() takes exactly one argument (0 given)``, and its receiver with the
``requires a 'tuple' object`` sentence where the list says ``doesn't apply to``. ``__sizeof__`` and
``__reversed__`` are not filed at all: the first is inherited from ``object``, the second is not needed by
a type the sequence protocol can already walk backwards.

``__getnewargs__`` is the entry to look at sideways, because it is the only one in either table that CPython
files with no sentence at all — its ``ml_doc`` is a clinic signature header with nothing after it, which
``_PyType_GetDocFromInternalDoc`` answers as ``None``, so the deviation the other methods carry is the
reference's own answer here. It hands pickle the tuple it was called on as the one argument ``__new__``
rebakes it from, which is why the answer of ``(1, 2).__getnewargs__()`` is ``((1, 2),)``.

What is *not* here is what the entries say about themselves: the ``ml_doc`` sentences of ``tuple_methods``
are text this runtime does not carry, so no line prints a method's ``__doc__`` where the table has one, a
bound ``repr()`` (CPython's names an address), the size ``object.__sizeof__`` works out, the type a
``reversed()`` call hands back, or a hash — a hash is only ever compared with the slot's own answer. The one
sentence the table does carry is ``__class_getitem__``'s, which the generic subscript reads back whole: a
tuple is the other family whose shape is written down, because ``tuple[T, ...]`` is a type with a length the
arguments choose.
"""


import sys


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


OWNED = tuple.__dict__
pair = (1, 2)
empty = ()

print("== the namespace of an immutable sequence ==")
show("kind", lambda: type(OWNED).__name__)
show("len", lambda: len(OWNED))
show("new is first", lambda: list(OWNED)[0])
show("doc is last", lambda: list(OWNED)[-1])
show("hash is a wrapper", lambda: type(OWNED["__hash__"]).__name__)
show("hash is callable", lambda: callable(tuple.__hash__))
show("a list files none", lambda: type(list.__dict__["__hash__"]).__name__)
show("getitem is a wrapper", lambda: type(OWNED["__getitem__"]).__name__)
show("list getitem is not", lambda: type(list.__dict__["__getitem__"]).__name__)
show("filed once", lambda: OWNED["count"] is OWNED["count"])
show("read agrees", lambda: tuple.count is OWNED["count"])
show("value read differs", lambda: pair.count is tuple.count)
show("value read twice", lambda: pair.count == pair.count)
show("doc is a string", lambda: type(OWNED["__doc__"]).__name__)
show("names not filed", lambda: [n for n in ("__init__", "__setitem__", "__iadd__", "__reversed__",
                                            "__sizeof__") if n not in OWNED])
show("sizeof is inherited", lambda: tuple.__sizeof__ is object.__sizeof__)
show("init is inherited", lambda: tuple.__init__ is object.__init__)

print("== all twenty-one names, as the table files them ==")
for name in OWNED:
    print(line(tuple, name))

print("== the gates a wrapper runs, and the bare count it gives ==")
show("len no argument", lambda: OWNED["__len__"]())
show("len wrong receiver", lambda: OWNED["__len__"](42))
show("len keyword", lambda: OWNED["__len__"](pair, x=1))
show("len too many", lambda: OWNED["__len__"](pair, 1))
show("getitem no argument", lambda: OWNED["__getitem__"](pair))
show("getitem two", lambda: OWNED["__getitem__"](pair, 0, 1))
show("getitem wrong receiver", lambda: OWNED["__getitem__"](42, 0))
show("getitem answers", lambda: OWNED["__getitem__"](pair, 1))
show("getitem slices", lambda: OWNED["__getitem__"](pair, slice(0, 2)))
show("getitem negative", lambda: OWNED["__getitem__"](pair, -1))
show("getitem out of range", lambda: OWNED["__getitem__"](pair, 9))
show("list getitem says otherwise", lambda: list.__dict__["__getitem__"]([1]))
show("hash no receiver", lambda: OWNED["__hash__"]())
show("hash agrees with the builtin", lambda: OWNED["__hash__"](pair) == hash(pair))
show("hash of empty agrees", lambda: OWNED["__hash__"](empty) == hash(empty))
show("bound hash kind", lambda: type(pair.__hash__).__name__)
show("iter", lambda: list(OWNED["__iter__"](pair)))
show("contains", lambda: OWNED["__contains__"](pair, 2))
show("contains text", lambda: OWNED["__contains__"](pair, "1"))
show("repr", lambda: OWNED["__repr__"](pair))
show("repr of one", lambda: OWNED["__repr__"]((1,)))
show("eq declines", lambda: OWNED["__eq__"](pair, [1, 2]) is NotImplemented)
show("operator still answers", lambda: pair == [1, 2])
show("lt", lambda: OWNED["__lt__"](pair, (1, 2, 0)))

print("== the two operators a sequence is measured by ==")
show("add a tuple", lambda: OWNED["__add__"](pair, (3,)))
show("add a list", lambda: OWNED["__add__"](pair, [3]))
show("add an int", lambda: OWNED["__add__"](pair, 3))
show("mul", lambda: OWNED["__mul__"](pair, 2))
show("mul a float", lambda: OWNED["__mul__"](pair, 1.5))
show("mul text", lambda: OWNED["__mul__"](pair, "2"))
show("rmul", lambda: OWNED["__rmul__"](pair, 2))
show("rmul of a bool", lambda: OWNED["__rmul__"](pair, True))
show("in-place is refused", lambda: pair.__iadd__ is list.__iadd__)

print("== what the one half of the constructor reads ==")
show("new with a class only", lambda: tuple.__new__(tuple))
show("new reads the rest", lambda: tuple.__new__(tuple, "ab"))
show("new of a list", lambda: tuple.__new__(tuple, [1, 2]))
show("new ignores keywords", lambda: tuple.__new__(tuple, x=1))
show("new with two arguments", lambda: tuple.__new__(tuple, (1,), 3))
show("new with nothing", lambda: tuple.__new__())
show("new of another type", lambda: tuple.__new__(int, 3))
show("new of a non-type", lambda: tuple.__new__(3))
show("init of a value", lambda: pair.__init__(2))
show("the value read builds nothing", lambda: tuple(pair) is pair)
show("an exact tuple is kept", lambda: type(tuple(sys.version_info)).__name__)
show("a subclass is rebuilt", lambda: tuple(sys.version_info) is sys.version_info)

print("== the pickle name, and the two methods ==")
show("kind", lambda: type(OWNED["__getnewargs__"]).__name__)
show("doc is none", lambda: OWNED["__getnewargs__"].__doc__)
show("qualname", lambda: OWNED["__getnewargs__"].__qualname__)
show("answers", lambda: OWNED["__getnewargs__"](pair))
show("empty answers", lambda: OWNED["__getnewargs__"](empty))
show("bound", lambda: pair.__getnewargs__())
show("no receiver", lambda: OWNED["__getnewargs__"]())
show("wrong receiver", lambda: OWNED["__getnewargs__"]([1]))
show("extra argument", lambda: pair.__getnewargs__(1))
show("keyword", lambda: pair.__getnewargs__(x=1))
show("count", lambda: tuple.count((1, 1, 2), 1))
show("count missing", lambda: tuple.count(pair, 9))
show("index", lambda: tuple.index(pair, 2))
show("index missing", lambda: tuple.index(pair, 9))
show("index window", lambda: tuple.index((1, 2, 1), 1, 2))
show("index empty window", lambda: tuple.index(pair, 1, 1))
show("index keyword", lambda: pair.index(1, x=1))
show("count wrong receiver", lambda: tuple.count([1], 1))

print("== the generic subscript, on a fixed-length family ==")
show("kind", lambda: type(OWNED["__class_getitem__"]).__name__)
show("doc", lambda: OWNED["__class_getitem__"].__doc__)
show("objclass", lambda: OWNED["__class_getitem__"].__objclass__ is tuple)
show("bound self", lambda: tuple.__class_getitem__.__self__ is tuple)
show("unbound no argument", lambda: OWNED["__class_getitem__"]())
show("unbound non-type class", lambda: OWNED["__class_getitem__"](3, int))
show("unbound foreign class", lambda: OWNED["__class_getitem__"](list, int))
show("no argument", lambda: tuple.__class_getitem__())
show("three arguments", lambda: tuple.__class_getitem__(int, str, int))
show("keyword", lambda: tuple.__class_getitem__(int, x=1))
show("one", lambda: tuple.__class_getitem__(int))
show("two subscripted", lambda: tuple[int, str])
show("none subscripted", lambda: tuple[()])
show("starred tail", lambda: tuple[int, ...])
show("not a class", lambda: tuple[3])
show("nested in a list", lambda: list[tuple[int, str]])
show("origin and args", lambda: (tuple[int].__origin__ is tuple, tuple[int, str].__args__))
show("no parameters", lambda: tuple[int, str].__parameters__)
show("read twice", lambda: tuple[int, str] == tuple[int, str])
show("order matters", lambda: tuple[int, str] == tuple[str, int])
show("as a dict key", lambda: {tuple[int]: 1}[tuple[int]])
show("the hash it is made of", lambda: hash(tuple[int]) == hash(tuple) ^ hash((int,)))
show("starred hash", lambda: hash(tuple[int, ...]) == hash(tuple) ^ hash((int, Ellipsis)))
show("subscripting an alias", lambda: tuple[int][0])
show("the type itself", lambda: type(tuple[int]))
