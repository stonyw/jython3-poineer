"""The dictionary ``dict`` files, and the two ways a mapping is read and written.

``len(dict.__dict__)`` is thirty-four: ``__new__``, the fifteen slot wrappers a mapping is measured and
merged by, ``__hash__`` filed as ``None`` as it is for ``list``, then fourteen ``method_descriptor``s, two
``classmethod_descriptor``s and the type's own sentence. ``dir`` reads forty-six, because the eight names
``object`` lends are inherited rather than filed.

The fourteen methods are where the two halves of ``PyDict_Type`` show. ``tp_as_mapping`` is copied into the
dictionary — ``mp_length`` as ``__len__``, ``mp_ass_sub`` as both ``__setitem__`` and ``__delitem__`` — but
``mp_subscript`` is not, because ``mapp_methods`` files a ``METH_O`` ``__getitem__`` beside it. The read of a
value is therefore counted in the vectorcall words (``dict.__getitem__() takes exactly one argument (0
given)``) while its write is counted bare, and the same split runs through ``__contains__``, ``__sizeof__``
and ``__reversed__``, which are methods, against ``__len__`` and ``__iter__``, which are slots.

The six comparison wrappers are filed although only two of them can answer. ``dict_richcompare`` checks
<em>both</em> operands for the dictionary they are supposed to compare, so ``{}.__eq__([])`` declines and the
``==`` falls back on identity, and it declines the four orderings even between two dictionaries — a
``dict.__lt__(d, e)`` read out of the dictionary hands back ``NotImplemented`` where the same call on a list
hands back a bool, and the sentence a person reads when they write ``d < e`` is the operator's own, naming
two types. ``__or__`` is a fourth shape: PEP 584 gave a mapping ``nb_or``, ``nb_inplace_or`` and the
reflected one, and ``__ror__`` is the same ``nb_or`` with its operands swapped, so its answer is built from
the dictionary on the <em>right</em>.

Every entry answers with the sentence CPython's table carries beside it, so one section prints them all: the
fifteen wrappers speak the text their ``slotdefs`` row holds, the fourteen methods the tail of the
``mapp_methods`` ``ml_doc``, and ``__class_getitem__`` the one line its ``PyMethodDef`` writes out whole.
What a line still does not print is a bound ``repr()`` (CPython's names an address), the size ``__sizeof__``
works out (CPython's counts the allocator's header and this one has none), the type a ``reversed()`` call
hands back (CPython names it), the ``__doc__`` of the ``__hash__`` cell (a value reads its type's string
here, where CPython's ``object.__doc__`` writes a fresh one from ``tp_doc`` each time), or a hash as a
number. The one hash written down is read as a relationship: ``ga_hash`` XORs the origin's hash with the hash
of the arguments as one tuple. What is not printed either is how wide ``dir()`` is on one of the three views:
CPython counts thirty-eight names because a view files its own, and this runtime binds those methods without
filing them.

The three windows a dictionary hands out are values, not types: ``type({}.keys()).__mro__`` is ``dict_keys``
and ``object`` and nothing in between, which is why a view reads ``__dict__`` as a missing name and ``vars()``
as a refusal, and why the only ``__name__`` a view answers is the one its own type carries.

Two of the merge rules are worth the section they are pinned in. ``dict_init`` is ``PyDict_Merge`` over the
receiver and does not empty it first, so ``d.__init__(d)`` merges a dictionary into itself and changes
nothing — where ``l.__init__(l)`` is an empty list, because ``list___init___impl`` clears the receiver
before it extends from the argument, and so does a refused ``l.__init__(5)``. And a lookup takes the key's
hash <em>before</em> it knows whether there is anything to find, which is what makes an unhashable key a
refusal on a dictionary with nothing in it; ``_PyDict_Pop`` reads the entry count first, so it never hashes
at all when there is nothing to pop.

Two of the fifteen wrappers are keyword-carrying — ``tp_init`` and ``tp_call`` are the only two in CPython's
``slotdefs`` with that flag — and a keyword-carrying wrapper runs its two gates and then stops looking: the
emptiness and receiver checks come first whatever the slot is, so ``dict.__dict__['__init__'](3)`` complains
about the ``int`` with a keyword beside it just as it does without one, while ``dict.__dict__['__init__'](d,
{})`` answers ``None`` where a counted slot would have said how many arguments arrived. The keyword refusal
a call does hear on ``list.__dict__['__init__']([1], x=2)`` is therefore in ``list()``'s own words, not the
wrapper's.

The last section is the pair of merges that only a call writes. ``{**x}`` is ``PyDict_Update`` over the
dictionary being built and takes any keys; ``f(**x)`` is ``_PyDict_MergeEx`` filling a keyword dictionary and
refuses a key that is not a string — with the requirement as its whole sentence, because that function has no
qualname to write into it.
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


OWNED = dict.__dict__
sample = {"a": 1, "b": 2}
empty = {}


class Loud:
    __hash__ = None


class Counting:
    calls = 0

    def __hash__(self):
        Counting.calls += 1
        return 7

    def __eq__(self, other):
        return isinstance(other, Counting)


def ior(target, source):
    """The receiver after ``__ior__`` handed something back."""
    before = len(target)
    merged = target.__ior__(source)
    return (merged is target, len(merged) - before, merged)


def view_grows(window):
    """A view reads the dictionary it was made from, not a copy of it."""
    keys = window.keys()
    window["c"] = 3
    return sorted(keys)


def asked_once():
    key = Counting()
    found = {"a": 1}.get(key)
    return (Counting.calls, found)


def asked_empty():
    Counting.calls = 0
    found = {}.get(Counting())
    try:
        {}.get(Loud())
    except TypeError as error:
        message = str(error)
    return (Counting.calls, found, message)


def init_refused(receiver, argument):
    """What the receiver holds after an ``__init__`` that was refused on the way in."""
    try:
        receiver.__init__(argument)
    except TypeError as error:
        return (str(error), receiver)
    return ("no refusal", receiver)


def keyword_read(**window):
    """What a ``f(**window)`` saw, as the names it was written with."""
    return sorted(str(name) for name in window)



print("== the namespace of a mapping ==")
show("kind", lambda: type(OWNED).__name__)
show("len", lambda: len(OWNED))
show("dir is wider", lambda: len(dir(dict)))
show("new is first", lambda: list(OWNED)[0])
show("doc is last", lambda: list(OWNED)[-1])
show("getitem is between", lambda: list(OWNED).index("__getitem__"))
show("hash is none", lambda: OWNED["__hash__"] is None)
show("hash kind", lambda: type(OWNED["__hash__"]).__name__)
show("hash is not callable", lambda: callable(dict.__hash__))
show("wrapped hash refuses", lambda: OWNED["__hash__"](sample))
show("the builtin refuses too", lambda: hash(sample))
show("a list files none", lambda: type(list.__dict__["__hash__"]).__name__)
show("a tuple files a wrapper", lambda: type(tuple.__dict__["__hash__"]).__name__)
show("getitem is a method", lambda: type(OWNED["__getitem__"]).__name__)
show("len is a slot", lambda: type(OWNED["__len__"]).__name__)
show("fromkeys is a classmethod", lambda: type(OWNED["fromkeys"]).__name__)
show("filed once", lambda: OWNED["get"] is OWNED["get"])
show("read agrees", lambda: dict.get is OWNED["get"])
show("value read differs", lambda: sample.get is dict.get)
show("value read twice", lambda: sample.get == sample.get)
show("no str is filed", lambda: [n for n in ("__str__", "__format__", "__int__", "__call__")
                                 if n in OWNED])
show("str is inherited", lambda: dict.__str__ is object.__str__)
show("str reads the repr", lambda: str({"a": 1}))
show("doc is the constructor", lambda: OWNED["__doc__"].splitlines()[0])
show("doc ends with the example", lambda: OWNED["__doc__"].splitlines()[-1].strip())
show("doc width", lambda: len(OWNED["__doc__"]))

print("== all thirty-four names, as the table files them ==")
for name in OWNED:
    print(line(dict, name))

print("== the three counts a subscript answers in ==")
show("getitem none", lambda: OWNED["__getitem__"](sample))
show("getitem two", lambda: OWNED["__getitem__"](sample, "a", "b"))
show("getitem keyword", lambda: sample.__getitem__(x=1))
show("getitem wrong receiver", lambda: OWNED["__getitem__"](42, "a"))
show("getitem answers", lambda: OWNED["__getitem__"](sample, "a"))
show("getitem missing", lambda: OWNED["__getitem__"](sample, "zz"))
show("len says it bare", lambda: OWNED["__len__"](sample, 1))
show("setitem one", lambda: OWNED["__setitem__"](sample, "a"))
show("setitem three", lambda: OWNED["__setitem__"](sample, "a", 1, 2))
show("setitem keyword", lambda: sample.__setitem__("a", 1, x=2))
show("setitem answers none", lambda: sample.__setitem__("c", 3) and 0)
show("setitem then read", lambda: sample)
show("delitem two", lambda: OWNED["__delitem__"](sample, "c", 1))
show("delitem answers none", lambda: sample.__delitem__("c") and 0)
show("delitem then read", lambda: sample)
show("delitem missing", lambda: sample.__delitem__("zz"))
show("contains counts like getitem", lambda: sample.__contains__())
show("contains two", lambda: sample.__contains__("a", "b"))
show("contains wrong receiver", lambda: OWNED["__contains__"](42, "a"))
show("contains answers", lambda: sample.__contains__("b"))
show("operator on a list", lambda: "a" in [1])

print("== what the four orderings answer between two dictionaries ==")
show("eq answers", lambda: OWNED["__eq__"]({"a": 1}, {"a": 1}))
show("eq length first", lambda: OWNED["__eq__"]({"a": 1}, {"a": 1, "b": 2}))
show("eq value decides", lambda: {"a": 1} == {"a": 2})
show("eq declines a list", lambda: OWNED["__eq__"]({"a": 1}, [("a", 1)]) is NotImplemented)
show("ne declines a list", lambda: OWNED["__ne__"]({"a": 1}, [("a", 1)]) is NotImplemented)
show("operator still answers", lambda: {"a": 1} == [("a", 1)])
show("identity is the fallback", lambda: {} == {})
show("lt refuses between dicts", lambda: OWNED["__lt__"]({"a": 1}, {"b": 2}))
show("le refuses between dicts", lambda: OWNED["__le__"]({"a": 1}, {"a": 1}))
show("ge refuses between dicts", lambda: OWNED["__ge__"]({"a": 1}, {"a": 1}))
show("gt declines a list", lambda: OWNED["__gt__"]({"a": 1}, [1]) is NotImplemented)
show("operator says the same", lambda: {"a": 1} < {"b": 2})
show("operator names the other", lambda: {"a": 1} < 2)
show("cmp of a list differs", lambda: list.__dict__["__lt__"]([1], [2]))

print("== the three halves of the merge operator ==")
left = {"a": 1, "b": 2}
right = {"b": 22, "c": 33}
show("or builds new", lambda: (left | right, dict(left)))
show("or kind", lambda: type(left | right).__name__)
show("or of empty", lambda: {} | {})
show("method or", lambda: left.__or__(right))
show("or a list", lambda: left | [1])
show("or a list names both", lambda: [1] | left)
show("ror declines a list", lambda: left.__ror__([1]) is NotImplemented)
show("ror swaps operands", lambda: {"b": 3}.__ror__({"b": 2, "a": 1}))
show("ror order is asked", lambda: list({"b": 3}.__ror__({"b": 2, "a": 1})))
show("ror of a mapping name", lambda: {"b": 3}.__ror__({"a": 1}))
show("ior keeps the receiver", lambda: ior(left, right))
show("ior of a list", lambda: ior(left, [1]))
show("ior of pairs", lambda: ior(left, [("d", 4)]))
show("ior of a number", lambda: ior(left, 5))
show("ior answers itself", lambda: (lambda s: s.__ior__({}) is s)({"a": 1}))


print("== what __init__ merges, and does not clear ==")
built = {"a": 1, "b": 2}
show("init a dict", lambda: (built.__init__({"b": 22, "c": 33}), built))
show("init of itself", lambda: (built.__init__(built), built))
show("init returns none", lambda: built.__init__({}))
show("init keywords", lambda: (built.__init__(z=9), sorted(built)))
show("init pairs", lambda: (built.__init__([("k", 1)]), "k" in built))
show("init text pair", lambda: (built.__init__(["ab"]), built["a"]))
show("init two positional", lambda: built.__init__({}, {}))
show("init a number", lambda: built.__init__(5))
show("init bad pair", lambda: built.__init__([1, 2]))
show("init keyword then count", lambda: OWNED["__init__"](built, {}, x=1))
show("dict init refused keeps", lambda: init_refused({"a": 1}, 5))
show("list init of itself", lambda: (lambda s: (s.__init__(s), s))([1, 2]))
show("list init refused clears", lambda: init_refused([1, 2], 5))
show("list extend of itself doubles", lambda: (lambda s: (s.extend(s), s))([1, 2]))
show("list init of a copy", lambda: (lambda s: (s.__init__(s[:]), s))([1, 2]))
show("new takes a class", lambda: dict.__new__(dict))
show("new ignores the rest", lambda: dict.__new__(dict, [("a", 1)]))
show("new ignores keywords", lambda: dict.__new__(dict, x=1))
show("new with nothing", lambda: dict.__new__())
show("new of another type", lambda: dict.__new__(list))
show("new of a non-type", lambda: dict.__new__(3))

print("== the two gates a keyword-carrying wrapper still runs ==")
show("init slot of a number", lambda: OWNED["__init__"](3))
show("init slot with nothing", lambda: OWNED["__init__"]())
show("init slot keyword of a number", lambda: OWNED["__init__"](3, x=1))
show("init slot past the receiver",
     lambda: (lambda s: (OWNED["__init__"](s, {"c": 3}), sorted(s)))({"a": 1}))
show("object init slot answers keywords", lambda: object.__dict__["__init__"](3, x=1))
show("object init slot answers extras", lambda: object.__dict__["__init__"](3, 4))
show("object init slot with nothing", lambda: object.__dict__["__init__"]())
show("list init slot of a number", lambda: list.__dict__["__init__"](3))
show("list init slot names its own", lambda: list.__dict__["__init__"]([1], x=2))
show("type call slot with nothing", lambda: type.__dict__["__call__"]())
show("type call slot of a number", lambda: type.__dict__["__call__"](3))
show("type call slot builds", lambda: type.__dict__["__call__"](dict))
show("type init slot of a number", lambda: type.__dict__["__init__"](3))
show("len slot of a number", lambda: OWNED["__len__"](3))
show("len slot keyword", lambda: OWNED["__len__"](sample, x=1))

print("== the methods a person calls, run through the descriptor ==")
box = {"x": 1, "y": 2}
show("get", lambda: dict.get(box, "x"))
show("get missing", lambda: dict.get(box, "zz"))
show("get default", lambda: box.get("zz", "gone"))
show("get none", lambda: box.get())
show("get three", lambda: box.get("x", 1, 2))
show("get keyword", lambda: box.get("x", default=1))
show("get wrong receiver", lambda: dict.get([1], "x"))
show("setdefault", lambda: (box.setdefault("s"), box))
show("setdefault again", lambda: box.setdefault("s", 9))
show("setdefault default none", lambda: box.setdefault("n"))
show("setdefault two positional", lambda: box.setdefault("n", 1, 2))
show("pop", lambda: (box.pop("n"), sorted(box)))
show("pop missing", lambda: box.pop("zz"))
show("pop default", lambda: box.pop("zz", "gone"))
show("pop returns", lambda: box.pop("s"))
show("popitem", lambda: (box.popitem(), sorted(box)))
show("popitem empty", lambda: {}.popitem())
show("popitem keyword", lambda: box.popitem(x=1))
show("keys", lambda: sorted({"a": 1, "b": 2}.keys()))
show("keys kind", lambda: type({}.keys()).__name__)
show("keys mro", lambda: [kind.__name__ for kind in type({}.keys()).__mro__])
show("keys dict", lambda: {}.keys().__dict__)
show("keys vars", lambda: vars({}.keys()))
show("keys inherits name", lambda: {}.keys().__name__)
show("values mro", lambda: [kind.__name__ for kind in type({}.__dict__["values"]({})).__mro__])
show("items mro", lambda: [kind.__name__ for kind in type({}.items()).__mro__])
show("keys live", lambda: view_grows({"a": 1}))
show("items", lambda: sorted({"a": 1}.items()))
show("items kind", lambda: type({}.items()).__name__)
show("values", lambda: sorted({"a": 1, "b": 2}.values()))
show("values kind", lambda: type({}.values()).__name__)
show("update dict", lambda: (box.update({"u": 1}), "u" in box))
show("update pairs", lambda: (box.update([("p", 1)]), "p" in box))
show("update kwargs", lambda: (box.update(k=1, j=2), sorted(box)[-2:]))
show("update both", lambda: (box.update({"q": 1}, r=2), "q" in box and "r" in box))
show("update two positional", lambda: box.update({}, {}))
show("update none", lambda: box.update())
show("clear", lambda: (box.clear(), box))
show("clear wrong receiver", lambda: dict.clear([1]))
show("copy", lambda: (lambda s: (s.copy(), s.copy() is not s, s.copy() == s))({"a": 1}))
show("copy takes no argument", lambda: {"a": 1}.copy(1))
show("copy of an empty", lambda: {}.copy() == {})

print("== a dictionary with nothing in it still asks the key to hash ==")
show("subscript", lambda: empty[["a"]])
show("getitem", lambda: empty.__getitem__(["a"]))
show("unbound getitem", lambda: OWNED["__getitem__"](empty, ["a"]))
show("get", lambda: empty.get(["a"]))
show("get default", lambda: empty.get(["a"], "gone"))
show("contains", lambda: empty.__contains__(["a"]))
show("in", lambda: ["a"] in empty)
show("setdefault", lambda: empty.setdefault(["a"]))
show("delitem", lambda: empty.__delitem__(["a"]))
show("pop", lambda: empty.pop(["a"]))
show("pop default", lambda: empty.pop(["a"], "gone"))
show("loud subscript", lambda: empty[Loud()])
show("loud get", lambda: empty.get(Loud()))
show("loud pop", lambda: empty.pop(Loud(), "gone"))
show("loud in", lambda: Loud() in empty)
show("a dictionary with entries agrees", lambda: sample[[1]])
show("and pop with entries", lambda: sample.pop([1]))
show("the refusal names the key", lambda: {(1, ["x"]): 0})
show("hashing is asked once", lambda: asked_once())
show("and once on an empty read", lambda: asked_empty())

print("== the class method that builds, and the three gates it runs ==")
show("fromkeys text", lambda: dict.fromkeys("abc"))
show("fromkeys value", lambda: dict.fromkeys("ab", 7))
show("fromkeys list", lambda: dict.fromkeys([1, 2, 1]))
show("fromkeys none", lambda: dict.fromkeys(iter([])))
show("fromkeys a number", lambda: dict.fromkeys(5))
show("fromkeys unhashable key", lambda: dict.fromkeys([["a"]]))
show("read through a value", lambda: sample.fromkeys("x"))
show("read through the type", lambda: type(OWNED["fromkeys"]).__name__)
show("objclass", lambda: OWNED["fromkeys"].__objclass__ is dict)
show("bound self", lambda: dict.fromkeys.__self__ is dict)
show("bound kind", lambda: type(dict.fromkeys).__name__)
show("unbound with nothing", lambda: OWNED["fromkeys"]())
show("unbound with one", lambda: OWNED["fromkeys"](dict))
show("unbound of a value", lambda: OWNED["fromkeys"](sample, "ab"))
show("unbound of a list", lambda: OWNED["fromkeys"]([1], "ab"))
show("unbound keyword", lambda: OWNED["fromkeys"](dict, "ab", x=1))
show("bound with nothing", lambda: dict.fromkeys())
show("bound with three", lambda: dict.fromkeys("ab", 1, 2))
show("bound keyword", lambda: dict.fromkeys("ab", value=1))

print("== the generic subscript, on a mapping of two ==")
show("kind", lambda: type(OWNED["__class_getitem__"]).__name__)
show("doc", lambda: OWNED["__class_getitem__"].__doc__)
show("objclass", lambda: OWNED["__class_getitem__"].__objclass__ is dict)
show("bound self", lambda: dict.__class_getitem__.__self__ is dict)
show("unbound with one", lambda: OWNED["__class_getitem__"](dict, int))
show("unbound with none", lambda: OWNED["__class_getitem__"]())
show("unbound non-type class", lambda: OWNED["__class_getitem__"]("ab", int))
show("unbound foreign class", lambda: OWNED["__class_getitem__"](list, int))
show("bound with two", lambda: dict.__class_getitem__(int, str))
show("bound keyword", lambda: dict.__class_getitem__(int, x=1))
show("one", lambda: dict[int])
show("two", lambda: dict[str, int])
show("three", lambda: dict[str, int, float])
show("none subscripted", lambda: dict[()])
show("a slice", lambda: dict[int:2])
show("not a class", lambda: dict[3])
show("nested", lambda: dict[str, list[int]])
show("alias kind", lambda: (type(dict[int]).__name__, type(dict[int]).__module__))
show("origin and args", lambda: (dict[int].__origin__ is dict, dict[int].__args__))
show("no parameters", lambda: dict[int].__parameters__)
show("read twice", lambda: dict[str, int] == dict[str, int] and dict[str, int] is not dict[str, int])
show("order matters", lambda: dict[str, int] == dict[int, str])
show("hashable", lambda: {dict[int, str]: 1}[dict[int, str]])
show("the hash it is made of", lambda: hash(dict[int, str]) == hash(dict) ^ hash((int, str)))
show("subscripting an alias", lambda: dict[int][0])
show("the type itself", lambda: type(dict[int]))

print("== the sentence each filed name carries ==")
for name in OWNED:
    if name != "__hash__":
        print("%-20s %r" % (name, OWNED[name].__doc__))

print("== the two merges a call writes into a dictionary ==")
show("star of strings", lambda: keyword_read(**{"a": 1}))
show("star of any keys", lambda: sorted(str(k) for k in {**{"a": 1}, **{1: 2}}))
show("star into keywords", lambda: keyword_read(**{"a": 1, 2: 3}))
show("unpack into a display", lambda: {**5})
show("unpack of a string key", lambda: {**{1: 2}})
