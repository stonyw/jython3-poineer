"""The nine offsets ``SyntaxError`` hands out, and what each of them is worth when nothing filled it.

Every one of the nine is a ``_Py_T_OBJECT`` offset in ``PyMemberDef``, which is the same kind of row
``ImportError`` files its four fields with and it behaves the same way: the value is a pointer, a pointer that
was never written reads back as ``None``, writing one lands in the struct and never in ``e.__dict__``, and
deleting one puts ``NULL`` back so the next read is ``None`` again. What the nine do differently is the number
of them and the display — the fields are the reason ``SyntaxError`` needs a ``__str__`` of its own.

The initialiser is the other half, and it is the family's only one that reads a second argument as a sequence.
``SyntaxError('m', ('/a/b/f.py', 3, 5, 'text'))`` fills four of the nine from the four items, and the table it
parses the inner sequence with is ``OOOO|OOO``, so the count is what decides: three items is refused as
``function takes at least 4 arguments (3 given)``, five as ``end_offset must be provided when end_lineno is
provided``, eight as ``function takes at most 7 arguments (8 given)``. Seven is legal and the seventh is
``_metadata``. Anything that is not a sequence is refused where the sequence is read — ``SyntaxError('m', 42)``
is ``'int' object is not iterable`` — and a string is a sequence of one-character strings, so
``SyntaxError('m', 'abcd')`` keeps ``a``, ``b``, ``c`` and ``d`` as its four fields without anyone objecting.
Only the first positional becomes ``msg``, and it becomes it whatever it is; the arguments themselves are kept
aside from that, so ``e.args`` is still the pair.

Four of the nine take part in the text and five never do: ``msg`` is the sentence, ``filename`` the name in
parentheses and ``lineno`` the line under it, and ``offset``, ``text``, ``end_lineno``, ``end_offset``,
``print_file_and_line`` and ``_metadata`` are for whoever prints a traceback. That is why writing only
``lineno`` is worth ``m (line 7)`` and writing only ``filename`` is worth ``m (f.txt)``: the name is cut at its
last separator by ``my_basename`` and the line is read as a C long, so a ``lineno`` too wide for one prints as
``-1`` rather than as the number a person wrote. ``msg`` is ``%S``'d, which is the one difference from a
message that is ``None``: the text says ``None`` and the parentheses still come.

What this case does not print is the ``offset`` a real parse error leaves behind to point at the character that
broke, the ``_metadata`` only the compiler itself writes, and the address in the ``repr`` of the descriptor.
"""


FIELDS = (
    "msg",
    "filename",
    "lineno",
    "offset",
    "text",
    "end_lineno",
    "end_offset",
    "print_file_and_line",
    "_metadata",
)

OWNED = SyntaxError.__dict__


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def read(e):
    return tuple(getattr(e, name) for name in FIELDS)


def written(name, value):
    return lambda: (lambda e: (setattr(e, name, value), getattr(e, name))[1])(SyntaxError())


def of_pair(*items):
    return lambda: read(SyntaxError("m", items))


show("nine rows", lambda: [n for n in OWNED if OWNED[n].__class__.__name__ == "member_descriptor"])
show("docs", lambda: [OWNED[n].__doc__ for n in FIELDS])
show("qualnames", lambda: [OWNED[n].__qualname__ for n in FIELDS])
show("kinds", lambda: sorted({type(OWNED[n]).__name__ for n in FIELDS}))
show("settable", lambda: [hasattr(OWNED[n], "__set__") for n in FIELDS])
show("nothing filled", lambda: read(SyntaxError()))
show("one argument", lambda: (lambda e: (e.msg, e.filename, e.lineno))(SyntaxError("m")))
show("four read", lambda: of_pair("/a/b/f.py", 3, 5, "text")())
show("six read", lambda: of_pair("/f", 1, 2, "t", 3, 4)())
show("seven read", lambda: of_pair("/f", 1, 2, "t", None, None, "meta")())
show("args stay a pair", lambda: SyntaxError("m", ("/f", 1, 2, "t")).args)
show("msg is the first of the pair", lambda: of_pair("/f", 1, 2, "t")()[0])

for name in FIELDS:
    show("write " + name, written(name, 5))
    show("read after delete " + name, (lambda n: lambda: (lambda e: (
        setattr(e, n, 1), delattr(e, n), getattr(e, n))[2])(SyntaxError()))(name))

show("dict stays empty", lambda: (lambda e: (setattr(e, "lineno", 4), e.__dict__)[1])(SyntaxError()))
show("dict after a delete", lambda: (lambda e: (delattr(e, "msg"), e.__dict__)[1])(SyntaxError("m")))
show("delete twice", lambda: (lambda e: (delattr(e, "text"), delattr(e, "text"), e.text)[2])(SyntaxError("m", ("/f", 1, 2, "t"))))
show("write a name no row has", lambda: (lambda e: (setattr(e, "offetto", 1), e.offetto)[1])(SyntaxError()))
show("field on the class", lambda: SyntaxError.lineno)
show("class write", lambda: setattr(SyntaxError, "lineno", 1))
show("class delete", lambda: delattr(SyntaxError, "lineno"))
show("row of an int", lambda: OWNED["lineno"].__get__(3))
show("row of a str", lambda: OWNED["msg"].__get__("s"))
show("row of another class", lambda: OWNED["msg"].__get__(ValueError("a")))
show("row read with an owner", lambda: OWNED["msg"].__get__(SyntaxError("m"), SyntaxError))
show("row set", lambda: OWNED["msg"].__set__(SyntaxError(), 7))
show("row read of a subclass", lambda: (lambda k: k("m", ("/f", 1, 2, "t")).filename)(type("Syn", (SyntaxError,), {})))

show("re-init fills again", lambda: (lambda e: (e.__init__("m2", ("/g", 9, 9, "g2")), read(e)))(SyntaxError("m", ("/f", 1, 2, "t"))))
show("re-init leaves the rest", lambda: (lambda e: (e.__init__("m2"), e.msg))(SyntaxError("m", ("/f", 1, 2, "t"))))
show("init with no arguments", lambda: (lambda e: (e.__init__(), read(e)))(SyntaxError("m", ("/f", 1, 2, "t"))))
show("init takes keywords", lambda: SyntaxError().__init__(msg="m"))
show("init of an int", lambda: OWNED["__init__"](3))
show("init returns none", lambda: SyntaxError().__init__("m"))

show("str of nothing", lambda: str(SyntaxError()))
show("str of one", lambda: str(SyntaxError("m")))
show("str of a pair", lambda: str(SyntaxError("m", ("/a/b/f.py", 3, 5, "text"))))
show("str of three args", lambda: str(SyntaxError("m", ("/f", 1, 2, "t"), "x")))
show("repr of a pair", lambda: repr(SyntaxError("m", ("/f", 1, 2, "t"))))
