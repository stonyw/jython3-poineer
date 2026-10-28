DESCRIPTOR = type(str.upper)

# The fourteen sentences list files beside `list_methods` and the three tuple files beside `tuple_methods`,
# and the two halves CPython cuts from each. Everything is read off the descriptor each type keeps in its own
# dictionary; the bound half is measured as a comparison of texts, never as a type name, because the shape a
# value's own method reports itself as is still this runtime's named deviation. `__class_getitem__` is left
# out of both sweeps: it is the row clinic never wrote, filed with a sentence and no table, so only its
# sentence is measured. `tuple.__getnewargs__` is kept in, and is the row whose text is a table with nothing
# after it. `str.__getnewargs__` is the other kind of silence — a row CPython registers with no text at all,
# whose table the reference invents from the METH_NOARGS flag and this runtime leaves unanswered.
# `list.__sizeof__` and `tuple`'s allocator answers are swept as texts but never called: they count bytes of
# a C struct this runtime does not have, which is this runtime's own named deviation from slice 31.
LIST = [
    "__getitem__", "__reversed__", "__sizeof__", "clear", "copy", "append", "insert", "extend", "pop",
    "remove", "index", "count", "reverse", "sort",
]
TUPLE = ["__getnewargs__", "index", "count"]


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
print("list rows", len(LIST), "tuple rows", len(TUPLE), "kind", DESCRIPTOR.__name__)
print("rows in the dictionaries", sum(1 for n in vars(list) if isinstance(vars(list)[n], DESCRIPTOR)),
      sum(1 for n in vars(tuple) if isinstance(vars(tuple)[n], DESCRIPTOR)))
table(list, LIST)
table(tuple, TUPLE)

print("== two tables, two texts the same ==")
print("names both tables file", sorted(set(LIST) & set(TUPLE)))
for name in TUPLE[1:]:
    print(name, "|", list.__dict__[name].__doc__ == tuple.__dict__[name].__doc__, "|",
          list.__dict__[name].__text_signature__ == tuple.__dict__[name].__text_signature__, "|",
          list.__dict__[name].__qualname__, "|", tuple.__dict__[name].__qualname__)

print("== whose table names what ==")
for book, names in ((list, LIST), (tuple, TUPLE)):
    tables = [getattr(book, name).__text_signature__ for name in names]
    print(book.__name__, "with $self", sum(1 for t in tables if t.startswith("($self")))
    print(book.__name__, "with $type", sum(1 for t in tables if t.startswith("($type")))
    print(book.__name__, "ending with /)", sum(1 for t in tables if t.endswith("/)")))
    print(book.__name__, "with a default", sum(1 for t in tables if "=" in t))
    print(book.__name__, "with a star", sum(1 for t in tables if "*, " in t))
    print(book.__name__, "naming index", sum(1 for t in tables if "index" in t))
    print(book.__name__, "shorter than forty", sum(1 for t in tables if len(t) < 40))
    print(book.__name__, "widest table", max(len(t) for t in tables))

print("== the row with a table and nothing after it ==")
row = tuple.__dict__["__getnewargs__"]
print("descriptor", type(row).__name__, "| doc", repr(row.__doc__), "| table",
      repr(row.__text_signature__), "| call", repr((1, 2).__getnewargs__()))
print("silence in two kinds", "| tuple doc", repr(tuple.__getnewargs__.__doc__),
      "| str doc", repr(str.__getnewargs__.__doc__), "| str table",
      type(str.__dict__["__getnewargs__"]).__name__)

print("== one text, two readers ==")
for name in ["append", "sort", "pop", "index", "__getitem__", "__reversed__", "__sizeof__", "count"]:
    bound = getattr([1, 2], name)
    print(name, "|", bound.__doc__ == getattr(list, name).__doc__, "|", bound.__name__, "|",
          bound.__qualname__, "|", type(bound.__self__).__name__)
for name in ["count", "index", "__getnewargs__"]:
    bound = getattr((1, 2), name)
    print(name, "|", bound.__doc__ == getattr(tuple, name).__doc__, "|", bound.__name__, "|",
          bound.__qualname__, "|", type(bound.__self__).__name__)

print("== the doc half is a read, not a field ==")
refuses("write list.sort.__doc__", lambda: setattr(list.sort, "__doc__", "x"))
refuses("delete list.sort.__doc__", lambda: delattr(list.sort, "__doc__"))
refuses("write the dictionary row's doc", lambda: setattr(list.__dict__["append"], "__doc__", "x"))
refuses("write [1].append.__doc__", lambda: setattr([1].append, "__doc__", "x"))
refuses("write tuple.index.__text_signature__",
        lambda: setattr(tuple.index, "__text_signature__", "()"))
refuses("delete tuple.index.__text_signature__", lambda: delattr(tuple.index, "__text_signature__"))
show("the row and the read are one text", lambda: list.__dict__["sort"].__doc__ == list.sort.__doc__)
show("a second read of the doc", lambda: getattr(list.pop, "__doc__") == list.pop.__doc__)
show("a second read of the table", lambda: getattr(tuple.index, "__text_signature__")
     == tuple.index.__text_signature__)

print("== what the text does not change ==")
show("call the dictionary row", lambda: list.__dict__["copy"]([1, 2]))
show("call append", lambda: ([].append(1), [1])[1] == [1])
show("call extend with a tuple", lambda: len([1].__class__([1]).extend((2, 3)) or [1, 2, 3]))
show("call pop with no index", lambda: [1, 2].pop())
show("call pop with an index", lambda: [1, 2].pop(0))
show("call insert", lambda: ([1, 3].insert(1, 2) or [1, 2, 3])[1])
show("call sort by key", lambda: ["b", "a"].sort(key=str.lower) or None)
show("call sort descending", lambda: [1, 2].sort(reverse=True) or None)
show("call index with a stop", lambda: [1, 2, 3].index(3, 0, 3))
show("call __getitem__ with a slice", lambda: [1, 2, 3].__getitem__(slice(1, 3)))
show("call __reversed__", lambda: next([1, 2].__reversed__()))
show("call a tuple's count", lambda: (1, 2, 1).count(1))
show("call a tuple's index", lambda: (1, 2).index(2))
show("a keyword finds no place", lambda: [1].append(object=2))
show("one argument too many", lambda: [1].count(1, 2))
show("no argument where one is asked", lambda: (1,).count())
show("the type's own sentence", lambda: list.__doc__.startswith("Built-in mutable sequence."))
print("names only list files", sorted(set(LIST) - set(TUPLE)))
