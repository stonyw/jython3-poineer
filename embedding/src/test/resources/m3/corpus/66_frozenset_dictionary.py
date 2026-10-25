"""The dictionary ``frozenset`` files, and the three things an immutable value answers differently.

``len(frozenset.__dict__)`` is thirty-two: ``__new__``, the eighteen slot wrappers a frozen set is compared,
iterated, halved and combined by — among them ``__hash__``, filed as a wrapper and not as ``None`` — then
eleven ``method_descriptor``s, one ``classmethod_descriptor`` and the type's own sentence. ``dir`` reads
forty-four, and the twelve names it borrows from ``object`` include ``__init__``, which a set files in its
own table and a frozenset does not.

Only the Java class differs from ``set``, and three things follow. The method table has no mutating method,
so ``fs.add`` is an ``AttributeError`` rather than a refusal, and ``fs.discard`` is missing while
``fs.difference_update`` is not. The four in-place operators are not filed at all, so ``fs |= other``
declines in place and Python falls back to ``__or__``, naming a new frozenset where ``s |= other`` mutates
and hands the same object back. And a value can be filed in a dictionary, which needs a hash independent of
the order its elements were stored in — ``frozenset_hash_impl`` is an XOR of shuffled element hashes for
exactly that reason, so ``hash(frozenset([1, 2]))`` and ``hash(frozenset([2, 1]))`` agree, and the two
dispersal steps at the end are what keep a nested ``frozenset({frozenset({1})})`` from landing on the same
number as its own element.

``__hash__`` is the second name in the table, where a set files ``None`` in the same place: ``set`` writes
``PyObject_HashNotImplemented`` into ``tp_hash``, and ``add_operators`` files that one pointer as a plain
``None`` rather than as a wrapper, which is why
``set.__dict__['__hash__'](s)`` is a ``TypeError`` about an ``NoneType`` that cannot be called while the
frozen one answers with a number. The third difference is the missing ``__init__``: ``frozenset`` inherits
``object``'s, which checks only that the receiver is a ``frozenset`` and then swallows whatever else
arrived, so ``f.__init__([2])`` answers ``None`` and changes nothing while ``s.__init__([2])`` refills the
set.

``set_init`` is the reason a set and a frozen set differ under the same call, and it is worth the section it
is pinned in: the set clears its receiver before it reads the argument, so ``s.__init__(s)`` empties the set
it was handed. ``set_contains_lock_held`` is the reason they differ under ``in``: a lookup refuses a set as
an element but then hashes the value as the frozenset of its contents and asks again, which makes
``{1} in {frozenset({1})}`` true, and ``frozenset`` takes the same second look as the mutable half does.

The sections below print the table, the eight slots, the eleven methods, the hash and those identities.
What they do not print is a bound ``repr()`` (CPython's names an address), the size ``__sizeof__`` works out
(CPython's counts the allocator's header and this one has none), the type an iterator is (CPython names it
``set_iterator``), the ``__doc__`` of a value read from ``None``, a hash as a number — only as a
relationship — or a set in the order it happens to iterate in, which is why every set here is read through
``sorted``.
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
    return "%-28s %-24s %-28s self=%-5s objclass=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__self__"),
        hasattr(value, "__objclass__"),
    )


OWNED = frozenset.__dict__
ONE = frozenset({1})
TWO = frozenset({1, 2})


def missing(names):
    """The names of the mutable half this table does not file."""
    return [name for name in names if name not in OWNED]


print("== the namespace of a frozenset ==")
show("kind", lambda: type(OWNED).__name__)
show("len", lambda: len(OWNED))
show("dir is wider", lambda: len(dir(frozenset)))
show("new is first", lambda: list(OWNED)[0])
show("doc is last", lambda: list(OWNED)[-1])
show("hash is second", lambda: list(OWNED).index("__hash__"))
show("hash is a wrapper", lambda: type(OWNED["__hash__"]).__name__)
show("a set files none there", lambda: type(set.__dict__["__hash__"]).__name__)
show("hash answers a number", lambda: type(OWNED["__hash__"](ONE)).__name__)
show("hash of a set refuses", lambda: set.__dict__["__hash__"]({1}))
show("hash callable through the type", lambda: callable(frozenset.__hash__))
show("six orderings then iter", lambda: list(OWNED).index("__iter__") - list(OWNED).index("__lt__"))
show("eight number slots", lambda: list(OWNED).index("__len__") - list(OWNED).index("__sub__"))
show("no init is filed", lambda: missing(["__init__"]))
show("init is inherited", lambda: frozenset.__init__ is object.__init__)
show("init is in dir", lambda: "__init__" in dir(frozenset))
show("contains follows len", lambda: list(OWNED).index("__contains__") - list(OWNED).index("__len__"))
show("contains is a method", lambda: type(OWNED["__contains__"]).__name__)
show("len is a slot", lambda: type(OWNED["__len__"]).__name__)
show("the mutable names are not here", lambda: missing(
    ["add", "clear", "discard", "pop", "remove", "update", "difference_update",
     "intersection_update", "symmetric_difference_update"]))
show("the in-place slots are not here", lambda: missing(["__isub__", "__iand__", "__ixor__", "__ior__"]))
show("reduce is filed", lambda: type(OWNED["__reduce__"]).__name__)
show("getitem is a classmethod", lambda: type(OWNED["__class_getitem__"]).__name__)
show("filed once", lambda: OWNED["copy"] is OWNED["copy"])
show("read agrees", lambda: frozenset.copy is OWNED["copy"])
show("value read twice", lambda: (lambda t: t.copy == t.copy)(ONE))
show("nothing of object is filed", lambda: missing(["__str__", "__bool__", "__reversed__", "__format__"]))
show("str is inherited", lambda: frozenset.__str__ is object.__str__)
show("str reads the repr", lambda: len(str(ONE)) > 0)
show("doc is the constructor", lambda: OWNED["__doc__"].splitlines()[0])
show("doc width", lambda: len(OWNED["__doc__"]))
show("the mutable doc differs", lambda: set.__doc__ != frozenset.__doc__)

print("== all thirty-two names, as the table files them ==")
for name in OWNED:
    print(line(frozenset, name))

print("== the six orderings, which are the same subset tests ==")
show("lt a set", lambda: OWNED["__lt__"](ONE, {1, 2}))
show("lt of itself", lambda: ONE < frozenset({1}))
show("le of itself", lambda: ONE <= frozenset({1}))
show("ge of a superset", lambda: TWO >= ONE)
show("gt of a superset", lambda: TWO > ONE)
show("gt of empty", lambda: frozenset() > frozenset())
show("eq a set", lambda: OWNED["__eq__"](ONE, {1}))
show("eq the other way", lambda: {1} == ONE)
show("ne a set", lambda: OWNED["__ne__"](ONE, {2}))
show("eq of a list", lambda: OWNED["__eq__"](ONE, [1]) is NotImplemented)
show("lt of a list", lambda: OWNED["__lt__"](ONE, [1]) is NotImplemented)
show("operator still answers", lambda: ONE == [1])
show("identity is the fallback", lambda: frozenset() == frozenset())
show("lt names both", lambda: ONE < [1])
show("cmp of a tuple differs", lambda: tuple.__dict__["__lt__"]((1,), (2,)))

print("== the eight number slots, and what each builds ==")
show("sub", lambda: sorted(TWO - ONE))
show("sub slot", lambda: sorted(OWNED["__sub__"](TWO, ONE)))
show("sub a set", lambda: sorted(OWNED["__sub__"](frozenset({1, 2, 3}), {1})))
show("sub of a list", lambda: OWNED["__sub__"](ONE, [1]) is NotImplemented)
show("sub operator", lambda: ONE - [1])
show("rsub swaps", lambda: sorted(OWNED["__rsub__"](ONE, {1, 2})))
show("and", lambda: sorted(frozenset({1, 2}) & frozenset({2, 3})))
show("and a set", lambda: sorted(OWNED["__and__"](frozenset({1, 2}), {2, 3})))
show("rand swaps", lambda: sorted(OWNED["__rand__"](ONE, {1, 2})))
show("xor", lambda: sorted(frozenset({1, 2}) ^ {2, 3}))
show("rxor swaps", lambda: sorted(OWNED["__rxor__"](ONE, {1, 2})))
show("or", lambda: sorted(ONE | {2}))
show("or a set", lambda: sorted(OWNED["__or__"](ONE, {2, 3})))
show("ror swaps", lambda: sorted(OWNED["__ror__"](ONE, {2})))
show("or of a list", lambda: ONE | [2])
show("the kind each builds", lambda: type(TWO - ONE).__name__)
show("or names its type", lambda: type(ONE | {2}).__name__)
show("sub keyword", lambda: ONE.__sub__(ONE, x=2))
show("sub slot of a number", lambda: OWNED["__sub__"](5, ONE))
show("len slot", lambda: OWNED["__len__"](TWO))
show("len extra", lambda: OWNED["__len__"](TWO, 1))
show("iter slot", lambda: sorted(OWNED["__iter__"](TWO)))
show("repr slot", lambda: len(OWNED["__repr__"](ONE)) > 0)

def rebound(start):
    """``f = f | x``: the in-place slot declines, so the name is bound to a new value."""
    frozen = start
    frozen = frozen | ONE
    return (frozen is start, len(frozen), sorted(frozen))


print("== what an in-place operator falls back to ==")
show("ior of a frozen", lambda: (lambda t: (t.__ior__({2}), sorted(t)))(TWO))
show("ior statement", lambda: (lambda t: (t.__ior__(ONE), sorted(t)))(TWO))
show("iand statement", lambda: (lambda t: (t.__iand__({2}), sorted(t)))(TWO))
show("isub statement", lambda: (lambda t: (t.__isub__(ONE), sorted(t)))(TWO))
show("ixor statement", lambda: (lambda t: (t.__ixor__(ONE), sorted(t)))(TWO))
show("bar equals", lambda: (lambda t: (t | ONE is not t, sorted(t), len(t)))(TWO))
show("minus equals", lambda: (lambda t: (t - ONE is not t, sorted(t)))(TWO))
show("rebound", lambda: rebound(TWO))
show("set keeps itself", lambda: (lambda t: (t.__ior__({2}) is t))(set(TWO)))

print("== the eleven methods, run through the descriptor ==")
show("copy", lambda: (lambda t: (sorted(t.copy()), t.copy() is t))(ONE))
show("copy an argument", lambda: ONE.copy(1))
show("copy keyword", lambda: ONE.copy(x=1))
show("copy wrong receiver", lambda: frozenset.copy({1}))
show("difference one", lambda: sorted(TWO.difference(ONE)))
show("difference three", lambda: sorted(frozenset({1, 2, 3}).difference({1}, {2}, {3})))
show("difference none", lambda: TWO.difference())
show("difference of a number", lambda: TWO.difference(5))
show("difference keyword", lambda: TWO.difference(ONE, x=2))
show("intersection", lambda: sorted(frozenset({1, 2, 3}).intersection({2}, {2, 3})))
show("intersection of a number", lambda: TWO.intersection(5))
show("intersection unhashable", lambda: sorted(ONE.intersection([1, [2]])))
show("isdisjoint", lambda: ONE.isdisjoint([7, 8]))
show("isdisjoint two", lambda: ONE.isdisjoint([1], [2]))
show("isdisjoint of a number", lambda: ONE.isdisjoint(5))
show("issubset a set", lambda: ONE.issubset({1, 2}))
show("issubset a list", lambda: ONE.issubset([1, 2]))
show("issubset a number", lambda: ONE.issubset(5))
show("issuperset a set", lambda: TWO.issuperset({1}))
show("issuperset of a list", lambda: TWO.issuperset([1]))
show("symmetric_difference", lambda: sorted(TWO.symmetric_difference({2, 3})))
show("symmetric_difference two", lambda: TWO.symmetric_difference(ONE, ONE))
show("union", lambda: sorted(ONE.union([3], [5])))
show("union of a number", lambda: ONE.union(5))
show("union keyword", lambda: ONE.union(x=1))
show("union wrong receiver", lambda: frozenset.union([1], [2]))
show("contains", lambda: ONE.__contains__(1))
show("contains none", lambda: ONE.__contains__())
show("contains two", lambda: ONE.__contains__(1, 2))
show("contains keyword", lambda: ONE.__contains__(1, x=2))
show("contains wrong receiver", lambda: OWNED["__contains__"](42, 1))
show("reduce", lambda: (lambda t: (t.__reduce__()[0] is frozenset, type(t.__reduce__()[1]).__name__,
                                   len(t.__reduce__()[1]), t.__reduce__()[2]))(TWO))
show("reduce arity", lambda: TWO.__reduce__(1))
show("sizeof is filed", lambda: type(OWNED["__sizeof__"]).__name__)

print("== a value that hashes, and the order it cannot depend on ==")
show("keyable", lambda: {ONE: "x"}[frozenset({1})])
show("nested keyable", lambda: {{ONE}: 1}[frozenset({1})])
show("equal sets hash apart", lambda: ONE == {1})
show("of empty", lambda: hash(frozenset()) == hash(frozenset()))
show("order does not matter", lambda: hash(frozenset([1, 2])) == hash(frozenset([2, 1])))
show("three ways agree", lambda: len({frozenset([1, 2]), frozenset([2, 1]), frozenset({1, 2})}))
show("a new value hashes the same", lambda: hash(ONE ^ frozenset({1, 2})) == hash(frozenset({2})))
show("a set is refused as an element", lambda: frozenset([{1}]))
show("a list is refused as an element", lambda: frozenset([[1]]))
show("nested is not its element", lambda: hash(frozenset({ONE})) != hash(ONE))
show("nested agrees both ways", lambda: hash(frozenset({ONE, TWO})) == hash(frozenset({TWO, ONE})))
show("equal values, equal hash", lambda: hash(frozenset({1, 2})) == hash(frozenset({2, 1, 1})))
show("the -1 is not used", lambda: hash(frozenset()) != -1)
show("as a dict key twice", lambda: ({ONE: 1, frozenset({1}): 2}))
show("counted once as a key", lambda: len({frozenset({1}), frozenset({1}), ONE}))
show("hash keyword", lambda: ONE.__hash__(x=1))
show("hash slot of a number", lambda: OWNED["__hash__"](3))
show("hash of a set receiver", lambda: OWNED["__hash__"]({1}))

print("== the identity a frozen value keeps ==")
show("of itself", lambda: frozenset(ONE) is ONE)
show("of a set", lambda: frozenset({1}) == ONE)
show("copy is itself", lambda: ONE.copy() is ONE)
show("empty is not shared", lambda: frozenset() is frozenset())
show("two equal values", lambda: frozenset({1}) == frozenset({1}))
show("a set copy is not itself", lambda: (lambda t: t.copy() is not t)(set(ONE)))
show("of nothing", lambda: len(frozenset()))
show("of a text", lambda: sorted(frozenset("ab")))
show("of a dict", lambda: sorted(frozenset({"a": 1, "b": 2})))
show("of a number", lambda: frozenset(5))
show("of two", lambda: frozenset([1], [2]))
show("with keywords", lambda: frozenset(x=1))
show("init swallows", lambda: (lambda t: (t.__init__([2]), len(t)))(ONE))
show("init swallows a refusal", lambda: (lambda t: (t.__init__(5), len(t)))(ONE))
show("init swallows keywords", lambda: (lambda t: (t.__init__(x=1), len(t)))(ONE))
show("init slot of a number", lambda: object.__dict__["__init__"](3))

print("== the class gates, which are the same two ==")
show("new", lambda: type(frozenset.__new__(frozenset)).__name__)
show("new of nothing", lambda: frozenset.__new__())
show("new of a number", lambda: frozenset.__new__(3))
show("new of a set", lambda: frozenset.__new__(set))
show("new of two", lambda: frozenset.__new__(frozenset, [1], [2]))
show("new keyword", lambda: frozenset.__new__(frozenset, x=1))
show("set new of a frozen", lambda: set.__new__(frozenset))

print("== the sentence each filed name carries ==")
for name in OWNED:
    print("%-28s %r" % (name, OWNED[name].__doc__))

print("== the class method that makes a generic, and the three gates it runs ==")
show("kind", lambda: type(OWNED["__class_getitem__"]).__name__)
show("doc", lambda: OWNED["__class_getitem__"].__doc__)
show("a set says it too", lambda: set.__class_getitem__.__doc__ != frozenset.__class_getitem__.__doc__)
show("objclass", lambda: OWNED["__class_getitem__"].__objclass__ is frozenset)
show("bound self", lambda: frozenset.__class_getitem__.__self__ is frozenset)
show("bound kind", lambda: type(frozenset.__class_getitem__).__name__)
show("unbound with one", lambda: len(OWNED["__class_getitem__"](frozenset, int)))
show("unbound with none", lambda: OWNED["__class_getitem__"]())
show("unbound non-type class", lambda: OWNED["__class_getitem__"]("ab", int))
show("unbound of a value", lambda: OWNED["__class_getitem__"](ONE, int))
show("bound with two", lambda: frozenset.__class_getitem__(int, str))
show("bound keyword", lambda: frozenset.__class_getitem__(int, x=1))
show("one", lambda: type(frozenset[int]).__name__)
show("origin and args", lambda: (frozenset[int].__origin__ is frozenset, len(frozenset[int].__args__)))
show("not a class", lambda: type(frozenset[3]).__name__)
show("read twice", lambda: frozenset[int] == frozenset[int] and frozenset[int] is not frozenset[int])
show("hashable", lambda: {frozenset[int]: 1}[frozenset[int]])
show("the frozen half is not the set half", lambda: set[int] == frozenset[int])
