"""The four cursors whose builtin is the type the call makes.

`builtins.enumerate`, `builtins.zip`, `builtins.map` and `builtins.filter` are types, not functions: each of
them is a `PyTypeObject` — `PyEnum_Type` in `Objects/enumobject.c`, and `PyZip_Type`, `PyMap_Type` and
`PyFilter_Type` in `Python/bltinmodule.c`, where the other three of the four are written — and
`_PyBuiltin_Init` (`bltinmodule.c:3434`) files the name with `SETBUILTIN` and the type itself.
`enumerate("ab")` and `type(enumerate("ab")) is enumerate` therefore read the same object twice, and
`enumerate.__new__(enumerate, "ab")` is the constructor reached the long way. `iter` is the fifth name that
hands out a cursor and the only one of the five that is still a `builtin_function_or_method`.

The books are no longer than the fifteen cursors that walk a source — `enumerate`, `zip` and `map` file six
names and `filter` five, which is what a sequence cursor and a hash cursor each file — but they are not the
same six either: a walking cursor files `__length_hint__`, and these four file `__new__`, the name a type in
`builtins` has and the value of `iter` never does. Beside the two slots, the pickle, the constructor and the
sentence, which all four share, `enumerate` files `__class_getitem__` because it is the one generic of the
family, `zip` and `map` file `__setstate__` because `strict` is a state that survives being found again, and
`filter` files neither. `dir()` is twenty-seven, twenty-seven, twenty-seven, twenty-six against `object`'s
twenty-four, and the names gained over `object` are the two slots plus one more for the first three and no
more for `filter`, since `object` files both `__new__` and `__reduce__` already. The alias a subscript builds
is callable and calls its origin, so `enumerate[int]("ab")` is the cursor `enumerate("ab")` hands back with
nothing of the alias kept.

What each constructor asks, and in which order, is where the four differ most:

  - `enumerate` is the one with two hand-written argument paths. `enumerate_vectorcall` (`:104`) counts by
    hand and knows only `iterable` and `start`, while `enumerate.__new__` runs the clinic table, so the two
    spellings of the same bad call disagree: `enumerate()` says `missing required argument 'iterable'` and
    `enumerate.__new__(enumerate)` adds `(pos 1)`. The count and the keywords come first, then
    `PyNumber_Index` on `start`, then `PyObject_GetIter` — which is why `enumerate(1, start='x')` complains
    about the string.
  - `zip` and `map` read `strict` out of the keyword arguments with the `p` format, which asks the truth of
    the value and so hears a `__bool__` that complains before any iterable is asked for an iterator — and
    `strict` is keyword-only, so `zip([1], True)` takes the `True` as a third source to walk.
  - `filter` is `PyArg_UnpackTuple` over exactly two arguments with the keywords refused on the way in, in
    the words `filter expected 2 arguments, got 1`. Neither `map` nor `filter` asks whether what it files is
    callable — `filter(1, [1])` and `map(1, [1])` are made in silence, and the number is called only by a
    step of the walk that reaches for it.

`__setstate__` on `zip` and `map` is not a no-op: it is the `strict` flag, read by `PyObject_IsTrue` and
written back, and `__reduce__` answers three items rather than two when the flag is on, so the state a found
cursor needs is the one thing the arguments cannot carry. The four payloads name their own type and hand back
what the cursor holds — `enumerate` the iterator and the count, `zip` the tuple of iterators, `map` the
function and the tuple, `filter` the function and the one iterator — and unlike `reversed`, which drops its
source when it runs out, none of the four ever lets go: the pickle of a spent cursor is the pickle of the
same cursor born.

Nothing here prints a value whole. All four cursors, and every iterator and function a pickle hands back,
repr with an address that the two interpreters cannot agree on — so a row reads the kind instead: the name of
the type a call hands out, and a payload with each object in it replaced by the name of its type. The two rows
that do print a repr are the aliases of the last section, which name no address.
"""


def named_kind(value):
    """A value as its own kind names it, with the objects it holds walked in the same way.

    Nothing in this file prints a value whole: all four of these reprs carry an address, and a pickle
    is a tuple of values that each carry one. What a row reads instead is the type — `list_iterator`
    for a source, `enumerate` for a cursor — so the shape of a payload is on the page and its place in
    memory is not.
    """
    if isinstance(value, tuple):
        return tuple(named_kind(held) for held in value)
    if isinstance(value, list):
        return [named_kind(held) for held in value]
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    return type(value).__name__


def said(action):
    try:
        return "ok " + repr(named_kind(action()))
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


def made(action):
    """The name of the type a call hands out, which is all of a cursor a row can print and stay readable."""
    try:
        return "ok " + type(action()).__name__
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


def shown(action):
    """The repr a value answers, for the rows whose values carry no address at all."""
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


def reduced(it):
    """The pickle, with the type it names answered by whether that type is the builtin itself."""
    payload = it.__reduce__()
    return (payload[0] is type(it),) + named_kind(tuple(payload[1:]))


def rebuilt(it):
    """`type(*args)` then `__setstate__(state)` — the two halves of the pickle put back together."""
    payload = it.__reduce__()
    found = payload[0](*payload[1])
    if len(payload) > 2:
        found.__setstate__(payload[2])
    return found


def twice(n):
    return n * 2


def join(a, b):
    return (a, b)


class Refusing:
    def __bool__(self):
        raise ValueError("refusing")


class Bad:
    def __next__(self):
        raise KeyError("not the end")

    def __iter__(self):
        return self


KINDS = [
    ("enumerate", enumerate, lambda: enumerate([1, 2], 7)),
    ("zip", zip, lambda: zip([1, 2], [3])),
    ("map", map, lambda: map(twice, [1, 2], [3])),
    ("filter", filter, lambda: filter(twice, [1, 2])),
]

print("== the name in builtins is the type the call makes")
for name, kind, make in KINDS:
    value = make()
    print("  %-10s the name is a type=%-6s the value is of it=%-6s iter(value) is value=%s" % (
        name, type(kind) is type, isinstance(value, kind), iter(value) is value))

print()
print("== the book, and how far it stands above object")
for name, kind, make in KINDS:
    print("  %-10s vars=%s" % (name, sorted(vars(kind))))
    print("      dir=%-4s gained=%s" % (
        len(dir(kind)), sorted(set(dir(kind)) - set(dir(object)))))
    print("      signature=%r" % (kind.__text_signature__,))
    print("      doc first line=%r" % (kind.__doc__.splitlines()[0],))
    for row in sorted(vars(kind)):
        print("        %-18s %-28s sig=%-24r doc=%r" % (
            row, type(vars(kind)[row]).__name__,
            getattr(vars(kind)[row], "__text_signature__", None),
            getattr(vars(kind)[row], "__doc__", None)))

print()
print("== one call, two spellings")
SPELLINGS = [
    ("no arguments at all", lambda: enumerate(), lambda: enumerate.__new__(enumerate)),
    ("too many", lambda: enumerate([1], 2, 3), lambda: enumerate.__new__(enumerate, [1], 2, 3)),
    (
        "a start and no iterable",
        lambda: enumerate(start=1),
        lambda: enumerate.__new__(enumerate, start=1),
    ),
    (
        "the iterable by name",
        lambda: enumerate(iterable=[1]),
        lambda: enumerate.__new__(enumerate, iterable=[1]),
    ),
    (
        "an unknown keyword",
        lambda: enumerate(x=1),
        lambda: enumerate.__new__(enumerate, x=1),
    ),
    ("too few for map", lambda: map(twice), lambda: map.__new__(map, twice)),
    (
        "a named positional-only argument",
        lambda: map(function=twice, iterable=[1]),
        lambda: map.__new__(map, function=twice, iterable=[1]),
    ),
    (
        "an unknown keyword on map",
        lambda: map(twice, [1], x=1),
        lambda: map.__new__(map, twice, [1], x=1),
    ),
    (
        "an unknown keyword on zip",
        lambda: zip(x=1),
        lambda: zip.__new__(zip, x=1),
    ),
    ("a strict with no source", lambda: zip(strict=True), lambda: zip.__new__(zip, strict=True)),
    ("too few for filter", lambda: filter(twice), lambda: filter.__new__(filter, twice)),
    (
        "a keyword on filter",
        lambda: filter(twice, [1], x=1),
        lambda: filter.__new__(filter, twice, [1], x=1),
    ),
    ("a foreign class", lambda: enumerate([1]), lambda: enumerate.__new__(list, [1])),
]
for label, direct, via_new in SPELLINGS:
    print("  %-32s %-52s %s" % (
        label, made(direct), made(via_new)))

print()
print("== what each constructor asks before what")
ASKS = [
    ("a source that is not iterable, a start that is not a number",
     lambda: enumerate(1, start="x")),
    ("a source that is not iterable, a start that complains", lambda: enumerate(1, start=Refusing())),
    ("an iterable and a keyword that is not one of the two", lambda: enumerate(1, iterable=2)),
    ("three for a call of two", lambda: enumerate(1, 2, start=3)),
    ("both names, and one position", lambda: enumerate(1, start=1, iterable=2)),
    ("a strict that complains, before the source is asked", lambda: zip(1, strict=Refusing())),
    ("a strict that complains, before the count", lambda: map(twice, strict=Refusing())),
    ("a strict that complains, with nothing at all", lambda: zip(strict=Refusing())),
    ("a strict read as an iterable", lambda: zip([1], True)),
    ("a keyword-only flag on the new spelling", lambda: zip.__new__(zip, [1], strict=1)),
    ("a keyword before the count, on filter", lambda: filter(twice, 1, x=1)),
    ("the source is asked, the predicate never, on filter", lambda: filter(1, 1)),
    ("the source is asked, the function never, on map", lambda: map(1, 1)),
    ("made in silence, a predicate that is a number", lambda: filter(1, [1])),
    ("made in silence, a function that is a number", lambda: map(1, [1])),
    ("the second source is not iterable, on map", lambda: map(twice, [1], 1)),
    ("the second source is not iterable, on zip", lambda: zip([1], 1)),
]
for label, action in ASKS:
    print("  %-56s %s" % (label, made(action)))

print()
print("== the count, over and under a machine word")
EDGES = [2 ** 63 - 3, 2 ** 63 - 1, 2 ** 63, 2 ** 64, -1, -(2 ** 63) - 1, True, 3.0, "3"]
for start in EDGES:
    print("  start=%-22s %s" % (repr(start), said(lambda start=start: list(enumerate([1, 2], start)))))

print()
print("== the walk")
WALKS = [
    ("enumerate over a string", lambda: list(enumerate("ab", True))),
    ("enumerate over a source that complains", lambda: list(enumerate(Bad()))),
    ("map with None for a function", lambda: list(map(None, [1]))),
    ("map with a function that takes two", lambda: list(map(join, [1, 2], [3, 4]))),
    ("map over one source of none", lambda: list(map(twice, []))),
    ("map with a source that complains", lambda: list(map(twice, Bad()))),
    ("filter with None", lambda: list(filter(None, [0, 1, "a", ()]))),
    ("filter with bool itself", lambda: list(filter(bool, [0, 1, 2]))),
    ("filter with a falsey function", lambda: list(filter(Refusing(), [1]))),
    ("filter with a source that complains", lambda: list(filter(twice, Bad()))),
    ("filter where the number is called on the walk", lambda: list(filter(1, [1]))),
    ("map where the number is called on the walk", lambda: list(map(1, [1]))),
    ("zip of nothing", lambda: list(zip())),
    ("zip to the shortest", lambda: list(zip([1, 2, 3], [4]))),
    ("zip with a source that complains", lambda: list(zip(Bad(), [1]))),
    ("zip of one source, twice stepped", lambda: (lambda z: (next(z, "over"), next(z, "over")))(
        zip([1]))),
    ("zip leaves a later source alone", lambda: (lambda rest: (
        list(zip([], rest)), next(rest, "asked")))(iter([1, 2]))),
    ("map leaves a later source alone", lambda: (lambda rest: (
        list(map(join, [], rest)), next(rest, "asked")))(iter([1, 2]))),
]
for label, action in WALKS:
    print("  %-46s %s" % (label, said(action)))

print()
print("== strict, in both directions and across an error")
STRICTS = [
    ("zip even", lambda: list(zip([1, 2], [3, 4], strict=True))),
    ("zip short", lambda: list(zip([1, 2], [3], strict=True))),
    ("zip long", lambda: list(zip([1], [3, 4], strict=True))),
    ("zip of one short of two", lambda: list(zip([1, 2], [3, 4], [5], strict=True))),
    ("zip of one long of two", lambda: list(zip([1, 2], [3, 4], [5, 6, 7], strict=True))),
    ("zip with the first empty", lambda: list(zip([], [1], strict=True))),
    ("zip with both empty", lambda: list(zip([], [], strict=True))),
    ("zip with a falsey strict", lambda: list(zip([1, 2], [3], strict=[]))),
    ("zip after a step", lambda: (lambda z: (next(z), list(z)))(zip([1, 2], [3], strict=True))),
    ("zip with a source that complains", lambda: list(zip(Bad(), [1], strict=True))),
    ("map even", lambda: list(map(join, [1, 2], [3, 4], strict=True))),
    ("map short", lambda: list(map(join, [1, 2], [3], strict=True))),
    ("map long", lambda: list(map(join, [1], [3, 4], strict=True))),
    ("map with the first empty", lambda: list(map(join, [], [1], strict=True))),
    ("map with both empty", lambda: list(map(join, [], [], strict=True))),
    ("map with three sources one short", lambda: list(map(
        lambda a, b, c: (a, b, c), [1, 2], [3, 4], [5], strict=True))),
    ("map with a source that complains", lambda: list(map(join, Bad(), [1], strict=True))),
    ("map of one source, strict", lambda: list(map(twice, [1, 2], strict=True))),
]
for label, action in STRICTS:
    print("  %-40s %s" % (label, said(action)))

print()
print("== the pickle, born, stepped, spent, and put back")
for name, kind, make in KINDS:
    live = make()
    print("  %-10s born   %s" % (name, said(lambda: reduced(live))))
    print("  %-10s stepped %s" % (name, said(lambda: (next(live, None), reduced(live))[1])))
    print("  %-10s spent  %s" % (name, said(lambda: (list(live), reduced(live))[1])))
    print("  %-10s rebuilt %s" % (name, said(lambda: list(rebuilt(make())))))

print()
print("== the state the arguments cannot carry")
for make in [lambda: zip([1, 2], [3]), lambda: map(join, [1, 2], [3])]:
    strict = make()
    strict.__setstate__(True)
    print("  %-10s set True   reduce=%-14s rest=%s" % (
        type(strict).__name__,
        said(lambda: tuple(strict.__reduce__()[2:])),
        said(lambda: list(strict))))
    falsey = make()
    falsey.__setstate__([])
    print("  %-10s set falsey reduce=%-14s rest=%s" % (
        type(falsey).__name__,
        said(lambda: tuple(falsey.__reduce__()[2:])),
        said(lambda: list(falsey))))
    refused = make()
    print("  %-10s set a complaint %s" % (
        type(refused).__name__, said(lambda: refused.__setstate__(Refusing()))))

print()
print("== the pickle that carries the flag")
for make in [
    lambda: zip([1, 2], [3, 4], strict=True),
    lambda: map(join, [1, 2], [3, 4], strict=True),
    lambda: zip([1, 2], [3, 4]),
]:
    it = make()
    print("  %-10s %s" % (type(it).__name__, said(lambda: reduced(it))))
    print("      three items=%-6s rebuilt %s" % (
        len(it.__reduce__()) == 3, said(lambda: list(rebuilt(it)))))

print()
print("== the rows beside the pickle, on their own and on a stranger")
ROWS = [
    ("zip", zip.__setstate__, zip([1, 2])),
    ("map", map.__setstate__, map(twice, [1, 2])),
    ("enumerate", enumerate.__class_getitem__, None),
    ("filter", filter.__reduce__, filter(twice, [1, 2])),
]
for name, unbound, own in ROWS:
    print("  %-12s %-18s" % (name, unbound.__name__))
    print("      no receiver      %s" % shown(lambda: unbound()))
    print("      a second argument %s" % shown(lambda: unbound(own, 1)))
    print("      a keyword        %s" % shown(lambda: unbound(own, x=1)))
    print("      a stranger twice %s" % shown(lambda: unbound([1], 1)))
    print("      a stranger once  %s" % shown(lambda: unbound([1])))

print()
print("== the one generic of the family")
GENERICS = [
    ("enumerate[int]", lambda: enumerate[int]),
    ("enumerate[int, str]", lambda: enumerate[int, str]),
    ("enumerate[()]", lambda: enumerate[()]),
    ("the alias called", lambda: list(enumerate[int]("ab"))),
    ("an alias of a sequence called", lambda: list[int]([1, 2])),
    ("an alias called with a name", lambda: list[int](x=1)),
    ("the alias is callable", lambda: callable(enumerate[int])),
    ("the alias of the alias", lambda: enumerate[int][str]),
    ("a value subscripted", lambda: enumerate([])[int]),
    ("zip[int]", lambda: zip[int]),
    ("map[int]", lambda: map[int]),
    ("filter[int]", lambda: filter[int]),
    ("reversed[int]", lambda: reversed[int]),
    ("the row on a value", lambda: enumerate([]).__class_getitem__(int)),
    ("the row on the type", lambda: enumerate.__class_getitem__(int)),
    ("the row given two", lambda: enumerate.__class_getitem__(enumerate, int)),
    ("the row given none", lambda: enumerate.__class_getitem__()),
]
for label, action in GENERICS:
    print("  %-32s %s" % (label, shown(action)))
