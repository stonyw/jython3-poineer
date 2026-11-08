"""What a cursor files for itself: the three location cursors keep three rows where a sequence cursor keeps six.

Every cursor the language hands out is a type of its own with a book of its own, and the size of that book is
what its C declaration gives it. `list_iterator` is declared with `listiter_methods` — `__iter__`, `__next__`,
`__length_hint__`, `__reduce__`, `__setstate__` — and a sentence, so its dictionary holds six rows and `dir()`
of one of its values names twenty-eight. `line_iterator`, `positions_iterator` and the branches iterator are
declared with nothing but the two `PyMethodDef` rows that copy `tp_iter` and `tp_iternext`: `tp_members`,
`tp_getset` and `tp_doc` are null, `tp_new` is null, `tp_dictoffset` is zero. Three rows, two fewer than the
sequence cursor, and the two that are gone are the ones a person asks a cursor for — a length and a way to be
pickled.

Those three rows are measured below as rows, and the two live ones are read as descriptors: a
`wrapper_descriptor` whose `__doc__` is the same "Implement iter(self)." every cursor in the interpreter
carries, whose `__text_signature__` is `($self, /)`, whose `__objclass__` names the cursor type and which has
no `__module__` at all. Called with a receiver it refuses two ways, and which way depends on the route:
`tp_descr_get` checks the type first and says `descriptor '__iter__' for 'line_iterator' objects doesn't apply
to a 'int' object`, while the call is checked by `check_self` inside the wrapper and says `descriptor
'__next__' requires a 'line_iterator' object but received a 'int'`.

The two types that share the name `line_iterator` are here too, because they share the two rows that name
themselves in those sentences: a branches cursor and a lines cursor are not instances of each other's type, and
passing one to the other's `__next__` asks for a `line_iterator` and receives one. What a row belongs to is its
type's dictionary, not its name, which is why the two books are separate objects of equal length holding
descriptors that never compare equal.

Writing a row is refused at three levels: on the class, because the type is immutable, where the delete says
"cannot set" exactly as the set does; on a cursor value, because the attribute is read-only there; and through
`__dict__`, because a mappingproxy takes no assignment. Calling any of the three types is refused before its
arguments are read, since `tp_new` is null, so no argument list changes the sentence and `object.__new__` is
refused with it.

Three faces of these types are left unmeasured. A cursor's `__flags__` and `__sizeof__` are answered as zero by
this runtime, and a cursor refuses to be subclassed where the reference builds the subclass — all three named
elsewhere. The columns of a positions row are left out for a different reason: they are turned off by a switch
the two ways of running the reference here do not agree about, so a positions row is a quadruple of four
numbers read one way and of two read the other. What is measured of that cursor is its count, its type and the
rows around it.
"""


def jump(a, b):
    if a:
        b = 1
    return a or b


def told(action):
    try:
        return "ok " + shape(action())
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def shape(value):
    if isinstance(value, tuple):
        return "tuple of %d" % len(value)
    return short(value)


def short(value):
    return repr(value).split(" at ")[0]


def assign(book, name):
    book[name] = 1
    return "assigned"


CODE = jump.__code__
LINE_CURSOR = type(CODE.co_lines())
BRANCH_CURSOR = type(CODE.co_branches())
POSITION_CURSOR = type(CODE.co_positions())
SEQUENCE_CURSOR = type([1, 2].__iter__())
REVERSED_CURSOR = type(reversed([]))

THREE_BOOKS = ((LINE_CURSOR, lambda: CODE.co_lines()),
               (POSITION_CURSOR, lambda: CODE.co_positions()),
               (BRANCH_CURSOR, lambda: CODE.co_branches()))

print("== three books of three rows, and two books of six ==")
for owner, fresh in THREE_BOOKS:
    print(owner.__name__, "rows", len(vars(owner)), list(vars(owner)))
    print("  dir", len(dir(fresh())), "gained", [n for n in dir(owner) if n not in dir(object)])
    print("  doc", repr(owner.__doc__), "sig", repr(getattr(owner, "__text_signature__", "MISSING")))
    print("  bases", [b.__name__ for b in owner.__bases__], "mro", [m.__name__ for m in owner.__mro__])
    print("  __new__ in dir", "__new__" in dir(owner), "in vars", "__new__" in vars(owner))
    print("  __length_hint__ in dir", "__length_hint__" in dir(owner))
for owner, value in ((SEQUENCE_CURSOR, [1, 2].__iter__()), (REVERSED_CURSOR, reversed([]))):
    print(owner.__name__, "rows", len(vars(owner)), list(vars(owner)))
    print("  dir", len(dir(value)), "gained", [n for n in dir(owner) if n not in dir(object)])

print("== the two rows of each book, as descriptors ==")
for owner, _ in THREE_BOOKS:
    for name in ("__iter__", "__next__"):
        row = vars(owner)[name]
        print(owner.__name__, name, type(row).__name__, short(row))
        print("  doc", repr(row.__doc__), "sig", repr(row.__text_signature__))
        print("  qual", row.__qualname__, "objclass", row.__objclass__.__name__)
        print("  module", getattr(row, "__module__", "NOATTR"), "dir", len(dir(row)))
        print("  named for its own type", row.__qualname__ == "%s.%s" % (owner.__name__, name))

print("== a row called with the receiver it wants ==")
for owner, fresh in THREE_BOOKS:
    held = fresh()
    print(owner.__name__, "iter", told(lambda: vars(owner)["__iter__"](held) is held))
    print(owner.__name__, "next", told(lambda: vars(owner)["__next__"](held)))
    print(owner.__name__, "next twice", [told(lambda: vars(owner)["__next__"](held)) for _ in range(2)])

print("== the same two rows read off a value ==")
line = CODE.co_lines()
print("iter(line) is line", iter(line) is line)
print("bound next", type(line.__next__).__name__, line.__next__.__self__ is line)
print("bound iter", type(line.__iter__).__name__, line.__iter__() is line)
print("first row", short(line.__next__()))
print("walk of a fresh cursor", [short(row) for row in list(CODE.co_lines())][:3])
print("count", len(list(CODE.co_lines())), "again", len(list(CODE.co_lines())))
print("positions", len(list(CODE.co_positions())), "branches", len(list(CODE.co_branches())))
print("branch rows", [short(row) for row in CODE.co_branches()][:3])

print("== the wrong receiver, by both routes ==")
for owner, _ in THREE_BOOKS:
    for name in ("__iter__", "__next__"):
        row = vars(owner)[name]
        for wrong in (3, "x", CODE, None, [1, 2].__iter__()):
            print("call", owner.__name__, name, type(wrong).__name__,
                  told(lambda row=row, wrong=wrong: row(wrong)))
        print("__get__", owner.__name__, name, told(lambda row=row: row.__get__(3, owner)))

print("== two types, one name ==")
print("line is branch", LINE_CURSOR is BRANCH_CURSOR)
print("names", LINE_CURSOR.__name__, BRANCH_CURSOR.__name__)
print("same doc row", vars(LINE_CURSOR)["__doc__"] is vars(BRANCH_CURSOR)["__doc__"])
print("same next row", vars(LINE_CURSOR)["__next__"] is vars(BRANCH_CURSOR)["__next__"])
print("equal next rows", vars(LINE_CURSOR)["__next__"] == vars(BRANCH_CURSOR)["__next__"])
print("objclass", vars(LINE_CURSOR)["__next__"].__objclass__ is LINE_CURSOR,
      vars(BRANCH_CURSOR)["__next__"].__objclass__ is BRANCH_CURSOR)
line_value = CODE.co_lines()
branch_value = CODE.co_branches()
print("isinstance", isinstance(line_value, LINE_CURSOR), isinstance(line_value, BRANCH_CURSOR),
      isinstance(branch_value, BRANCH_CURSOR), isinstance(branch_value, LINE_CURSOR))
print("cross", told(lambda: vars(LINE_CURSOR)["__next__"](branch_value)))
print("cross back", told(lambda: vars(BRANCH_CURSOR)["__next__"](line_value)))

print("== writing a row, at three levels ==")
cursor = CODE.co_lines()
for name in ("__iter__", "__next__"):
    print("class set", name, told(lambda name=name: setattr(LINE_CURSOR, name, 1)))
    print("class del", name, told(lambda name=name: delattr(LINE_CURSOR, name)))
    print("value set", name, told(lambda name=name: setattr(cursor, name, 1)))
    print("value del", name, told(lambda name=name: delattr(cursor, name)))
    print("dict set", name, told(lambda name=name: assign(LINE_CURSOR.__dict__, name)))
print("the row the writes could not move", short(vars(LINE_CURSOR)["__next__"]))
print("the value still walks", len(list(cursor)))

print("== no way to make one ==")
for owner, _ in THREE_BOOKS:
    print("call", owner.__name__, told(lambda owner=owner: owner()))
    print("with an argument", owner.__name__, told(lambda owner=owner: owner(3)))
    print("with a keyword", owner.__name__, told(lambda owner=owner: owner(start=1)))
    print("tp_new", owner.__name__, told(lambda owner=owner: owner.__new__(owner)))
    print("object.__new__", owner.__name__, told(lambda owner=owner: object.__new__(owner)))
print("control call", SEQUENCE_CURSOR.__name__, told(lambda: SEQUENCE_CURSOR()))
print("control new", SEQUENCE_CURSOR.__name__, told(lambda: SEQUENCE_CURSOR.__new__(SEQUENCE_CURSOR)))

print("== what a cursor value carries ==")
for owner, fresh in THREE_BOOKS + ((SEQUENCE_CURSOR, lambda: [1, 2].__iter__()),):
    value = fresh()
    print(owner.__name__, "dict", hasattr(value, "__dict__"), "weak", hasattr(value, "__weakref__"),
          "hint", hasattr(value, "__length_hint__"), "doc", repr(value.__doc__))
    print("  reduce", told(lambda value=value: value.__reduce__()))
    print("  getstate", told(lambda value=value: value.__getstate__()))
    print("  repr", short(value), "named", type(value).__name__)

print("== an exhausted cursor, read three ways ==")
dead = CODE.co_lines()
while True:
    try:
        dead.__next__()
    except StopIteration:
        break
next_row = vars(LINE_CURSOR)["__next__"]
print("row", told(lambda: next_row(dead)))
print("bound", told(lambda: dead.__next__()))
print("builtin", told(lambda: next(dead)))
print("a fresh one answers", told(lambda: CODE.co_lines().__next__()))
