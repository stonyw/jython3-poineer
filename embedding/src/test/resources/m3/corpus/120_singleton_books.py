"""The three books the singletons keep: one welded table of nothing, and the rows beside it.

`type(None)`, `type(NotImplemented)` and `type(Ellipsis)` are the three types of `builtins` whose whole work
is to be the type of one value, and each writes a `tp_doc` that opens with a table of an empty argument list:
`NoneType()`, `NotImplementedType()` and `ellipsis()`, each followed by the rule `--` clinic prints and one
sentence. The line is a table to `type_get_text_signature` (`Objects/typeobject.c:1972`) because
`find_signature` matches the type's own name straight followed by a bracket and `skip_signature` reaches the
rule before the first blank line; `type_get_doc` (`:1955`) hands the same string to the cutter that keeps what
lies under the rule, and the dictionary's `__doc__` row is that remainder rather than the weld — so `sig`,
the sentence and the filed row are three reads of one text, and only the first of them is cut twice.

The `ellipsis` book is where the two names of one type part company. `PyEllipsis_Type` carries the `tp_name`
`ellipsis`, which is what its table says and therefore what `find_signature` has to match, while the
constructor's refusal is written beside the C struct and says `EllipsisType takes no arguments`. The other
two are named alike in both places, so only this type shows the split — and it shows it twice over, because
`ellipsis.__new__` is filed under the `tp_name` and refuses under the struct.

The books are the shortest in the language: eleven names, five and four, against `object`'s twenty-four, and
only two of the three stand above `object` in `dir()`. `NoneType` files `__hash__`, the six comparisons and a
`__bool__` that answers `False`; nine of those ten are names `object` files too, so its `dir()` is
twenty-five and the one name gained is the truth row. `NotImplementedType` files five rows, of which only
that same `__bool__` is new, so it reaches twenty-five with six fewer names than its neighbour. `ellipsis`
files no truth row at all and so reads the plain twenty-four of `object`, its `__reduce__` and `__repr__`
being names `object` already keeps. `super`'s nine rows are the fourth book of this shape and come later.

One read of these books does not agree with the reference. `object.__ne__` and `NoneType.__ne__` answer the
negation of the pointer here, where CPython negates the left value's own `__eq__` and declines with it when
that declines: `object.__ne__(None, 1)` is `True` below and `NotImplemented` in the reference. Nothing here
prints that pair, and the four orderings and `__eq__`, which CPython answers out of the pointer too, are
printed as they agree. Nothing prints a `repr()` of `__new__` or of a bound builtin method, whose text names
an address, or `hash(None)` as a number, which CPython derives from one.
"""

NONE_TYPE = type(None)
NOTIMP_TYPE = type(NotImplemented)
ELLIPSIS_TYPE = type(Ellipsis)

BOOKS = [
    ("NoneType", NONE_TYPE, None),
    ("NotImplementedType", NOTIMP_TYPE, NotImplemented),
    ("ellipsis", ELLIPSIS_TYPE, Ellipsis),
]

# The name the refusal is written under, which for the third row is not the name the type is filed under.
CREATORS = [
    ("NoneType", NONE_TYPE),
    ("NotImplementedType", NOTIMP_TYPE),
    ("EllipsisType", ELLIPSIS_TYPE),
]


def rows_of(kind):
    """The names of one book, in the order its dictionary keeps them."""
    return list(vars(kind))


def told(action):
    """What a read answers, or the complaint it makes instead — half of this file is the complaints."""
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


print("== the one text, read by both knives")
for name, kind, _ in BOOKS:
    filed = vars(kind)["__doc__"]
    print("  %-19s sig=%-5r doc len=%-4s filed=%s" % (
        name, kind.__text_signature__, len(kind.__doc__), type(filed).__name__))
    print("      __doc__=%r" % (kind.__doc__,))
    print("      dict row=%r" % (filed,))

print()
print("== the book, and how far it stands above object")
for name, kind, _ in BOOKS:
    print("  %-19s vars=%s" % (name, rows_of(kind)))
    print("      %-19s dir=%-4s gained=%s" % (
        "", len(dir(kind)), sorted(set(dir(kind)) - set(dir(object)))))
    for row in sorted(vars(kind)):
        held = vars(kind)[row]
        if isinstance(held, str):
            print("        %-18s %-20s the sentence itself: %r" % (
                row, type(held).__name__, held))
            continue
        print("        %-18s %-20s sig=%-26r doc=%r" % (
            row, type(held).__name__,
            getattr(held, "__text_signature__", None),
            getattr(held, "__doc__", None)))

print()
print("== the three names a row answers")
for name, kind, _ in BOOKS:
    for row in sorted(vars(kind)):
        held = vars(kind)[row]
        if isinstance(held, str):
            continue
        print("  %-19s %-18s %-20s qualname=%-34r name=%-14r objclass=%s" % (
            name, row, type(held).__name__,
            getattr(held, "__qualname__", None),
            getattr(held, "__name__", None),
            getattr(getattr(held, "__objclass__", None), "__name__", None)))

print()
print("== the creator, and the name it answers")
for struct_name, kind in CREATORS:
    for label, action in [
        ("()", lambda k=kind: k()),
        ("(1)", lambda k=kind: k(1)),
        ("(1, 2)", lambda k=kind: k(1, 2)),
        ("(x=1)", lambda k=kind: k(x=1)),
    ]:
        print("  %-19s %-8s -> %s" % (struct_name, label, told(action)))

print()
print("== the one value each creator hands out")
for name, kind, value in BOOKS:
    print("  %-19s () is value=%s  type(value) is this=%s  name=%r" % (
        name, kind() is value, type(value) is kind, type(value).__name__))
    print("      __new__(kind) is value=%s" % (kind.__new__(kind) is value,))

print()
print("== the creator row, and what the class gate says first")
for name, kind, _ in BOOKS:
    for label, action in [
        ("__new__()", lambda k=kind: k.__new__()),
        ("__new__(kind)", lambda k=kind: k.__new__(k)),
        ("__new__(int)", lambda k=kind: k.__new__(int)),
        ("__new__(kind, 1)", lambda k=kind: k.__new__(k, 1)),
        ("__new__(kind, x=1)", lambda k=kind: k.__new__(k, x=1)),
    ]:
        print("  %-19s %-19s -> %s" % (name, label, told(action)))

print()
print("== the truth of the three values, and of the row beside them")
for name, kind, value in BOOKS:
    print("  %-19s bool(value)=%s" % (name, told(lambda v=value: bool(v))))
    row = vars(kind).get("__bool__")
    if row is None:
        print("      files no __bool__ of its own")
    else:
        print("      row(value)=%s" % (told(lambda r=row, v=value: r(v)),))

print()
print("== the boolean reads of NotImplemented")
for label, action in [
    ("bool(NotImplemented)", lambda: bool(NotImplemented)),
    ("not NotImplemented", lambda: not NotImplemented),
    ("NotImplemented and 1", lambda: NotImplemented and 1),
    ("NotImplemented or 1", lambda: NotImplemented or 1),
    ("if NotImplemented", lambda: "yes" if NotImplemented else "no"),
    ("[1, NotImplemented].count(2)", lambda: [1, NotImplemented].count(2)),
]:
    print("  %-28s -> %s" % (label, told(action)))

print()
print("== the pickle rows, which say nothing about themselves")
for name, kind, value in BOOKS:
    row = vars(kind).get("__reduce__")
    if row is None:
        print("  %-19s files no __reduce__ of its own" % (name,))
        continue
    print("  %-19s doc=%r sig=%r" % (name, row.__doc__, row.__text_signature__))
    print("      value.__reduce__()=%s row(value)=%s" % (
        told(lambda v=value: v.__reduce__()), told(lambda r=row, v=value: r(v))))

print()
print("== the rows asked of a stranger, in both wordings")
CHECKS = [
    ("wrapper", NONE_TYPE.__dict__["__repr__"], 5),
    ("wrapper", NOTIMP_TYPE.__dict__["__bool__"], 5),
    ("wrapper", ELLIPSIS_TYPE.__dict__["__repr__"], NotImplemented),
    ("method", NOTIMP_TYPE.__dict__["__reduce__"], 5),
    ("method", ELLIPSIS_TYPE.__dict__["__reduce__"], None),
]
for shape, row, stranger in CHECKS:
    print("  %-9s %-34s -> %s" % (shape, row.__qualname__, told(lambda r=row, v=stranger: r(v))))

print()
print("== binding the truth row, whose only receiver is nothing")
for name, kind, value in BOOKS:
    row = vars(kind).get("__bool__")
    if row is None:
        continue
    print("  %-19s __get__(value, kind) -> %s" % (
        name, told(lambda r=row, v=value, k=kind: type(r.__get__(v, k)).__name__)))
    print("      __get__(None) -> %s" % (told(lambda r=row: type(r.__get__(None)).__name__),))
    print("      the same row, bound and called -> %s" % (
        told(lambda r=row, v=value: r.__get__(v, type(v))()),))

print()
print("== the comparisons answered out of the pointer")
for label, action in [
    ("NoneType.__eq__(None, None)", lambda: NONE_TYPE.__eq__(None, None)),
    ("NoneType.__eq__(None, 1)", lambda: NONE_TYPE.__eq__(None, 1)),
    ("NoneType.__ne__(None, None)", lambda: NONE_TYPE.__ne__(None, None)),
    ("NoneType.__lt__(None, None)", lambda: NONE_TYPE.__lt__(None, None)),
    ("NoneType.__le__(None, 1)", lambda: NONE_TYPE.__le__(None, 1)),
    ("NoneType.__gt__(None, None)", lambda: NONE_TYPE.__gt__(None, None)),
    ("NoneType.__ge__(None, 1)", lambda: NONE_TYPE.__ge__(None, 1)),
    ("object.__eq__(3, 3)", lambda: object.__eq__(3, 3)),
    ("object.__eq__(3, 4)", lambda: object.__eq__(3, 4)),
    ("object.__eq__(3, 3.0)", lambda: object.__eq__(3, 3.0)),
    ("object.__lt__(3, 4)", lambda: object.__lt__(3, 4)),
    ("object.__ge__(3, 4)", lambda: object.__ge__(3, 4)),
]:
    print("  %-32s -> %s" % (label, told(action)))

print()
print("== what the operator does with a declined comparison")
for label, action in [
    ("None < 1", lambda: None < 1),
    ("Ellipsis > None", lambda: Ellipsis > None),
    ("NotImplemented == 1", lambda: NotImplemented == 1),
    ("NotImplemented != 1", lambda: NotImplemented != 1),
    ("None == None", lambda: None == None),
    ("Ellipsis is Ellipsis", lambda: Ellipsis is Ellipsis),
    ("NoneType.__dict__['__eq__'](None, 1)", lambda: NONE_TYPE.__dict__["__eq__"](None, 1)),
]:
    print("  %-34s -> %s" % (label, told(action)))

print()
print("== the hash row, read as a relation and not as a number")
print("  row(value) == hash(value): %s" % (NONE_TYPE.__hash__(None) == hash(None),))
print("  the creator's value hashes alike: %s" % (hash(NONE_TYPE()) == hash(None),))
print("  type of the row's answer: %s" % (type(NONE_TYPE.__hash__(None)).__name__,))

print()
print("== the text a program reads, and the book it comes from")
for name, kind, value in BOOKS:
    print("  %-19s name=%-20r str(value)=%-16r repr(value)=%-16r" % (
        name, kind.__name__, str(value), repr(value)))
    print("      mro=%s bases=%s" % (
        [k.__name__ for k in kind.__mro__], [k.__name__ for k in kind.__bases__]))
print("done")
