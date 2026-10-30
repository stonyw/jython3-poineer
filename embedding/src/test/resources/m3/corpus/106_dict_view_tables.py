import builtins


def said(action):
    try:
        return "ok " + repr(action())[:78]
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:78]


def walk_then_grow(source, view):
    """One step of a reverse walk, a key added under it, and the rest of the walk."""
    steps = reversed(view)
    first = next(steps)
    source["d"] = 4
    try:
        return [first, list(steps)]
    finally:
        source.pop("d")


def said_kind(action):
    try:
        return "ok " + type(action()).__name__
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:78]


def said_word(action):
    """Only the class of what went wrong, where the sentence names a type this runtime calls another."""
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__


def said_masked(action):
    """The repr a value answers, with the address the reference writes beside it taken back out."""
    try:
        made = action()
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:78]
    text = made if isinstance(made, str) else repr(made)
    before, at, after = text.rpartition(" at ")
    return repr(before + ">") if at else repr(text)


def shown(entry):
    """A row of a type dictionary: its kind and its sentence, or the bare None where the row is one."""
    if entry is None:
        return "the value None"
    return type(entry).__name__ + " | " + repr(entry.__doc__)


def named(values):
    return sorted(map(repr, values))


SOURCE = {"a": 1, "b": 2, "c": 3}
KEYS, VALUES, ITEMS = SOURCE.keys(), SOURCE.values(), SOURCE.items()
KEYS_TYPE, VALUES_TYPE, ITEMS_TYPE = (type(view) for view in (KEYS, VALUES, ITEMS))
KINDS = (KEYS_TYPE, VALUES_TYPE, ITEMS_TYPE)
VIEWS = (KEYS, VALUES, ITEMS)
OTHER = {"a", "z"}

# The three dictionaries the dict views keep for themselves, measured against CPython 3.14.8.
#
# All three types are written in `Objects/dictobject.c` — `PyDictKeys_Type` (:6534), `PyDictValues_Type`
# (:6736) and `PyDictItems_Type` (:6646) — with a dotless `tp_name` and no entry in the builtins table, so
# `type({}.keys())` is the only way Python reaches them and each still reads `builtins` as its module. None
# of the three fills `tp_new`, which is why the three names cannot be called. The rows come out of the
# struct in the order `type_ready` walks it: `tp_repr`, the six of `tp_richcompare`, `tp_iter`, the eight of
# `tp_as_number`, `tp_length`, `sq_contains`, then the `PyMethodDef` list, then the one `PyGetSetDef`, and
# then the sentence the type carries.
#
# `dict_values` is the interesting one because of what it leaves out. Its struct fills neither
# `tp_richcompare` nor `tp_as_number`, so it files no comparison and no operator — and the pair the
# inheritance rule copies is a `tp_richcompare` *and* a `tp_hash` together, so leaving the first null is
# what lets a values view keep `object`'s identity hash and be the only dict view a program can put in a
# set. The other two file the six comparisons and the eight operators, decline the hash with a trailing
# `None` that `type_ready_set_hash` appends after `__doc__`, and so come to twenty-three names against six.
print("== the three kinds, which are not names")
for kind in KINDS:
    print("   ", kind.__name__, said(lambda k=kind: repr(k)), "| module", kind.__module__,
          "| bases", kind.__bases__, "| mro", [k.__name__ for k in kind.__mro__])
print("   in builtins", [k.__name__ in dir(builtins) for k in KINDS])
print("   callable", [said(lambda k=k: k()) for k in KINDS])
print("   the three values", [said_masked(lambda v=v: v) for v in VIEWS])

print("== the twenty-three, the six, the twenty-three")
for kind in KINDS:
    print("   ", kind.__name__, len(vars(kind)))
    for name, entry in vars(kind).items():
        print("     ", name, shown(entry))

print("== the two rows that carry a None")
print("   hash row", [k.__dict__.get("__hash__") for k in KINDS],
      "| doc row", [k.__dict__.get("__doc__") for k in KINDS])
print("   read as attributes", [said(lambda k=k: k.__hash__) for k in KINDS],
      "| the type's own sentence", [kind.__doc__ for kind in KINDS])
print("   hash", [said_kind(lambda v=v: hash(v)) for v in VIEWS])
print("   a values view as a key", said(lambda: {VALUES: "kept"}[VALUES]),
      "| in a set", said(lambda: VALUES in {VALUES}),
      "| twice", said(lambda: len({VALUES, VALUES})))

print("== what a value of each kind answers")
for view in VIEWS:
    kind = type(view)
    print("   ", kind.__name__, "| len", len(view), "| iter", list(view),
          "| dir", len(dir(view)), sorted(n for n in dir(view) if not n.startswith("__")))
print("   dir of the keys view", sorted(dir(KEYS)))
print("   membership", "a" in KEYS, ("a", 1) in ITEMS, 1 in VALUES, 3 in VALUES, "c" in VALUES)
print("   the test row", [said(lambda k=kind, v=view: k.__dict__["__contains__"](v, "a"))
                          for kind, view in zip(KINDS, VIEWS)])
print("   vars", said(lambda: vars(KEYS)), "|", said(lambda: KEYS.__dict__))

print("== the window follows the dict it was made from")
watched = {}
window = watched.keys()
watched["x"] = 1
print("   grew", len(window), list(window), repr(window))
del watched["x"]
print("   shrank", len(window), repr(window), "| mapping", dict(window.mapping))
print("   the pairs", repr({}.items()), repr({1: [2]}.items()), repr({1: [2]}.values()))

print("== the one name that is neither a slot nor a method")
for kind in KINDS:
    row = kind.__dict__["mapping"]
    print("   ", kind.__name__, said_masked(lambda r=row: r), "|", row.__name__, row.__qualname__,
          row.__objclass__.__name__, repr(row.__doc__), "|", said_kind(lambda r=row: r.__set__))
print("   through the class", said_kind(lambda: KEYS_TYPE.__dict__["mapping"].__get__(None, KEYS_TYPE)))
print("   a fresh window each read", KEYS.mapping is KEYS.mapping,
      type(KEYS.mapping).__name__, repr(KEYS.mapping))
print("   the same dict", KEYS.mapping["a"], list(KEYS.mapping), len(VALUES.mapping))
print("   wrong receiver", said(lambda: KEYS_TYPE.__dict__["mapping"].__get__(3, KEYS_TYPE)))
print("   written", said(lambda: setattr(KEYS, "mapping", {})),
      "|", said(lambda: delattr(KEYS, "mapping")))
print("   the proxy refuses a write", said(lambda: KEYS.mapping.__setitem__("z", 1)),
      "|", said(lambda: KEYS.mapping.pop("a", None)))
print("   keys of the mapping back", dict(VALUES.mapping), ITEMS.mapping is not SOURCE)

print("== the reverse walk each type files")
print("   lists", list(reversed(KEYS)), list(reversed(VALUES)), list(reversed(ITEMS)))
print("   through the rows", [named(kind.__dict__["__reversed__"](view))
                              for kind, view in zip(KINDS, VIEWS)])
print("   bound", [said_masked(lambda v=v: v.__reversed__) for v in VIEWS])
print("   the rows", [(kind.__name__, kind.__dict__["__reversed__"].__qualname__,
                       kind.__dict__["__reversed__"].__text_signature__,
                       kind.__dict__["__reversed__"].__doc__) for kind in KINDS])
print("   extra", said(lambda: KEYS_TYPE.__reversed__(KEYS, 1)),
      "|", said(lambda: KEYS_TYPE.__reversed__(KEYS, x=1)),
      "|", said(lambda: KEYS_TYPE.__reversed__()))
print("   a second reversal", said_word(lambda: list(reversed(reversed(KEYS)))))
print("   an iterator has no end", said_word(lambda: reversed(iter(KEYS))))
print("   changed underneath", said(lambda: walk_then_grow(SOURCE, KEYS)))
print("   of the pairs", said(lambda: walk_then_grow(SOURCE, ITEMS)))
print("   back to three", list(reversed(KEYS)), repr(KEYS))

print("== the four operators, and the eight rows that answer them")
second = {"a": 1, "b": 9}
print("   keys", named(KEYS - OTHER), named(KEYS & OTHER), named(KEYS | OTHER), named(KEYS ^ OTHER))
print("   items", named(ITEMS - second.items()), named(ITEMS & second.items()),
      named(ITEMS ^ second.items()))
print("   other side", named(OTHER - KEYS), named(OTHER & KEYS), named(OTHER | KEYS),
      named(OTHER ^ KEYS))
print("   from a list", named([2, "a"] - KEYS), named([2, "a"] & KEYS), named([2, "a"] | KEYS))
print("   values has none", said_word(lambda: VALUES & {1}), "|",
      said_word(lambda: VALUES - {1}), "|", said(lambda: VALUES.__and__({1})))
print("   the number rows", [len([n for n in vars(kind) if n in
                                 ("__sub__", "__rsub__", "__and__", "__rand__", "__xor__",
                                  "__rxor__", "__or__", "__ror__")]) for kind in KINDS])
print("   in place", [said_word(lambda kind=kind, view=view: kind.__dict__["__isub__"](view, OTHER))
                      for kind, view in zip(KINDS, VIEWS)])
print("   the slots decline", said(lambda: KEYS.__sub__(3)), "|",
      said(lambda: ITEMS.__and__(3)), "|", said(lambda: ITEMS.__or__(3)))
print("   a set's own answer", named(OTHER - set("z")), "|", named(OTHER ^ set("a")))

print("== the six comparisons, which rank a subset")
print("   equal", KEYS == {"a", "b", "c"}, ITEMS == {"a": 1, "b": 2, "c": 3}.items(),
      KEYS == ITEMS, KEYS != ITEMS)
print("   ranked", KEYS < {"a", "b", "c", "d"}, KEYS <= {"a"}, KEYS > {"a"}, KEYS >= {"a", "b"})
print("   values compares by identity", VALUES == [1, 2, 3], VALUES == {1, 2, 3},
      VALUES == VALUES, VALUES != list(VALUES))
print("   a view against a view", KEYS == dict(a=1, b=2, c=3).keys(),
      ITEMS != dict(a=1).items(), KEYS < dict(a=1).keys())
print("   the comparison rows", [len([n for n in vars(kind)
                                      if n in ("__lt__", "__le__", "__eq__", "__ne__",
                                               "__gt__", "__ge__")]) for kind in KINDS])
print("   declines", said(lambda: KEYS_TYPE.__lt__(KEYS, ["a"])), "|",
      said(lambda: KEYS_TYPE.__eq__(KEYS, ["a"])), "|",
      said(lambda: KEYS_TYPE.__ne__(KEYS, ["a"])), "|",
      said(lambda: KEYS == ["a"]), "|", said(lambda: KEYS != ["a"]), "|",
      said_word(lambda: KEYS < ["a"]))
print("   values asks object", said(lambda: VALUES_TYPE.__dict__.get("__eq__")), "|",
      said(lambda: VALUES == VALUES))

print("== the one method a values view lacks")
print("   plain names", [(kind.__name__, [n for n in vars(kind) if not n.startswith("_")])
                         for kind in KINDS])
print("   isdisjoint", KEYS.isdisjoint(["z"]), KEYS.isdisjoint(["a"]),
      ITEMS.isdisjoint([("a", 9)]), ITEMS.isdisjoint([["a"]]), said(lambda: VALUES.isdisjoint))
print("   through the row", said(lambda: KEYS_TYPE.__dict__["isdisjoint"](KEYS, ["a"])),
      "|", said(lambda: KEYS_TYPE.__dict__["isdisjoint"](KEYS)),
      "|", said(lambda: KEYS_TYPE.__dict__["isdisjoint"](VALUES, [])))
print("   itself", KEYS.isdisjoint(KEYS), ITEMS.isdisjoint(ITEMS), {}.keys().isdisjoint({}.keys()))
print("   a non-iterable", said_word(lambda: KEYS.isdisjoint(3)), "|",
      said(lambda: ITEMS.isdisjoint([[1]])), "|", said(lambda: ITEMS.isdisjoint([1, 2])))
print("   the bound row", said_masked(lambda: KEYS.isdisjoint), KEYS.isdisjoint.__qualname__,
      KEYS.isdisjoint.__name__, repr(ITEMS_TYPE.isdisjoint.__doc__))

print("== the wrappers refuse in the wrapper's words")
for kind, view in zip(KINDS, VIEWS):
    print("   ", kind.__name__, said(lambda k=kind, v=view: k.__dict__["__len__"](v)),
          "|", said(lambda k=kind: k.__dict__["__len__"]([])),
          "|", said(lambda k=kind: k.__dict__["__len__"]()),
          "|", said(lambda k=kind: k.__dict__["__len__"](k, x=1)))
print("   contains", said(lambda: KEYS_TYPE.__dict__["__contains__"](KEYS)), "|",
      said(lambda: KEYS_TYPE.__dict__["__contains__"](KEYS, "a", "b")))
print("   iter", said(lambda: list(KEYS_TYPE.__dict__["__iter__"](KEYS)) == ["a", "b", "c"]), "|",
      said(lambda: KEYS_TYPE.__dict__["__iter__"](KEYS, 1)))
print("   repr", said(lambda: KEYS_TYPE.__dict__["__repr__"](KEYS)), "|",
      said(lambda: KEYS_TYPE.__dict__["__repr__"](3)))
print("   one row per kind", [[kind.__dict__["__len__"] is kind.__dict__["__len__"] for kind in KINDS]])
print("   the kinds are three", len({id(kind.__dict__["__len__"]) for kind in KINDS}))

print("== the same names, read the two ways")
print("   a row read off the type", [said_kind(lambda v=v: v.mapping) for v in VIEWS])
print("   a dict's own walk", said(lambda: list(dict.fromkeys(["a", "b"]).__reversed__())),
      "|", said(lambda: [list(reversed(dict.fromkeys(["a", "b"])))]))
print("   the views of an empty dict", repr({}.keys()), named({}.keys() - {"a"}),
      said(lambda: len({}.values())), list(reversed({}.items())))
