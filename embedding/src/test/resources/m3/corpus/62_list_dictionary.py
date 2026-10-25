"""The dictionary ``list`` files, and the generic subscript the two sequence types answer.

``len(list.__dict__)`` is thirty-six: ``__new__``, the eleven slot wrappers that carry a mutable sequence,
then ``__getitem__`` as a ``method_descriptor`` — the one sequence subscript that is not copied out of
``sq_item`` — then ``__setitem__``, ``__delitem__``, the four operators, the two in-place slots, the two
names a value type files beside its own table, the eleven methods of ``list_methods``, the generic
subscript and the type's sentence. Every one is listed below with the kind of object filed under it, the
``__qualname__`` it answers, and whether it has a ``__self__`` or an ``__objclass__``.

Two of those names are worth reading against ``bytes`` and ``tuple``, because both are about what a slot is
allowed to be. ``__hash__`` is filed as ``None`` rather than as a wrapper: a list is mutable, so the inherited
``object.__hash__`` is pushed out of reach by the dictionary itself, and ``hash([])`` complains about
unhashability without ever looking for a callable. ``__getitem__`` is a method rather than the ``sq_item``
copy ``bytes`` and ``tuple`` file, so it counts its arguments with the ``METH_O`` message
(``list.__getitem__() takes exactly one argument (0 given)``) and refuses a wrong receiver with the
``method_descriptor`` sentence, where the other two answer the count bare.

The subscript of a class is a third shape again. ``list.__class_getitem__`` is a ``classmethod_descriptor``,
so an entry read out of the dictionary takes its class as an argument and complains before it counts
anything — first that a class was wanted at all, then that the argument was a type, then that the type it got
is one the method could never build a value of — while a read through the type supplies that class and hands
the counting to the ``METH_O`` under it. What comes back is a ``types.GenericAlias``: the origin, the arguments, no parameters, and a
``repr`` that writes a class by its qualified name and anything else by its own — ``list[int]``,
``list[int, str]``, ``list[()]`` for no arguments at all, and ``list[3]`` for an argument that is not a
class. Subscripting the alias is the one thing it refuses.

What is *not* here is what the entries say about themselves: the eleven ``ml_doc`` sentences of
``list_methods`` are text this runtime does not carry, so no line prints a method's ``__doc__``, a bound
``repr()`` (CPython's names an address), the size ``__sizeof__`` works out (CPython's counts the allocator's
header), the type a ``reversed()`` call hands back (CPython names it), or a hash as a number. The one hash
that is written down is read as a relationship: ``ga_hash`` XORs the origin's hash with the hash of the
arguments as one tuple, so ``hash(list[int]) == hash(list) ^ hash((int,))`` is a fact about the value that
holds whatever the two numbers happen to be.
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


OWNED = list.__dict__
sample = [1, 2, 3]

print("== the namespace of a mutable sequence ==")
show("kind", lambda: type(OWNED).__name__)
show("len", lambda: len(OWNED))
show("new is first", lambda: list(OWNED)[0])
show("doc is last", lambda: list(OWNED)[-1])
show("getitem is between", lambda: list(OWNED).index("__getitem__"))
show("hash is none", lambda: OWNED["__hash__"] is None)
show("hash is not callable", lambda: callable(list.__hash__))
show("inherited hash is gone", lambda: list.__hash__ is not object.__hash__)
show("filed once", lambda: OWNED["append"] is OWNED["append"])
show("read agrees", lambda: list.append is OWNED["append"])
show("twice read", lambda: list.append is list.append)
show("value read differs", lambda: sample.append is list.append)
show("value read twice", lambda: sample.append == sample.append)
show("doc is a string", lambda: type(OWNED["__doc__"]).__name__)
show("names in", lambda: [n for n in ("upper", "__len__", "__init__", "__call__") if n in OWNED])
show("names not filed", lambda: [n for n in ("__call__", "__reduce__", "__buffer__", "__mod__")
                                 if n not in OWNED])

print("== all thirty-six names, as the table files them ==")
for name in OWNED:
    print(line(list, name))

print("== the four gates an unbound wrapper runs ==")
show("no argument", lambda: OWNED["__len__"]())
show("wrong receiver", lambda: OWNED["__len__"](42))
show("keyword", lambda: OWNED["__len__"](sample, x=1))
show("too many", lambda: OWNED["__len__"](sample, 1))
show("gate order keyword then receiver", lambda: OWNED["__len__"](42, x=1))
show("gate order receiver then count", lambda: OWNED["__len__"](42, 43, 44))
show("wrapped repr", lambda: OWNED["__repr__"](sample))
show("wrapped iter", lambda: list(OWNED["__iter__"](sample)))
show("wrapped contains", lambda: OWNED["__contains__"](sample, 2))
show("wrapped eq", lambda: OWNED["__eq__"](sample, [1, 2, 3]))
show("wrapped lt", lambda: OWNED["__lt__"](sample, [1]))
show("wrapped ge declines", lambda: OWNED["__ge__"](sample, 1) is NotImplemented)
show("operator still answers", lambda: sample >= 1)
show("wrapped hash refuses", lambda: OWNED["__hash__"](sample))

print("== the three names that count their arguments themselves ==")
show("getitem none", lambda: OWNED["__getitem__"](sample))
show("getitem two", lambda: OWNED["__getitem__"](sample, 0, 1))
show("getitem wrong receiver", lambda: OWNED["__getitem__"](42, 0))
show("getitem keyword", lambda: sample.__getitem__(x=1))
show("getitem answers", lambda: OWNED["__getitem__"](sample, 1))
show("getitem slices", lambda: OWNED["__getitem__"](sample, slice(0, 2)))
show("getitem kind", lambda: type(OWNED["__getitem__"]).__name__)
show("setitem none", lambda: OWNED["__setitem__"](sample))
show("setitem one", lambda: OWNED["__setitem__"](sample, 0))
show("setitem three", lambda: OWNED["__setitem__"](sample, 0, 1, 2))
show("setitem keyword", lambda: sample.__setitem__(0, 1, x=2))
show("setitem wrong receiver", lambda: OWNED["__setitem__"](42, 0, 1))
show("delitem none", lambda: OWNED["__delitem__"](sample))
show("delitem two", lambda: OWNED["__delitem__"](sample, 0, 1))
show("delitem answers", lambda: OWNED["__delitem__"](sample, 2) or sample)

print("== what the two halves of the constructor read ==")
show("new with a class only", lambda: list.__new__(list))
show("new ignores the rest", lambda: list.__new__(list, "ab"))
show("new ignores keywords", lambda: list.__new__(list, x=1))
show("new with nothing", lambda: list.__new__())
show("new of another type", lambda: list.__new__(int, 3))
show("new of a non-type", lambda: list.__new__(3))
show("init with a class", lambda: OWNED["__init__"](sample))
show("init two", lambda: sample.__init__(2, 3))
show("init keyword", lambda: sample.__init__(x=1))
show("init not iterable", lambda: sample.__init__(3))
show("init of itself doubles", lambda: sample.__init__(sample) or sample)
show("init of text", lambda: list("ab"))

print("== the two in-place slots keep the one list ==")
head = [1]
show("iadd identity", lambda: head.__iadd__([2]) is head)
show("iadd answers", lambda: head)
show("imul identity", lambda: head.__imul__(2) is head)
show("imul answers", lambda: head)
show("iadd of an int", lambda: head.__iadd__(3))
show("imul of text", lambda: head.__imul__("a"))
show("imul of a float", lambda: head.__imul__(1.5))
show("imul of a bool", lambda: head.__imul__(True))
show("mul declines", lambda: head.__mul__(1.5))

print("== the eleven methods, run through the descriptor ==")
box = [3, 1, 2]
show("append", lambda: list.append(box, 4) or box)
show("append wrong receiver", lambda: list.append("ab", 4))
show("append no argument", lambda: list.append(box))
show("extend", lambda: list.extend(box, (5,)) or box)
show("insert", lambda: list.insert(box, 0, 0) or box)
show("insert expected", lambda: list.insert(box, 0))
show("remove", lambda: list.remove(box, 3) or box)
show("remove missing", lambda: list.remove(box, 99))
show("pop", lambda: list.pop(box))
show("pop index", lambda: list.pop(box, 0))
show("pop empty", lambda: list.pop([]))
show("clear", lambda: list.clear(box) or box)
show("copy", lambda: list.copy([1, 2]))
show("count", lambda: list.count([1, 1, 2], 1))
show("index", lambda: list.index([1, 2], 2))
show("index missing", lambda: list.index([1, 2], 3))
show("reverse", lambda: list.reverse([1, 2]) is None)
show("sort", lambda: list(sorted([1, 2]), key=lambda x: -x))
show("sort keyword", lambda: [1].sort(reverse=True))
show("method qualname", lambda: (list.append.__qualname__, box.__class__.__name__))

print("== the generic subscript ==")
show("kind", lambda: type(OWNED["__class_getitem__"]).__name__)
show("doc", lambda: OWNED["__class_getitem__"].__doc__)
show("objclass", lambda: OWNED["__class_getitem__"].__objclass__ is list)
show("bound self", lambda: list.__class_getitem__.__self__ is list)
show("bound kind", lambda: type(list.__class_getitem__).__name__)
show("no argument", lambda: list.__class_getitem__())
show("two arguments", lambda: list.__class_getitem__(int, str))
show("keyword", lambda: list.__class_getitem__(int, x=1))
show("unbound no argument", lambda: OWNED["__class_getitem__"]())
show("unbound non-type class", lambda: OWNED["__class_getitem__"]("ab", 1))
show("unbound foreign class", lambda: OWNED["__class_getitem__"](str, int))
show("one", lambda: list.__class_getitem__(int))
show("subscript", lambda: list[int])
show("two subscripted", lambda: list[int, str])
show("none subscripted", lambda: list[()])
show("a slice", lambda: list[1:2])
show("not a class", lambda: list[3])
show("nested", lambda: list[tuple[int, str]])
show("alias kind", lambda: (type(list[int]).__name__, type(list[int]).__module__))
show("origin and args", lambda: (list[int].__origin__ is list, list[int].__args__))
show("no parameters", lambda: list[int].__parameters__)
show("read twice", lambda: list[int] == list[int] and list[int] is not list[int])
show("different args", lambda: list[int] == list[str])
show("hashable", lambda: {list[int]: 1}[list[int]])
show("the hash it is made of", lambda: hash(list[int]) == hash(list) ^ hash((int,)))
show("an empty read hashes too", lambda: hash(list[()]) == hash(list) ^ hash(()))
show("subscripting an alias", lambda: list[int][0])
show("the type itself", lambda: type(list[int]))
