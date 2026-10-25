"""The dictionary ``set`` files, and the twelve number slots a set is asked through.

``len(set.__dict__)`` is forty-six: ``__new__``, the twenty-two slot wrappers a set is compared, iterated,
filled and combined by, ``__hash__`` filed as ``None`` exactly as it is for ``list``, then twenty
``method_descriptor``s, one ``classmethod_descriptor`` and the type's own sentence. ``dir`` reads
fifty-seven, because the names ``object`` lends are inherited rather than filed.

The twelve number slots are the shape of this type. ``set_sub`` and its seven siblings are ``nb_*``
functions, not ``sq_*`` ones, so a set is combined with ``-``, ``&``, ``^`` and ``|`` and every one of the
eight has a reflected partner filed beside it — and ``__ror__`` is the same C function as ``__or__`` with
its two operands swapped, which is why ``[1].__or__`` is never reached and ``2 | {1}`` names the ``int`` in
its refusal. The four in-place ones are filed after them and, unlike a list's ``__iadd__``, are not
``sq_inplace`` slots; their sentences therefore say ``Return self-=value.`` where a list's say
``Implement self+=value.``, and what they return is the receiver itself, mutated.

``__contains__`` is the one name filed in a slot's place by ``METH_COEXIST``: its index in the table is the
``sq_contains`` one, right after ``__len__``, but the object there is a ``method_descriptor``, so a read of
it is counted in the vectorcall words — ``set.__contains__() takes exactly one argument (0 given)`` — where
``__len__`` and ``__iter__`` beside it are counted bare. ``__init__`` is filed although a set's own
constructor runs it: ``set()`` takes one argument and refuses keywords in its own voice, while the wrapper
behind ``set.__dict__['__init__']`` runs the emptiness and receiver gates first and stops, so
``set.__dict__['__init__'](3)`` complains about the ``int`` with a keyword beside it just as it does
without one.

What ``set_init`` does before it fills is the reason a set and a dictionary answer the same call
differently. It clears the receiver only when the receiver is not already empty, and it clears before it
reads, so ``s.__init__(s)`` empties the set it was handed — where ``d.__init__(d)`` merges a dictionary
into itself and changes nothing — and a refused ``s.__init__(5)`` leaves an empty set behind rather than
the set that was there.

Two of the twenty methods only answer through a hash, and that is where the two oddities live. A lookup
takes the element's hash before it knows whether there is anything to find, so an unhashable value tested
against an empty set is refused in the set's own words instead of being reported missing — and the hash is
asked once, not twice, whether or not there was anything to find it in. And a set is refused as an element
but not as a key: ``set_contains_key`` has no second look, while ``in``, ``discard`` and ``remove`` wrap the
refusal, hash the value as the frozenset of its contents and try again, which is what makes
``{1} in {frozenset({1})}`` true and ``{frozenset({1})}.isdisjoint([{1}])`` an error.

The sections below print the table, the twelve slots, the fills, the gates and those two lookups. What they
do not print is a bound ``repr()`` (CPython's names an address), the size ``__sizeof__`` works out
(CPython's counts the allocator's header and this one has none), the type an iterator is (CPython names it
``set_iterator``), an instance's own ``repr()`` (CPython's names its address), the ``__doc__`` of the
``__hash__`` cell (it is ``None`` itself, and this runtime reads ``None.__doc__`` as nothing), or a set in
the order it happens to iterate in — which is why every set here is read through ``sorted``.
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


OWNED = set.__dict__
count = [0]


class Counting:
    """One value, asked how many times a lookup wants its hash."""

    def __hash__(self):
        count[0] += 1
        return 7

    def __eq__(self, other):
        return isinstance(other, Counting)


marker = Counting()


def asked(thunk):
    """How often the hash was wanted, and what came back."""
    count[0] = 0
    return (count[0], thunk())


def after(target, thunk):
    """What the receiver holds once a method has run on it."""
    thunk()
    return sorted(target)


def mutated(target, thunk):
    """The receiver's identity, and what it holds."""
    answer = thunk()
    return (answer is target, sorted(target))


print("== the namespace of a set ==")
show("kind", lambda: type(OWNED).__name__)
show("len", lambda: len(OWNED))
show("dir is wider", lambda: len(dir(set)))
show("new is first", lambda: list(OWNED)[0])
show("doc is last", lambda: list(OWNED)[-1])
show("hash is none", lambda: OWNED["__hash__"] is None)
show("hash kind", lambda: type(OWNED["__hash__"]).__name__)
show("hash is not callable", lambda: callable(set.__hash__))
show("wrapped hash refuses", lambda: OWNED["__hash__"]({1}))
show("the builtin refuses too", lambda: hash({1}))
show("a list files none", lambda: type(list.__dict__["__hash__"]).__name__)
show("a tuple files a wrapper", lambda: type(tuple.__dict__["__hash__"]).__name__)
show("a frozenset files a wrapper", lambda: type(frozenset.__dict__["__hash__"]).__name__)
show("repr is second", lambda: list(OWNED).index("__repr__"))
show("hash is before the orderings", lambda: list(OWNED).index("__lt__") - list(OWNED).index("__hash__"))
show("init follows iter", lambda: list(OWNED).index("__init__") - list(OWNED).index("__iter__"))
show("twelve number slots", lambda: list(OWNED).index("__len__") - list(OWNED).index("__sub__"))
show("contains follows len", lambda: list(OWNED).index("__contains__") - list(OWNED).index("__len__"))
show("contains is a method", lambda: type(OWNED["__contains__"]).__name__)
show("len is a slot", lambda: type(OWNED["__len__"]).__name__)
show("iter is a slot", lambda: type(OWNED["__iter__"]).__name__)
show("add is the first method", lambda: list(OWNED).index("add") - list(OWNED).index("__contains__"))
show("reduce is filed", lambda: type(OWNED["__reduce__"]).__name__)
show("getitem is a classmethod", lambda: type(OWNED["__class_getitem__"]).__name__)
show("filed once", lambda: OWNED["add"] is OWNED["add"])
show("read agrees", lambda: set.add is OWNED["add"])
show("value read differs", lambda: {1}.add is set.add)
show("value read twice", lambda: (lambda t: t.add == t.add)({1}))
show("nothing of object is filed", lambda: [n for n in ("__str__", "__bool__", "__reversed__", "__format__")
                                             if n in OWNED])
show("str is inherited", lambda: set.__str__ is object.__str__)
show("str reads the repr", lambda: len(str({1})) > 0)
show("doc is the constructor", lambda: OWNED["__doc__"].splitlines()[0])
show("doc width", lambda: len(OWNED["__doc__"]))

print("== all forty-six names, as the table files them ==")
for name in OWNED:
    print(line(set, name))

print("== the six orderings, which are subset tests ==")
show("lt proper subset", lambda: OWNED["__lt__"]({1}, {1, 2}))
show("lt of itself", lambda: {1} < {1})
show("le of itself", lambda: {1} <= {1})
show("le subset", lambda: {1, 2} <= {2, 1})
show("ge of a superset", lambda: {1, 2} >= {1})
show("gt of a superset", lambda: {1, 2} > {1})
show("gt of empty", lambda: set() > set())
show("eq a frozenset", lambda: OWNED["__eq__"]({1}, frozenset({1})))
show("ne a frozenset", lambda: OWNED["__ne__"]({1}, frozenset({1})))
show("eq the other way", lambda: frozenset({1}) == {1})
show("eq of a list", lambda: OWNED["__eq__"]({1}, [1]) is NotImplemented)
show("ne of a list", lambda: OWNED["__ne__"]({1}, [1]) is NotImplemented)
show("lt of a list", lambda: OWNED["__lt__"]({1}, [1]) is NotImplemented)
show("operator still answers", lambda: {1} == [1])
show("identity is the fallback", lambda: set() == set())
show("lt names both", lambda: {1} < [1])
show("eq of an empty frozen", lambda: set() == frozenset())
show("subset of a number", lambda: {1} <= 1)
show("cmp of a list differs", lambda: list.__dict__["__lt__"]([1], [2]))

print("== the twelve number slots, and what each builds ==")
show("sub", lambda: sorted({1, 2, 3} - {1}))
show("sub slot", lambda: sorted(OWNED["__sub__"]({1, 2, 3}, {1})))
show("sub a frozenset", lambda: sorted(OWNED["__sub__"]({1, 2, 3}, frozenset({1}))))
show("sub of a list", lambda: OWNED["__sub__"]({1}, [1]) is NotImplemented)
show("sub operator", lambda: {1} - [1])
show("rsub swaps", lambda: sorted(OWNED["__rsub__"]({1}, {1, 2})))
show("and", lambda: sorted({1, 2} & {2, 3}))
show("and slot", lambda: sorted(OWNED["__and__"]({1, 2}, {2, 3})))
show("rand swaps", lambda: sorted(OWNED["__rand__"]({1, 2}, {2, 3})))
show("xor", lambda: sorted({1, 2} ^ {2, 3}))
show("rxor swaps", lambda: sorted(OWNED["__rxor__"]({1, 2}, {2, 3})))
show("or", lambda: sorted({1} | {2}))
show("or a frozenset", lambda: sorted(OWNED["__or__"]({1}, frozenset({2}))))
show("ror swaps", lambda: sorted(OWNED["__ror__"]({1}, {2})))
show("or of a list", lambda: {1} | [2])
show("ror of a list", lambda: OWNED["__ror__"]({1}, [2]) is NotImplemented)
show("isub is the receiver", lambda: mutated({1, 2}, lambda t: t.__isub__({1})))
show("isub of a list", lambda: mutated({1, 2}, lambda t: t.__isub__([1])))
show("isub of a number", lambda: {1, 2}.__isub__(5))
show("ior is the receiver", lambda: mutated({1}, lambda t: t.__ior__([3])))
show("ior of a frozenset", lambda: mutated({1}, lambda t: t.__ior__(frozenset({2}))))
show("ior of a number", lambda: {1}.__ior__(5))
show("iand of a list", lambda: mutated({1, 2}, lambda t: t.__iand__([1, 9])))
show("ixor of a list", lambda: mutated({1, 2}, lambda t: t.__ixor__([1])))
show("inplace names of a frozenset", lambda: [n for n in ("__isub__", "__iand__", "__ixor__", "__ior__")
                                              if n in frozenset.__dict__])
show("isub of a frozenset", lambda: set.__dict__["__isub__"](frozenset({1}), {1}))
show("sub keyword", lambda: {1, 2}.__sub__({1}, x=2))
show("sub slot keyword", lambda: OWNED["__sub__"]({1, 2}, {1}, x=2))
show("sub slot of a number", lambda: OWNED["__sub__"](5, 1))
show("len slot", lambda: OWNED["__len__"]({1, 2}))
show("len extra", lambda: OWNED["__len__"]({1, 2}, 1))
show("len keyword", lambda: OWNED["__len__"]({1}, x=1))
show("len slot of a number", lambda: OWNED["__len__"](3))
show("iter slot", lambda: sorted(OWNED["__iter__"]({2, 1})))
show("iter extra", lambda: OWNED["__iter__"]({1}, 1))

print("== what set_init clears before it fills ==")
show("init a list", lambda: after({1, 2}, lambda t: t.__init__([3, 4])))
show("init of itself", lambda: after({1, 2}, lambda t: t.__init__(t)))
show("init of a copy", lambda: after({1, 2}, lambda t: t.__init__(list(t))))
show("init refused clears", lambda: after({1, 2}, lambda t: t.__init__(5)))
show("init of empty refused", lambda: after({1}, lambda t: t.__init__(5)))
show("init with nothing", lambda: after({1, 2}, lambda t: t.__init__()))
show("init returns", lambda: {1}.__init__([1]))
show("init a text", lambda: after({1}, lambda t: t.__init__("ab")))
show("init two", lambda: {1}.__init__([1], [2]))
show("init keyword", lambda: {1}.__init__(x=1))
show("of a set", lambda: sorted(set({1, 2})))
show("of a list", lambda: sorted(set([1, 2, 2])))
show("of a text", lambda: sorted(set("ab")))
show("of nothing", lambda: len(set()))
show("of a number", lambda: set(5))
show("of two", lambda: set([1], [2]))
show("with keywords", lambda: set(x=1))
show("frozenset files no init", lambda: "__init__" in frozenset.__dict__)
show("frozen init inherited", lambda: frozenset.__init__ is object.__init__)
show("frozen init swallows", lambda: frozenset({1}).__init__([2]))
show("frozen init keeps its own", lambda: (lambda t: (t.__init__(5), len(t)))(frozenset({1})))
show("list init of itself", lambda: (lambda s: (s.__init__(s), s))([1, 2]))
show("dict init of itself", lambda: (lambda d: (d.__init__(d), len(d)))({"a": 1}))

print("== the two gates a keyword-carrying wrapper still runs ==")
show("init slot of a number", lambda: OWNED["__init__"](3))
show("init slot keyword of a number", lambda: OWNED["__init__"](3, x=1))
show("init slot with nothing", lambda: OWNED["__init__"]())
show("init slot of a frozenset", lambda: OWNED["__init__"](frozenset({1})))
show("init slot past the receiver", lambda: OWNED["__init__"]({1}, [1], [2]))
show("init slot answers none", lambda: OWNED["__init__"]({1}, [2]))
show("object init answers extras", lambda: object.__dict__["__init__"](3, 4))
show("repr slot of a number", lambda: OWNED["__repr__"](3))
show("iter slot of a number", lambda: OWNED["__iter__"](3))

print("== the twenty methods, run through the descriptor ==")
box = {1, 2}
show("add", lambda: after(box, lambda: box.add(3)))
show("add none", lambda: set.add())
show("add two", lambda: box.add(1, 2))
show("add keyword", lambda: box.add(1, x=2))
show("add wrong receiver", lambda: set.add([1], 2))
show("add unhashable", lambda: box.add([1]))
show("add again", lambda: after(box, lambda: box.add(7)))
show("discard", lambda: after(box, lambda: box.discard(3)))
show("discard missing", lambda: after(box, lambda: box.discard(99)))
show("discard two", lambda: box.discard(1, 2))
show("discard wrong receiver", lambda: set.discard([1], 2))
show("remove", lambda: after(box, lambda: box.remove(7)))
show("remove missing", lambda: box.remove(99))
show("remove none", lambda: box.remove())
show("remove keyword", lambda: box.remove(1, x=2))
show("pop", lambda: (lambda t: (sorted(t)[0], len(t) - 1))(set(box)))
show("pop of empty", lambda: set().pop())
show("pop argument", lambda: box.pop(1))
show("clear", lambda: after(box, lambda: box.clear()))
show("clear two", lambda: box.clear(1, 2))
show("clear wrong receiver", lambda: set.clear([1]))
show("copy", lambda: (lambda t: (sorted(t.copy()), t.copy() is not t, t.copy() == t))(set(box)))
show("copy an argument", lambda: box.copy(1))
show("difference one", lambda: sorted(box.difference({1})))
show("difference three", lambda: sorted({1, 2, 3}.difference({1}, {2}, {3})))
show("difference of a number", lambda: box.difference(5))
show("difference keyword", lambda: box.difference({1}, x=2))
show("difference_update", lambda: after(box, lambda: box.difference_update({1}, {2})))
show("difference_update of a number", lambda: box.difference_update(5))
show("intersection", lambda: sorted({1, 2, 3}.intersection({2}, {2, 3})))
show("intersection of a number", lambda: box.intersection(5))
show("intersection unhashable", lambda: {1}.intersection([1, [2]]))
show("intersection_update", lambda: after(box, lambda: box.intersection_update([2, 3])))
show("symmetric_difference", lambda: sorted(box.symmetric_difference({2, 3})))
show("symmetric_difference two", lambda: box.symmetric_difference({1}, {2}))
show("symmetric_difference_update", lambda: after(box, lambda: box.symmetric_difference_update([3])))
show("union", lambda: sorted(box.union([3], [5])))
show("union of a number", lambda: box.union(5))
show("union keyword", lambda: box.union(x=1))
show("update", lambda: after(box, lambda: box.update([3], {4})))
show("update of a number", lambda: box.update(5))
show("update keyword", lambda: box.update(x=1))
show("update wrong receiver", lambda: set.update([1], [2]))
show("issubset a set", lambda: {1}.issubset({1, 2}))
show("issubset a list", lambda: {1}.issubset([1, 2]))
show("issubset a number", lambda: {1}.issubset(5))
show("issuperset a frozenset", lambda: box.issuperset(frozenset({1})))
show("issuperset of a list", lambda: box.issuperset([1]))
show("isdisjoint", lambda: box.isdisjoint([7, 8]))
show("isdisjoint of a number", lambda: box.isdisjoint(5))
show("contains", lambda: box.__contains__(1))
show("contains none", lambda: box.__contains__())
show("contains two", lambda: box.__contains__(1, 2))
show("contains keyword", lambda: box.__contains__(1, x=2))
show("contains wrong receiver", lambda: OWNED["__contains__"](42, 1))
show("reduce", lambda: (lambda t: (t.__reduce__()[0] is set, type(t.__reduce__()[1]).__name__,
                                   len(t.__reduce__()[1]), t.__reduce__()[2]))(set(box)))
show("reduce arity", lambda: box.__reduce__(1))

print("== the sentence each filed name carries ==")
for name in OWNED:
    if name != "__hash__":
        print("%-28s %r" % (name, OWNED[name].__doc__))

print("== a set will not hash, and only some looks take a second one ==")
show("unhashable add", lambda: {1}.add([1]))
show("unhashable update", lambda: {1}.update([[1]]))
show("unhashable of", lambda: set([[1]]))
show("unhashable literal", lambda: {{1}})
show("unhashable in", lambda: [1] in {1})
show("unhashable in empty", lambda: [1] in set())
show("unhashable contains", lambda: {1}.__contains__([1]))
show("unhashable contains empty", lambda: set().__contains__([1]))
show("unhashable in empty frozen", lambda: [1] in frozenset())
show("unhashable discard", lambda: set().discard([1]))
show("unhashable remove", lambda: set().remove([1]))
show("unhashable isdisjoint", lambda: set().isdisjoint([[1]]))
show("unhashable dict", lambda: {} in {1})
show("second look finds", lambda: {1} in {frozenset({1})})
show("second look in a frozen", lambda: {1} in frozenset({frozenset({1})}))
show("second look misses", lambda: {2} in {frozenset({1})})
show("second look of an empty", lambda: {1} in set())
show("discard takes the second look", lambda: after({frozenset({1})}, lambda t: t.discard({1})))
show("remove takes it too", lambda: {frozenset({1})}.remove({2}))
show("isdisjoint declines it", lambda: {frozenset({1})}.isdisjoint([{1}]))
show("issuperset declines it", lambda: {frozenset({1})}.issuperset([{1}]))
show("issubset declines it", lambda: {frozenset({1})}.issubset([{1}]))
show("union declines it", lambda: {frozenset({1})}.union([{1}]))
show("add declines it", lambda: {1}.add({1}))
show("the hash is asked once, with entries", lambda: asked(lambda: len({1, 2}.intersection([marker]))))
show("and once on an empty look", lambda: asked(lambda: set().__contains__(marker)))
show("and once for a discard", lambda: asked(lambda: {1}.discard(marker)))
show("and once for a disjoint", lambda: asked(lambda: set().isdisjoint([marker])))
show("and once to file it", lambda: asked(lambda: set().add(marker)))

print("== the class method that makes a generic, and the three gates it runs ==")
show("kind", lambda: type(OWNED["__class_getitem__"]).__name__)
show("doc", lambda: OWNED["__class_getitem__"].__doc__)
show("objclass", lambda: OWNED["__class_getitem__"].__objclass__ is set)
show("bound self", lambda: set.__class_getitem__.__self__ is set)
show("bound kind", lambda: type(set.__class_getitem__).__name__)
show("unbound with one", lambda: len(OWNED["__class_getitem__"](set, int)))
show("unbound with none", lambda: OWNED["__class_getitem__"]())
show("unbound non-type class", lambda: OWNED["__class_getitem__"]("ab", int))
show("unbound of a value", lambda: OWNED["__class_getitem__"]({1}, int))
show("bound with two", lambda: set.__class_getitem__(int, str))
show("bound keyword", lambda: set.__class_getitem__(int, x=1))
show("one", lambda: type(set[int]).__name__)
show("origin and args", lambda: (set[int].__origin__ is set, len(set[int].__args__)))
show("not a class", lambda: type(set[3]).__name__)
show("read twice", lambda: set[int] == set[int] and set[int] is not set[int])
show("hashable", lambda: {set[int]: 1}[set[int]])
show("getitem is in dir", lambda: "__class_getitem__" in dir(set))
