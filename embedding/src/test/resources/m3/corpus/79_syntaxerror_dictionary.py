"""The twelve names ``SyntaxError`` files, and the three classes under it that file one.

``SyntaxError.__dict__`` has twelve names and ``dir(SyntaxError)`` counts forty-two: the nine fields, a
``__str__``, an initialiser and the sentence. The order is the one ``type_ready_fill_dict`` writes — the slot
wrappers first, then ``tp_methods``, then ``tp_members``, then ``tp_getset``, then ``__doc__`` last — and here
the only slot wrapper is ``__str__`` because ``SyntaxError`` is the one class of the family whose text is read
from fields rather than from the arguments. Then ``__init__``, and then the nine pointers of
``PySyntaxErrorObject`` in the order the struct declares them: ``msg``, ``filename``, ``lineno``, ``offset``,
``text``, ``end_lineno``, ``end_offset``, ``print_file_and_line``, ``_metadata``.

There is no ``__new__`` row, and that is the difference between this class and the three Unicode ones beside it
in this slice. ``type_ready_set_new`` installs ``__new__`` before the dictionary is filled, and it installs
``BaseException_new`` only where the static table names it; ``SyntaxError`` names a ``tp_init`` of its own
instead, so nothing is filed beside these twelve names and ``SyntaxError.__new__`` is the base's, reached
through the method resolution order. Asking the dictionary for it is a ``KeyError``, and the inherited answer
is the row ``BaseException`` files.

``IndentationError``, ``TabError`` and ``_IncompleteInputError`` each file ``__doc__`` alone and keep everything
else from above: forty-two names in ``dir`` and one in the dictionary, the same nine fields, the same
``__str__`` and the same initialiser — not a copy of them but the identical object, which is what makes
``IndentationError.__str__ is SyntaxError.__str__`` true. All three say where the text was wrong rather than
that it is, and none of them has anything of its own to say about a value.

What this case does not print is the eleven arguments ``PySyntaxErrorObject`` gives the interpreter to keep,
the ``filename`` a real parse error leaves on the value, the ``text`` of the offending line as the traceback
printer lays an anchor under it, and the address in the ``repr`` of a row.
"""


OWNED = SyntaxError.__dict__
INDENTED = IndentationError.__dict__
TABBED = TabError.__dict__
INCOMPLETE = _IncompleteInputError.__dict__


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def line(owned, name):
    value = owned[name]
    return "%-24s %-24s %-34s set=%-5s get=%-5s doc=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__set__"),
        hasattr(value, "__get__"),
        repr(getattr(value, "__doc__", "-")),
    )


print(len(OWNED), len(dir(SyntaxError)), list(OWNED))
for row in list(OWNED):
    print(line(OWNED, row))

for name, owned, kind in (
    ("IndentationError", INDENTED, IndentationError),
    ("TabError", TABBED, TabError),
    ("_IncompleteInputError", INCOMPLETE, _IncompleteInputError),
):
    print(name, len(owned), len(dir(kind)), list(owned), repr(kind.__doc__))

show("no new row", lambda: "__new__" in OWNED)
show("new is the base's", lambda: SyntaxError.__new__ is BaseException.__dict__["__new__"])
show("new asked of the dict", lambda: OWNED["__new__"])
show("str row is not object's", lambda: OWNED["__str__"] is object.__dict__["__str__"])
show("init row is not object's", lambda: OWNED["__init__"] is object.__dict__["__init__"])
show("no reduce row", lambda: "__reduce__" in OWNED)
show("no getstate row", lambda: "__getstate__" in OWNED)
show("doc", lambda: SyntaxError.__doc__)
show("indentation doc", lambda: IndentationError.__doc__)
show("tab doc", lambda: TabError.__doc__)
show("incomplete doc", lambda: _IncompleteInputError.__doc__)
show("unicode base doc", lambda: UnicodeError.__doc__)

show("str row shared below", lambda: IndentationError.__str__ is OWNED["__str__"])
show("init row shared below", lambda: IndentationError.__init__ is OWNED["__init__"])
show("str row shared twice below", lambda: TabError.__str__ is OWNED["__str__"])
show("field row shared below", lambda: IndentationError.msg is OWNED["msg"])
show("field row of the leaf named", lambda: IndentationError.msg.__qualname__)
show("incomplete has no rows of its own", lambda: sorted(INCOMPLETE))

show("str of a pair", lambda: str(SyntaxError("m", ("/a/b/f.py", 3, 5, "text"))))
show("str of an indentation", lambda: str(IndentationError("m", ("/p/q.py", 2, 3, "  x"))))
show("str of a tab", lambda: str(TabError("m", ("/p/q.py", 2, 3, "\tx"))))
show("str of an incomplete input", lambda: str(_IncompleteInputError("m", ("/p/q.py", 2, 3, "x"))))
show("reduce of an indentation", lambda: IndentationError("m").__reduce__())
show("reduce of a pair", lambda: SyntaxError("m", ("/f", 1, 2, "t")).__reduce__())
show("repr of a pair", lambda: repr(SyntaxError("m", ("/f", 1, 2, "t"))))
