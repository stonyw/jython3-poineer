DESCRIPTOR = type(bytes.hex)
WRAPPER = type(object.__init__)
CLASSMETHOD = type(object.__dict__["__subclasshook__"])
STATIC = type(bytes.__dict__["maketrans"])
BUILTIN = type(len)
KINDS = (DESCRIPTOR, WRAPPER, CLASSMETHOD, STATIC, BUILTIN)
SIX = ("($self, /)", "($type, /)", "($type, object, /)", "($self, object, /)", "()", "(object, /)")

# The twenty-one rows whose `__text_signature__` is not cut out of a text at all. CPython reads that name two
# ways (`_PyType_GetTextSignatureFromInternalDoc`, Objects/typeobject.c:909): it tries `find_signature` on the
# `ml_doc` first, and when the text carries no table it calls `signature_from_flags` (the same file, line 888),
# which answers a six-case switch over the row's `METH_NOARGS` (0x0004) and `METH_O` (0x0008) flags crossed
# with `METH_CLASS` (0x0010) and `METH_STATIC` (0x0020). Twenty of these twenty-one rows are registered by hand
# beside a sentence that names no arguments — or beside no sentence at all, as the two `__getnewargs__`s are
# — namely `object`'s two hooks, the five views of `mappingproxy_methods`,
# the seven generic subscripts, `int`'s four whole-number methods and the two `__getnewargs__`s of `str` and
# `bytes` — and the twenty-first, `dict.update`, is the row whose flags answer NULL and so reads None in both.
# The mappingproxy subscript is the one row of the twenty whose *call* this slice corrects as well as its
# table: `Objects/descrobject.c:1180` files `Py_GenericAlias` beside the same METH_O|METH_CLASS, so the
# seventh generic subscript now aliases like its six siblings instead of declining the bracket.
# Nothing here changes a `__doc__`: the flag is the second source of one name only, and the sentence half has
# no second source, which is why every row below answers the same text as it did before the flags arrived.
# A bound method's own read of this name is not modelled yet: CPython binds a `method_descriptor` to a
# `builtin_function_or_method`, which both answers `__text_signature__` from the very pair of rules and names it
# in `dir()`, while this runtime binds a `method` that answers neither. The one bound row below is therefore read
# by the three things the two runtimes do agree on — the name, the head of the repr and the value `__self__`
# holds — and not by the type's name.
ROWS = [("object", "__init_subclass__"), ("object", "__subclasshook__"),
        ("mappingproxy", "__class_getitem__"), ("mappingproxy", "__reversed__"),
        ("mappingproxy", "copy"), ("mappingproxy", "items"), ("mappingproxy", "keys"),
        ("mappingproxy", "values"), ("bytes", "__getnewargs__"), ("str", "__getnewargs__"),
        ("list", "__class_getitem__"), ("tuple", "__class_getitem__"), ("int", "conjugate"),
        ("int", "__trunc__"), ("int", "__floor__"), ("int", "__ceil__"),
        ("dict", "__class_getitem__"), ("dict", "update"), ("set", "__class_getitem__"),
        ("frozenset", "__class_getitem__"), ("BaseExceptionGroup", "__class_getitem__")]
BOOK = {"object": object, "mappingproxy": type(object.__dict__), "bytes": bytes, "str": str,
        "list": list, "tuple": tuple, "int": int, "dict": dict, "set": set, "frozenset": frozenset,
        "BaseExceptionGroup": BaseExceptionGroup}
SUBSCRIPTS = ["mappingproxy", "list", "tuple", "dict", "set", "frozenset", "BaseExceptionGroup"]
VIEWS = ["keys", "values", "items", "copy", "__reversed__"]
WHOLE = ["conjugate", "__trunc__", "__floor__", "__ceil__"]
TYPES = [object, type, type(object.__dict__), type(len),
         bytes, str, list, tuple, int, bool, float, dict, set, frozenset,
         BaseException, Exception, OSError, AttributeError, ImportError, NameError,
         StopIteration, SystemExit, SyntaxError, BaseExceptionGroup, ExceptionGroup]


def sweep(kind, names):
    for name in names:
        book = BOOK[kind]
        row = book.__dict__[name]
        read = getattr(book, name)
        print(
            kind, name, "|", type(row).__name__, "|", repr(row.__text_signature__), "|",
            type(read).__name__, "|", repr(read.__text_signature__), "|",
            repr(read.__doc__)[:30], "|", row.__doc__ == read.__doc__,
        )


def tables(names):
    return [getattr(BOOK[kind], name).__text_signature__ for kind, name in names]


def silent(kind):
    book = vars(kind)
    return [
        name for name in sorted(book)
        if isinstance(book[name], KINDS) and getattr(book[name], "__text_signature__", "gone") is None
    ]


def short_tables(kind):
    # The rows of one dictionary answering one of the six shapes the switch can invent. A script cannot tell
    # which of those tables the flag said and which was cut out of the text: `__doc__` is the prose either
    # way, and a clinic row's own name sits above the `--` rule rather than inside its sentence. So this is a
    # census of the answers, not of the flags — its use is the guarantee that no row besides the twenty-one
    # named above reads a different table now than it did before the flags arrived.
    book = vars(kind)
    return [
        name for name in sorted(book)
        if isinstance(book[name], KINDS)
        and getattr(book[name], "__text_signature__", "gone") in SIX
    ]


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


print("== the twenty-one rows whose table no text carries ==")
print("rows", len(ROWS), "descriptor kinds", len(KINDS))
print("rows read as flags' tables", sum(1 for t in tables(ROWS) if t is not None))
print("rows read as None", sum(1 for t in tables(ROWS) if t is None))
for kind, name in ROWS:
    sweep(kind, [name])

print("== which flag says which table ==")
found = tables(ROWS)
print("naming $self", sum(1 for t in found if t is not None and t.startswith("($self")))
print("naming $type", sum(1 for t in found if t is not None and t.startswith("($type")))
print("closing with /)", sum(1 for t in found if t is not None and t.endswith("/)")))
print("naming object", sum(1 for t in found if t is not None and "object" in t))
print("of one shape", len(set(found)), "distinct")
for text in sorted(set(found), key=lambda t: repr(t)):
    print("shape", repr(text), "rows", sum(1 for t in found if t == text))

print("== the four whole-number sentences of int ==")
sweep("int", WHOLE)
print("one C function, four names", "|", (5).conjugate() == 5, "|", (5).__floor__() == 5)
print("a bool reads the same row", "|", type(bool.conjugate).__name__, "|",
      repr(bool.conjugate.__text_signature__), "|", (True).__ceil__())

print("== the five views and the reverse ==")
sweep("mappingproxy", VIEWS)
window = {"a": 1}.keys()
print("view of a window", "|", type(window).__name__, "|", sorted({1: 2}.copy()))

print("== the seven generic subscripts ==")
for kind in SUBSCRIPTS:
    sweep(kind, ["__class_getitem__"])
print("list[int]", "|", list[int], "|", dict[str, int], "|", tuple[int, str])
print("subscripted", "|", " | ".join(repr(BOOK[k][int]) for k in SUBSCRIPTS))
print("origin and args", "|", type(object.__dict__)[int].__origin__.__name__, "|",
      type(object.__dict__)[int].__args__, "|", BaseExceptionGroup[[int]].__args__)

print("== the two hooks of object ==")
sweep("object", ["__init_subclass__", "__subclasshook__"])


class Plain:
    pass


print("bound to a written class", "|", Plain.__init_subclass__.__text_signature__, "|",
      int.__subclasshook__.__text_signature__)
show("object.__init_subclass__()", lambda: object.__init_subclass__())
show("int.__subclasshook__(object, str)", lambda: int.__subclasshook__(object, str))
refuses("int.__subclasshook__ with two", lambda: int.__subclasshook__(object, str, int))

print("== the two rows pickle reads ==")
sweep("bytes", ["__getnewargs__"])
sweep("str", ["__getnewargs__"])
print("b'ab'.__getnewargs__", "|", b"ab".__getnewargs__(), "|", "ab".__getnewargs__())
print("a value reads the same row", "|", b"ab".__getnewargs__.__name__, "|",
      repr(b"ab".__getnewargs__)[:20], "|", type(b"ab".__getnewargs__.__self__).__name__)

print("== a text beats a flag ==")
# Each of these five rows carries a clinic text with a table of its own, so the text wins and the flag is
# never reached. Three of them — `int.__format__`, `float.__format__`, `bytes.join` — are registered here by
# `addOne`, which writes METH_O, whose invented table would say `($self, object, /)` instead. The other two
# keep no flag at all, where the reference writes METH_FASTCALL|METH_KEYWORDS for `str.count` and
# METH_O|METH_COEXIST for `list.__getitem__` (Objects/listobject.c:3577).
for kind, name, book in [("int", "__format__", int), ("float", "__format__", float),
                         ("bytes", "join", bytes), ("str", "count", str), ("list", "__getitem__", list)]:
    print(kind, name, "|", repr(getattr(book, name).__text_signature__))

print("== the one row that stays silent in both ==")
print("dict.update", "|", repr(dict.update.__text_signature__), "|", repr(dict.update.__doc__)[:34])
print("the sentence opens with D.update", "|", dict.update.__doc__.startswith("D.update("))
print("so find_signature never starts", "|", not dict.update.__doc__.startswith("update"))

print("== what the table does not change ==")
show("(5).bit_count()", lambda: (5).bit_count())
show("(5).conjugate()", lambda: (5).conjugate())
show("int.__trunc__(-7)", lambda: int.__trunc__(-7))
show("(255).__getnewargs__()", lambda: (255).__getnewargs__())
show("(2.5).__getnewargs__()", lambda: (2.5).__getnewargs__())
show("bytes.fromhex('ff')", lambda: bytes.fromhex("ff"))
show("{1: 2}.keys()", lambda: sorted({1: 2}.keys()))
show("frozenset[int]", lambda: frozenset[int])
refuses("int.conjugate(1, 2)", lambda: int.conjugate(1, 2))
refuses("'x'.__getnewargs__('y')", lambda: "x".__getnewargs__("y"))
refuses("int.__floor__()", lambda: int.__floor__())
refuses("{}.update(1, 2)", lambda: {}.update(1, 2))

print("== a flag is not writable ==")
refuses("write int.conjugate.__text_signature__", lambda: setattr(int.conjugate, "__text_signature__", "()"))
refuses("delete it", lambda: delattr(int.conjugate, "__text_signature__"))
refuses("write mappingproxy.keys.__doc__", lambda: setattr(CLASSMETHOD.__doc__, "__doc__", "x"))
refuses("write the dictionary row", lambda: setattr(int.__dict__["conjugate"], "__doc__", "x"))
refuses("write the hook", lambda: setattr(object.__subclasshook__, "__text_signature__", "()"))

print("== the census the flags close ==")
left = 0
for kind in TYPES:
    names = silent(kind)
    left += len(names)
    if names:
        print(kind.__name__, "silent", len(names), "|".join(names))
print("types swept", len(TYPES), "rows silent", left)
print("rows answering one of the six", sum(len(short_tables(kind)) for kind in TYPES))
for kind in TYPES:
    names = short_tables(kind)
    if names:
        print(kind.__name__, "short tables", len(names), "|".join(names))
