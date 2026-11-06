"""The ten rows `code` copies out of its own slots, and the two method rows beside them.

`code`'s book has thirty-seven rows. Twenty of them are storage — the fourteen `PyMemberDef`s and the six
getsets — and those are `123_code_storage_half.py`. This case is the ten that answer from a slot the type
already fills: the eight `wrapper_descriptor`s `add_operators` copies out of `tp_repr`, `tp_hash` and the
one `tp_richcompare` that becomes six names, and the two `method_descriptor`s `code_methods` files with no
sentence of their own beyond `__sizeof__`'s silence and `_varname_from_oparg`'s warning.

Two shapes, and they bind differently. A wrapper row read off a value is a `method-wrapper`, which is the
slot itself wearing the value; a method row read off a value is a `builtin_function_or_method`, which is the
table row with `m_self` filled in. Both are made afresh on every read, so `CODE.__eq__ is CODE.__eq__` is
false while the two answer the same. And both ask `descr_check` before they do anything else, so a row run
against an `int` complains about the receiver before it complains about the argument count.

`code_richcompare` answers only the two equalities, and only between two code objects: `CODE == 3` declines
and the operator falls back on identity, while the four orderings decline between code objects too, which is
what makes `CODE < CODE` name both types. Read as a method the same slot hands back the value `NotImplemented`
rather than a fallback — the decline is the answer the row gives.

`__sizeof__` counts a C struct this runtime has no layout for, so its answer is the reference's arithmetic
read off this one: the header the struct has before the instructions begin, two octets for each code unit,
and the whole rounded up to a word. `dir(code)` and `vars(code)` are not counted: the book filled out to its
thirty-seven rows in `127_code_new.py`, and that case counts them. Nothing here prints a
`hash()` value either — `code_hash` scrambles in a string hash, which the interpreter randomizes per run.

The one row that cannot be answered from `co_varnames` is `_varname_from_oparg`: it reads the localsplus
array, which continues past the locals into the cells a body keeps and the names it only captures. So a
closure's opargs outlive its own `co_varnames`, an inner body answers for names it never defines, and each
runs out with `IndexError` where the array does. `co_varnames` by contrast is cut back to the locals, which
is why the two counts are asked side by side.
"""


def told(action):
    try:
        return "ok " + repr(action())
    except BaseException as exc:
        return type(exc).__name__ + ": " + str(exc)


def row_of(owner, name):
    return vars(owner)[name]


def fn(a, b=1, *args, c, **kw):
    return a


def wide(a, b, c, d, e):
    total = 0
    for each in range(10):
        total += each
    while total > 1:
        total -= 1
    return total


def outer(x):
    y = x + 1

    def inner():
        return x + y

    return inner


class Indexes:
    def __index__(self):
        return 1


CODE = fn.__code__
KIND = type(CODE)
WIDE = wide.__code__

WRAPPERS = ["__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__"]
METHODS = ["__sizeof__", "_varname_from_oparg"]

print("== the eight wrapper rows, and the two method rows beside them")
for name in WRAPPERS + METHODS:
    row = row_of(KIND, name)
    print("  %-21s %-19s %-26s %s" % (name, type(row).__name__, row.__qualname__, row.__name__))
    print("  %-21s objclass %s" % ("", row.__objclass__))
    print("  %-21s doc %r" % ("", row.__doc__))
    print("  %-21s sig %r" % ("", row.__text_signature__))

print()
print("== the order the ten sit in, among the rows already filed")
FILED = WRAPPERS + METHODS + ["co_lines", "co_positions", "co_argcount", "co_code", "__doc__"]
print("  %s" % [name for name in vars(KIND) if name in FILED])

print()
print("== every one of the ten has a reading half and no writing half")
for name in WRAPPERS + METHODS:
    row = row_of(KIND, name)
    print("  %-21s get %s set %s delete %s"
          % (name, hasattr(row, "__get__"), hasattr(row, "__set__"), hasattr(row, "__delete__")))
print("  %-21s %s" % ("__set__ of eq row", told(lambda: row_of(KIND, "__eq__").__set__(CODE, 1))))
print("  %-21s %s" % ("__set__ of sizeof", told(lambda: row_of(KIND, "__sizeof__").__set__(CODE, 1))))

print()
print("== the receiver is asked first")
for name in WRAPPERS + METHODS:
    row = row_of(KIND, name)
    print("  %-21s %s" % (name, told(lambda r=row: r.__get__(7))))
print("  %-21s %s" % ("__eq__ of None and kind", told(
    lambda: row_of(KIND, "__eq__").__get__(None, KIND) is row_of(KIND, "__eq__"))))

print()
print("== what a value hands out")
for name in WRAPPERS + METHODS:
    print("  %-21s %s" % (name, told(lambda n=name: type(getattr(CODE, n)).__name__)))
print("  %-21s %s" % ("two reads same object", told(lambda: CODE.__eq__ is CODE.__eq__)))
print("  %-21s %s" % ("two reads same answer", told(lambda: CODE.__eq__(CODE) == CODE.__eq__(CODE))))
print("  %-21s %s" % ("sizeof self", told(lambda: CODE.__sizeof__.__self__ is CODE)))
print("  %-21s %s" % ("sizeof qualname", told(lambda: CODE.__sizeof__.__qualname__)))
print("  %-21s %s" % ("eq wrapper repr", told(lambda: repr(CODE.__eq__).split(" ")[0:3])))
print("  %-21s %s" % ("row read off class", told(lambda: row_of(KIND, "__lt__") is KIND.__lt__)))

print()
print("== the two equalities, and the four orderings")
print("  %-21s %s" % ("CODE == CODE", told(lambda: CODE == CODE)))
print("  %-21s %s" % ("CODE == 3", told(lambda: CODE == 3)))
print("  %-21s %s" % ("3 == CODE", told(lambda: 3 == CODE)))
print("  %-21s %s" % ("CODE != 3", told(lambda: CODE != 3)))
print("  %-21s %s" % ("CODE != CODE", told(lambda: CODE != CODE)))
print("  %-21s %s" % ("eq of 3", told(lambda: CODE.__eq__(3))))
print("  %-21s %s" % ("ne of 3", told(lambda: CODE.__ne__(3))))
print("  %-21s %s" % ("lt of 3", told(lambda: CODE.__lt__(3))))
print("  %-21s %s" % ("eq of CODE", told(lambda: CODE.__eq__(CODE))))
print("  %-21s %s" % ("eq is NotImplemented", told(lambda: CODE.__eq__(3) is NotImplemented)))
print("  %-21s %s" % ("CODE < CODE", told(lambda: CODE < CODE)))
print("  %-21s %s" % ("CODE <= CODE", told(lambda: CODE <= CODE)))
print("  %-21s %s" % ("CODE > CODE", told(lambda: CODE > CODE)))
print("  %-21s %s" % ("CODE >= CODE", told(lambda: CODE >= CODE)))

print()
print("== the six names are one slot, and the row can be run without a value")
print("  %-21s %s" % ("row of eq on pair", told(lambda: row_of(KIND, "__eq__")(CODE, CODE))))
print("  %-21s %s" % ("row of eq on one", told(lambda: row_of(KIND, "__eq__")(CODE))))
print("  %-21s %s" % ("row of lt on pair", told(lambda: row_of(KIND, "__lt__")(CODE, CODE))))
print("  %-21s %s" % ("row of ne on pair", told(lambda: row_of(KIND, "__ne__")(CODE, CODE))))
print("  %-21s %s" % ("row of repr on none", told(lambda: row_of(KIND, "__repr__").__get__(None, KIND))))
print("  %-21s %s" % ("repr row of CODE", told(lambda: row_of(KIND, "__repr__").__get__(CODE)() == repr(CODE))))
print("  %-21s %s" % ("hash row of CODE", told(lambda: type(row_of(KIND, "__hash__").__get__(CODE)()))))
print("  %-21s %s" % ("repr arity", told(lambda: CODE.__repr__(3))))
print("  %-21s %s" % ("hash arity", told(lambda: CODE.__hash__(3))))

print()
print("== equality is about the body, so the same body twice is one answer")
print("  %-21s %s" % ("CODE == CODE again", told(lambda: CODE == CODE)))
print("  %-21s %s" % ("CODE == WIDE", told(lambda: CODE == WIDE)))
print("  %-21s %s" % ("CODE != WIDE", told(lambda: CODE != WIDE)))
print("  %-21s %s" % ("set of one", told(lambda: len({CODE, CODE}))))
print("  %-21s %s" % ("dict of one", told(lambda: {CODE: 1}[CODE])))
print("  %-21s %s" % ("hash stable", told(lambda: CODE.__hash__() == hash(CODE))))

print()
print("== __sizeof__: the arithmetic of the table")
for label, code in [("fn", CODE), ("wide", WIDE), ("lambda", (lambda q: q).__code__)]:
    print("  %-21s %s" % (label + " units", len(code.co_code) // 2))
    print("  %-21s %s" % (label + " sizeof", code.__sizeof__()))
print("  %-21s %s" % ("sizeof is int", told(lambda: type(CODE.__sizeof__()).__name__)))
print("  %-21s %s" % ("sizeof twice", told(lambda: CODE.__sizeof__() == CODE.__sizeof__())))
print("  %-21s %s" % ("sizeof arity", told(lambda: CODE.__sizeof__(3))))
print("  %-21s %s" % ("sizeof keyword", told(lambda: CODE.__sizeof__(x=1))))
print("  %-21s %s" % ("wide minus fn", told(lambda: WIDE.__sizeof__() - CODE.__sizeof__())))

print()
print("== _varname_from_oparg walks the slots in the order the frame keeps them")
print("  %-21s %s" % ("co_varnames", told(lambda: CODE.co_varnames)))
print("  %-21s %s" % ("co_nlocals", told(lambda: CODE.co_nlocals)))
for oparg in range(0, 7):
    print("  %-21s %s" % ("oparg " + str(oparg), told(lambda i=oparg: CODE._varname_from_oparg(i))))
print("  %-21s %s" % ("negative", told(lambda: CODE._varname_from_oparg(-1))))
print("  %-21s %s" % ("a string", told(lambda: CODE._varname_from_oparg("0"))))
print("  %-21s %s" % ("a float", told(lambda: CODE._varname_from_oparg(1.0))))
print("  %-21s %s" % ("a bool", told(lambda: CODE._varname_from_oparg(True))))
print("  %-21s %s" % ("an index", told(lambda: CODE._varname_from_oparg(Indexes()))))
print("  %-21s %s" % ("too wide", told(lambda: CODE._varname_from_oparg(2 ** 70))))
print("  %-21s %s" % ("named", told(lambda: CODE._varname_from_oparg(oparg=2))))
print("  %-21s %s" % ("nothing", told(lambda: CODE._varname_from_oparg())))
print("  %-21s %s" % ("two", told(lambda: CODE._varname_from_oparg(0, 1))))
print("  %-21s %s" % ("both ways", told(lambda: CODE._varname_from_oparg(0, oparg=1))))
print("  %-21s %s" % ("unknown name", told(lambda: CODE._varname_from_oparg(index=0))))
print("  %-21s %s" % ("module code", told(lambda: WIDE._varname_from_oparg(5))))

print()
print("== a closure answers for the cells too, which co_varnames does not hold")
OUTER = outer.__code__
INNER = OUTER.co_consts[1]
print("  %-21s %s" % ("outer varnames", told(lambda: OUTER.co_varnames)))
print("  %-21s %s" % ("outer cellvars", told(lambda: OUTER.co_cellvars)))
print("  %-21s %s" % ("outer nlocals", told(lambda: OUTER.co_nlocals)))
for oparg in range(0, 4):
    print("  %-21s %s" % ("outer oparg " + str(oparg), told(lambda i=oparg: OUTER._varname_from_oparg(i))))
print("  %-21s %s" % ("inner kind", told(lambda: type(INNER).__name__)))
print("  %-21s %s" % ("inner varnames", told(lambda: INNER.co_varnames)))
print("  %-21s %s" % ("inner freevars", told(lambda: INNER.co_freevars)))
print("  %-21s %s" % ("inner nlocals", told(lambda: INNER.co_nlocals)))
for oparg in range(0, 3):
    print("  %-21s %s" % ("inner oparg " + str(oparg), told(lambda i=oparg: INNER._varname_from_oparg(i))))

print()
print("== none of the ten can be written, and each says it in its own family's words")
for name in WRAPPERS + METHODS:
    print("  %-21s %s" % ("write " + name, told(lambda n=name: setattr(CODE, n, 1))))
print("  %-21s %s" % ("delete __eq__", told(lambda: delattr(CODE, "__eq__"))))
print("  %-21s %s" % ("delete sizeof", told(lambda: delattr(CODE, "__sizeof__"))))
print("  %-21s %s" % ("delete varname", told(lambda: delattr(CODE, "_varname_from_oparg"))))
print("  %-21s %s" % ("class write eq", told(lambda: setattr(KIND, "__eq__", 1))))
print("  %-21s %s" % ("class write sizeof", told(lambda: setattr(KIND, "__sizeof__", 1))))
print("  %-21s %s" % ("class delete varname", told(lambda: delattr(KIND, "_varname_from_oparg"))))

print()
print("done")
