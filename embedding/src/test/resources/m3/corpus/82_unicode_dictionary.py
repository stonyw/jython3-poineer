"""The two rows ``UnicodeError`` files and the nine each of its three leaves files beside them.

``UnicodeError.__dict__`` has two names and ``dir(UnicodeError)`` counts thirty-three: ``__new__`` and
``__doc__``. It has no fields of its own to file — the five it hands out belong to the leaves — and no
``__str__``, so a ``UnicodeError`` says whatever ``BaseException`` says and ``str(UnicodeError("a"))`` is the
message alone. Then come ``UnicodeEncodeError``, ``UnicodeDecodeError`` and ``UnicodeTranslateError``, each with
nine names and thirty-eight in ``dir``.

Each of the three files a ``__new__``, and that is the difference from ``SyntaxError``, which holds the same
position in the tree and files none. The static table of these three names ``BaseException_new`` as their
``tp_new``, and ``type_ready_set_new`` copies a class's own ``tp_new`` into its dictionary when it is not the
inherited one, so the row lands there before the rest of the table is filled — first of the nine, and as a
``builtin_function_or_method`` rather than any kind of descriptor. It is the leaf's own: ``__qualname__`` is
``UnicodeEncodeError.__new__`` and ``__self__`` is the class, and a class written under one of the leaves
reaches the same row without renaming it, so ``__qualname__`` still names the leaf and ``__self__`` is still
the leaf rather than the subclass.

Then a ``__str__`` and a ``__init__`` as two ``wrapper_descriptor``s, and then the five fields of the
``PyUnicodeErrorObject`` struct: ``encoding``, ``object``, ``start``, ``end``, ``reason``. All three leaves share
that one ``PyMemberDef`` table, which is why ``UnicodeTranslateError`` files an ``encoding`` row it has no use
for — the translate sentence names no codec, so a value of that class reads back the ``None`` the field was
never given while the same field on the other two holds whatever the initialiser put there.

Two of the five are a different kind of row from the other three, and it shows the moment one is written to.
``encoding``, ``object`` and ``reason`` are ``_Py_T_OBJECT`` pointers, so they read ``None`` until filled, take
any value written to them, and go back to ``None`` when deleted. ``start`` and ``end`` are ``Py_ssize_t``
offsets: they always read a number, refuse a delete with ``can't delete numeric/char attribute``, refuse
anything that is not an integer with ``an integer is required``, and take a ``bool`` because that is an integer.
A value made by ``__new__`` alone has ``0`` in both — the struct is zeroed — where the same value made by the
initialiser has the range the call gave it.

What this case does not print is the ``object`` a real codec failure leaves behind, which is the whole text or
the whole byte string that could not be converted, the ``__module__`` of these four classes, and the address in
the ``repr`` of a ``__new__`` row.
"""


import builtins

LEAVES = ("UnicodeEncodeError", "UnicodeDecodeError", "UnicodeTranslateError")
MEMBERS = ("encoding", "object", "start", "end", "reason")


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def line(owned, name):
    value = owned[name]
    return "%-20s %-28s %-40s set=%-5s get=%-5s doc=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__set__"),
        hasattr(value, "__get__"),
        repr(getattr(value, "__doc__", "-")),
    )


print("UnicodeError", len(UnicodeError.__dict__), len(dir(UnicodeError)))
for row in list(UnicodeError.__dict__):
    print(line(UnicodeError.__dict__, row))

for name in LEAVES:
    kind = getattr(builtins, name)
    print("==", name, len(kind.__dict__), len(dir(kind)))
    for row in list(kind.__dict__):
        print(line(kind.__dict__, row))

show("base doc", lambda: UnicodeError.__doc__)
show("encode doc", lambda: UnicodeEncodeError.__doc__)
show("decode doc", lambda: UnicodeDecodeError.__doc__)
show("translate doc", lambda: UnicodeTranslateError.__doc__)
show("base extends value error", lambda: UnicodeError.__mro__[1] is ValueError)
show("leaf extends base", lambda: UnicodeEncodeError.__mro__[1] is UnicodeError)
show("base files no members", lambda: [n for n in MEMBERS if n in UnicodeError.__dict__])
show("base files no str row", lambda: "__str__" in UnicodeError.__dict__)
show("base str is the family's", lambda: UnicodeError.__str__ is BaseException.__dict__["__str__"])
show("leaf str is not the base's", lambda: UnicodeEncodeError.__str__ is UnicodeError.__str__)
show("leaves do not share a str", lambda: UnicodeEncodeError.__str__ is UnicodeDecodeError.__str__)
show("leaves do not share an init", lambda: UnicodeEncodeError.__init__ is UnicodeDecodeError.__init__)
show("translate files an encoding row", lambda: "encoding" in UnicodeTranslateError.__dict__)
show("translate member docs", lambda: [UnicodeTranslateError.__dict__[n].__doc__ for n in MEMBERS])
show("decode member qualnames", lambda: [UnicodeDecodeError.__dict__[n].__qualname__ for n in MEMBERS])
show("kinds", lambda: sorted({type(UnicodeDecodeError.__dict__[n]).__name__
                              for n in MEMBERS + ("__new__", "__str__", "__init__", "__doc__")}))
show("new is not the base's", lambda: UnicodeEncodeError.__new__ is BaseException.__dict__["__new__"])
show("new rows differ per leaf", lambda: UnicodeEncodeError.__new__ is UnicodeDecodeError.__new__)
show("base of one argument", lambda: str(UnicodeError("a")))
show("base of five", lambda: str(UnicodeError("utf-8", b"a", 0, 1, "r")))
show("base of five args", lambda: UnicodeError("utf-8", b"a", 0, 1, "r").args)
show("base has no fields", lambda: [n for n in MEMBERS if hasattr(UnicodeError("x"), n)])
show("base write refused", lambda: (lambda e: (setattr(e, "start", 1), e.start))(UnicodeError("x")))
show("new made without init", lambda: (lambda e: (e.start, e.end, e.encoding, e.object, e.reason))(UnicodeEncodeError.__new__(UnicodeEncodeError)))
show("new alone prints", lambda: str(UnicodeEncodeError.__new__(UnicodeEncodeError)))
show("new alone reprs", lambda: repr(UnicodeEncodeError.__new__(UnicodeEncodeError)))
show("new with no receiver", lambda: UnicodeEncodeError.__new__(3))
show("new then init", lambda: (lambda e: (e.__init__("utf-8", "a", 0, 1, "r"), (e.start, str(e)))[1])(UnicodeEncodeError.__new__(UnicodeEncodeError)))
show("read five", lambda: (lambda e: (e.encoding, e.object, e.start, e.end, e.reason))(UnicodeEncodeError("utf-8", "é", 0, 1, "w")))
show("read translate", lambda: (lambda e: (e.encoding, e.object, e.start, e.end, e.reason))(UnicodeTranslateError("é", 0, 1, "w")))

for name in MEMBERS:
    show("write " + name, (lambda n: lambda: (lambda e: (setattr(e, n, 5), getattr(e, n))[1])(UnicodeDecodeError("utf-8", b"a", 0, 1, "r")))(name))
    show("delete " + name, (lambda n: lambda: (lambda e: (delattr(e, n), getattr(e, n))[1])(UnicodeDecodeError("utf-8", b"a", 0, 1, "r")))(name))
    show("row of an int " + name, (lambda n: lambda: UnicodeDecodeError.__dict__[n].__get__(3))(name))

show("dict stays empty", lambda: (lambda e: (setattr(e, "encoding", "x"), e.__dict__)[1])(UnicodeDecodeError("utf-8", b"a", 0, 1, "r")))
show("start a bool", lambda: (lambda e: (setattr(e, "start", True), e.start)[1])(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r")))
show("start a float", lambda: setattr(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r"), "start", 1.5))
show("start a str", lambda: setattr(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r"), "start", "x"))
show("start two to the eighty", lambda: setattr(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r"), "start", 2**80))
show("start minus two to the eighty", lambda: setattr(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r"), "start", -(2**80)))
show("start two to the sixty three", lambda: (lambda e: (setattr(e, "start", 2**63 - 1), e.start)[1])(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r")))


class Countable:
    def __index__(self):
        return 3


class NotCountable:
    pass


show("start an index", lambda: (lambda e: (setattr(e, "start", Countable()), e.start)[1])(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r")))
show("start an object", lambda: setattr(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r"), "start", NotCountable()))
show("end read after two writes", lambda: (lambda e: (setattr(e, "end", 0), setattr(e, "end", -5), e.end)[2])(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r")))
show("class write", lambda: setattr(UnicodeDecodeError, "start", 1))
show("subclass reads members", lambda: (lambda k: k("utf-8", b"a", 0, 1, "r").start)(type("SD", (UnicodeDecodeError,), {})))
show("subclass new qualname", lambda: type("SU", (UnicodeEncodeError,), {}).__new__.__qualname__)
show("subclass new owner", lambda: type("SU", (UnicodeEncodeError,), {}).__new__.__self__ is UnicodeEncodeError)
