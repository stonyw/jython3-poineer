DESCRIPTOR = type(str.upper)

# The fifteen sentences `mapp_methods` files beside the dictionary type (Objects/dictobject.c:4812), each read
# as the whole `ml_doc`, and the two halves CPython cuts from it. Thirteen of the fifteen come out of
# `Objects/clinic/dictobject.c.h` through the `*_METHODDEF` macro the table names; two are written by hand
# under their own `PyDoc_STRVAR` — `__getitem__` with a table like clinic's and `update` without one, which is
# why `update` is the row whose sentence has no table to cut and the reference reads its `__text_signature__`
# as `None` exactly as this runtime does. Everything is read off the descriptor the type keeps in its own
# dictionary; the bound half is measured as a comparison of texts, never as a type name, because the shape a
# value's own method reports itself as is still this runtime's named deviation. `__class_getitem__` is left out
# of the sweeps: it is the table's sixteenth row, filed with a sentence and no table, and its
# `($type, object, /)` the reference reads out of the METH_O|METH_CLASS flags rather than transcribing.
# `__sizeof__` is swept as a text and never called for an answer: it counts bytes of a C struct this runtime
# does not have, which is this runtime's own named deviation from slice 31.
DICT = ["__contains__", "__getitem__", "__sizeof__", "get", "setdefault", "pop", "popitem", "keys",
        "items", "values", "update", "fromkeys", "clear", "copy", "__reversed__"]


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
print("dict rows", len(DICT), "kind", DESCRIPTOR.__name__)
print("rows in the dictionary", sum(1 for n in vars(dict) if isinstance(vars(dict)[n], DESCRIPTOR)))
table(dict, DICT)

print("== the sentence with no table to cut ==")
update = dict.__dict__["update"]
print("update", "|", type(update).__name__, "|", repr(update.__text_signature__), "|",
      repr(update.__doc__.split("\n")[0]))
print("update lines", len(update.__doc__.split("\n")))
print("the row and the read are one text", update.__doc__ == dict.update.__doc__)
print("a row whose table is not None", repr(dict.__dict__["get"].__text_signature__))

print("== whose table names what ==")
found = [getattr(dict, name).__text_signature__ for name in DICT]
print("rows with no table", sum(1 for t in found if t is None))
tables = [t for t in found if t is not None]
print("dict", "with $self", sum(1 for t in tables if t.startswith("($self")))
print("dict", "with $type", sum(1 for t in tables if t.startswith("($type")))
print("dict", "ending with /)", sum(1 for t in tables if t.endswith("/)")))
print("dict", "with a default", sum(1 for t in tables if "=" in t))
print("dict", "with a star", sum(1 for t in tables if "*, " in t))
print("dict", "naming key", sum(1 for t in tables if "key," in t))
print("dict", "shorter than forty", sum(1 for t in tables if len(t) < 40))
print("dict", "widest table", max(len(t) for t in tables))
print("dict", "sentences of one line", sum(1 for name in DICT
                                           if "\n" not in getattr(dict, name).__doc__))

print("== the default the source cannot write ==")
print("pop names", repr(dict.pop.__text_signature__))
print("unrepresentable", "<unrepresentable>" in dict.pop.__text_signature__)
print("rows naming a default", [name for name in DICT
                                if "=" in (getattr(dict, name).__text_signature__ or "")])
print("the class row names", repr(dict.fromkeys.__text_signature__))

print("== the class row and the value rows ==")
for name in DICT:
    row = dict.__dict__[name]
    print(name, "|", type(row).__name__, "|", row.__qualname__)
print("read from the type", type(dict.fromkeys).__name__)
print("read from a value", type({}.fromkeys).__name__)

print("== one text, two readers ==")
for name in ["get", "pop", "keys", "update", "__getitem__", "__contains__", "__sizeof__", "setdefault"]:
    bound = getattr({"a": 1}, name)
    print(name, "|", bound.__doc__ == getattr(dict, name).__doc__, "|", bound.__name__, "|",
          bound.__qualname__, "|", type(bound.__self__).__name__)

print("== the doc half is a read, not a field ==")
refuses("write dict.get.__doc__", lambda: setattr(dict.get, "__doc__", "x"))
refuses("delete dict.get.__doc__", lambda: delattr(dict.get, "__doc__"))
refuses("write the dictionary row's doc", lambda: setattr(dict.__dict__["pop"], "__doc__", "x"))
refuses("write {'a': 1}.pop.__doc__", lambda: setattr({"a": 1}.pop, "__doc__", "x"))
refuses("write dict.update.__text_signature__", lambda: setattr(dict.update, "__text_signature__", "()"))
refuses("delete dict.update.__text_signature__", lambda: delattr(dict.update, "__text_signature__"))
show("the row and the read are one text", lambda: dict.__dict__["keys"].__doc__ == dict.keys.__doc__)
show("a second read of the doc", lambda: getattr(dict.setdefault, "__doc__") == dict.setdefault.__doc__)
show("a second read of the table", lambda: getattr(dict.pop, "__text_signature__")
     == dict.pop.__text_signature__)

print("== what the text does not change ==")
show("call the dictionary row", lambda: dict.__dict__["copy"]({"a": 1}))
show("call get with no default", lambda: {"a": 1}.get("b"))
show("call get with a default", lambda: {"a": 1}.get("b", 2))
show("call pop with a default", lambda: {"a": 1}.pop("b", 2))
show("call pop with no default", lambda: {"a": 1}.pop("b"))
show("call pop of a real key", lambda: {"a": 1, "b": 2}.pop("a"))
show("call setdefault on a hit", lambda: {"a": 1}.setdefault("a", 9))
show("call setdefault on a miss", lambda: (lambda d: (d.setdefault("b", 9), d)[1])({"a": 1}))
show("call update with keywords", lambda: (lambda d: (d.update(b=2), d)[1])({"a": 1}))
show("call update from pairs", lambda: (lambda d: (d.update([("b", 2)]), d)[1])({"a": 1}))
show("call update from a mapping", lambda: (lambda d: (d.update({"b": 2}), d)[1])({"a": 1}))
show("call update from nothing", lambda: (lambda d: (d.update(), d)[1])({"a": 1}))
show("call fromkeys", lambda: dict.fromkeys("ab"))
show("call fromkeys with a value", lambda: dict.fromkeys([1, 2], 0))
show("call fromkeys sharing one value", lambda: (lambda d: d[1] is d[2])(dict.fromkeys([1, 2], [])))
show("call keys", lambda: sorted({"a": 1}.keys()))
show("call items", lambda: sorted({"a": 1}.items()))
show("call values", lambda: list({"a": 1}.values()))
show("call __contains__", lambda: {"a": 1}.__contains__("a"))
show("call __getitem__", lambda: {"a": 1}.__getitem__("a"))
show("call __getitem__ missing", lambda: {"a": 1}.__getitem__("b"))
show("call __reversed__", lambda: next({"a": 1, "b": 2}.__reversed__()))
show("call popitem", lambda: {"a": 1}.popitem())
show("call popitem on empty", lambda: {}.popitem())
show("call clear", lambda: (lambda d: (d.clear(), len(d))[1])({"a": 1}))
show("a keyword finds no place", lambda: {}.get(key=1))
show("one argument too many", lambda: {}.get(1, 2, 3))
show("__sizeof__ asked with an argument", lambda: {}.__sizeof__(2))
show("the type's own sentence", lambda: dict.__doc__.startswith("dict() -> new empty dictionary"))
