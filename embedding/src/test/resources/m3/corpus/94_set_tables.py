DESCRIPTOR = type(bytes.hex)

# The twenty sentences set files beside its methods and the eleven frozenset files beside its own, and the
# two halves CPython cuts from each. Everything here is read off the descriptor each book keeps in its own
# dictionary; the bound half is measured as a comparison of texts, never as a type name, because the shape a
# value's own method reports itself as is still this runtime's named deviation. `__class_getitem__` is left
# out of the sweeps in both books: it is the one row clinic never wrote, filed with a sentence but no table,
# so only its sentence is measured here. `__sizeof__` is swept as a text but never called for an answer: it
# reports the bytes an allocator counts, which this runtime named back in slice 31.
SET = [
    "add", "clear", "__contains__", "copy", "discard", "difference", "difference_update", "intersection",
    "intersection_update", "isdisjoint", "issubset", "issuperset", "pop", "__reduce__", "remove",
    "__sizeof__", "symmetric_difference", "symmetric_difference_update", "union", "update",
]
FROZEN = [
    "__contains__", "copy", "difference", "intersection", "isdisjoint", "issubset", "issuperset",
    "__reduce__", "__sizeof__", "symmetric_difference", "union",
]


def table(book, names):
    for name in names:
        method = getattr(book, name)
        print(
            book.__name__, name, "|", repr(method.__text_signature__), "|",
            type(method.__doc__).__name__, "|", repr(method.__doc__),
        )


def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "->", type(e).__name__)


def refuses(label, fn):
    try:
        fn()
        print(label, "-> accepted")
    except Exception as e:
        print(label, "->", type(e).__name__)


print("== the two halves of one text ==")
print("set rows", len(SET), "frozenset rows", len(FROZEN), "kind", DESCRIPTOR.__name__)
print("rows in the dictionaries", sum(1 for n in vars(set) if isinstance(vars(set)[n], DESCRIPTOR)),
      sum(1 for n in vars(frozenset) if isinstance(vars(frozenset)[n], DESCRIPTOR)))
table(set, SET)
table(frozenset, FROZEN)

print("== one macro, two books ==")
print("names both books file", len(set(SET) & set(FROZEN)))
for name in FROZEN:
    print(name, "|", getattr(set, name).__doc__ == getattr(frozenset, name).__doc__, "|",
          getattr(set, name).__text_signature__ == getattr(frozenset, name).__text_signature__, "|",
          getattr(set, name).__qualname__, "|", getattr(frozenset, name).__qualname__)

print("== the sentence clinic copied rather than wrote ==")
print("frozenset's copy says", repr(frozenset.copy.__doc__.split("\n")[-1]))
print("frozenset's contains says", repr(frozenset.__contains__.__doc__.split("\n")[-1]))
print("two symbols, one text", set.copy.__doc__ == frozenset.copy.__doc__)
print("nine rows share a macro", sum(1 for name in FROZEN[2:]
                                    if getattr(set, name).__doc__ == getattr(frozenset, name).__doc__))

print("== whose table names what ==")
for book, names in ((set, SET), (frozenset, FROZEN)):
    print(book.__name__, "with $self", sum(1 for name in names
                                           if getattr(book, name).__text_signature__.startswith("($self")))
    print(book.__name__, "with $type", sum(1 for name in names
                                           if getattr(book, name).__text_signature__.startswith("($type")))
    print(book.__name__, "ending with /)", sum(1 for name in names
                                                if getattr(book, name).__text_signature__.endswith("/)")))
    print(book.__name__, "with a star", sum(1 for name in names
                                            if "*, " in getattr(book, name).__text_signature__))
    print(book.__name__, "with *others", sum(1 for name in names
                                             if "*others)" in getattr(book, name).__text_signature__))
    print(book.__name__, "naming object", sum(1 for name in names
                                              if "object," in getattr(book, name).__text_signature__))
    print(book.__name__, "naming other", sum(1 for name in names
                                             if "other," in getattr(book, name).__text_signature__))

print("== the row with a sentence and no table ==")
for book in (set, frozenset):
    row = book.__dict__["__class_getitem__"]
    print(book.__name__, "|", type(row).__name__, "|", repr(row.__doc__), "|",
          repr(getattr(book, "__class_getitem__").__doc__), "|", type(getattr(book, "__class_getitem__")).__name__)

print("== one text, two readers ==")
for name in ["union", "difference", "add", "pop", "__contains__", "__sizeof__", "isdisjoint"]:
    bound = getattr({1, 2}, name)
    print(name, "|", bound.__doc__ == getattr(set, name).__doc__, "|", bound.__name__, "|",
          bound.__qualname__, "|", type(bound.__self__).__name__)
for name in ["union", "copy", "__reduce__"]:
    bound = getattr(frozenset([1]), name)
    print(name, "|", bound.__doc__ == getattr(frozenset, name).__doc__, "|", bound.__name__, "|",
          bound.__qualname__, "|", type(bound.__self__).__name__)

print("== the doc half is a read, not a field ==")
refuses("write set.union.__doc__", lambda: setattr(set.union, "__doc__", "x"))
refuses("delete set.union.__doc__", lambda: delattr(set.union, "__doc__"))
refuses("write the dictionary row's doc", lambda: setattr(set.__dict__["union"], "__doc__", "x"))
refuses("write {1}.union.__doc__", lambda: setattr({1}.union, "__doc__", "x"))
refuses("write frozenset.copy.__text_signature__",
        lambda: setattr(frozenset.copy, "__text_signature__", "()"))
refuses("delete frozenset.copy.__text_signature__", lambda: delattr(frozenset.copy, "__text_signature__"))
show("the row and the read are one text", lambda: set.__dict__["union"].__doc__ == set.union.__doc__)
show("a second read of the doc", lambda: getattr(set.add, "__doc__") == set.add.__doc__)
show("a second read of the table", lambda: getattr(frozenset.union, "__text_signature__")
     == frozenset.union.__text_signature__)

print("== what the text does not change ==")
show("call the dictionary row", lambda: set.__dict__["difference"]({1, 2}, {2}))
show("call with two others", lambda: sorted({1, 2, 3}.difference({1}, {3})))
show("call union with a list", lambda: sorted({1}.union([2, 2])))
show("call update", lambda: sorted({1}.copy() | {2}))
show("call add and pop", lambda: ({1}.add(2), {5}.pop())[1])
show("call a frozenset's copy", lambda: frozenset([1]).copy() == frozenset([1]))
show("call isdisjoint", lambda: {1}.isdisjoint({2}))
show("call __contains__", lambda: {1}.__contains__(1))
show("call __sizeof__ with an argument", lambda: {1}.__sizeof__(2))
show("call __reduce__", lambda: type({1}.__reduce__()[0]).__name__)
show("a keyword finds no place", lambda: {1}.add(element=2))
show("one argument too many", lambda: {1}.add(2, 3))
show("frozenset has no add", lambda: dir(frozenset) == dir(set))
print("rows only set files", sorted(set(SET) - set(FROZEN)))
