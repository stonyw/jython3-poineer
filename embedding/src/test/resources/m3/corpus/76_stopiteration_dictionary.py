"""The three rows ``StopIteration`` files, and the one field the generator protocol fills.

``StopIteration.__dict__`` is ``__init__``, ``value`` and ``__doc__``, and ``dir(StopIteration)`` counts
thirty-four — the same three names ``NameError`` files, with a field called for what a generator's ``return``
handed back instead of a name. Like its siblings it files no ``__new__``: the creator is ``BaseException``'s,
so ``StopIteration(1, 2)`` keeps both arguments and the field is filled by the initialiser from the first.
``add_operators`` files nothing here either, and there is no ``__getstate__`` and no ``__reduce__``, so a
stopped iterator reduces to the two places ``BaseException`` answers with and the value it carried is not in
the reduction at all — the one thing about this class a person restoring one from a pickle is surprised by.

The field is a ``_Py_T_OBJECT`` offset, and the initialiser is the only one of the family that fills it from a
positional rather than from a name. Three rules come out of that and are visible only against
``SystemExit``, which reads the same arguments: one positional is the value and two are the first of them, so
``StopIteration('a', 'b').value`` is ``'a'`` and not a pair; no positional at all *clears* the field, so
``e.__init__()`` on a value that had a value leaves ``None`` behind where the same call on a ``SystemExit``
keeps its code; and a bare ``StopIteration.__new__(StopIteration)`` reads ``None`` because the creator never
touches the offset.

Keywords are not parsed here. ``BaseException_init`` is handed the call's own keyword dictionary and answers it
with ``_PyArg_NoKeywords``, which reads ``Py_TYPE(self)->tp_name`` after ``__new__`` has already decided what
the value is — so the refusal names the class that was called, and a subclass of ``StopIteration`` is refused
in its own words rather than in ``BaseException``'s. That is the difference between this class and
``NameError``, whose table is read against an empty tuple and so complains about the name instead.

What this case does not print is the ``value`` of the ``StopIteration`` a generator's ``return`` makes, which
slice 10 covers, the address in the ``repr`` of the row, or the ``__basicsize__`` that would say how many
pointers the class keeps beside its arguments.
"""


OWNED = StopIteration.__dict__


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def line(name):
    value = OWNED[name]
    return "%-22s %-24s %-28s set=%-5s get=%-5s doc=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__set__"),
        hasattr(value, "__get__"),
        repr(getattr(value, "__doc__", "-")),
    )


print(len(OWNED), len(dir(StopIteration)), list(OWNED))
for row in list(OWNED):
    print(line(row))

show("doc", lambda: StopIteration.__doc__)
show("no new row", lambda: "__new__" in OWNED)
show("no state row", lambda: "__getstate__" in OWNED)
show("bare value", lambda: StopIteration().value)
show("one positional", lambda: StopIteration("a").value)
show("two positionals take the first", lambda: StopIteration("a", "b").value)
show("arguments are both", lambda: StopIteration("a", "b").args)
show("value is not an argument", lambda: StopIteration("a").args)
show("new keeps nothing", lambda: StopIteration.__new__(StopIteration).value)
show("new keeps no arguments", lambda: StopIteration.__new__(StopIteration).args)
show("named refused", lambda: StopIteration(value=1))
show("any name refused", lambda: StopIteration(x=1))
show("name refused after value", lambda: StopIteration("a", name="n"))
show("str of a value", lambda: str(StopIteration("a")))
show("repr of a value", lambda: repr(StopIteration("a")))
show("repr of nothing", lambda: repr(StopIteration()))
show("reduce is two places", lambda: StopIteration("a").__reduce__())
show("reduce of empty", lambda: StopIteration().__reduce__())

show("write value", lambda: (lambda e: (setattr(e, "value", 5), e.value))(StopIteration()))
show("write None", lambda: (lambda e: (setattr(e, "value", None), e.value))(StopIteration("a")))
show("delete value", lambda: (lambda e: (delattr(e, "value"), e.value))(StopIteration("a")))
show("delete twice", lambda: (lambda e: (delattr(e, "value"), delattr(e, "value"), e.value))(StopIteration("a")))
show("value is not the dict", lambda: (lambda e: (setattr(e, "value", 1), dict(e.__dict__)))(StopIteration()))
show("re-init clears", lambda: (lambda e: (e.__init__("a"), e.__init__(), (e.args, e.value)))(StopIteration()))
show("re-init moves on", lambda: (lambda e: (e.__init__(1, 2), (e.args, e.value)))(StopIteration("a")))


class Stopped(StopIteration):
    pass


show("subclass keeps the field", lambda: Stopped("a").value)
show("subclass names itself", lambda: Stopped(x=1))
show("subclass refuse of nothing", lambda: Stopped(value=1))
show("subclass doc", lambda: Stopped.__doc__)
show("subclass dict", lambda: list(Stopped.__dict__))
show("row of another class", lambda: StopIteration.__dict__["__init__"](NameError("m")))


def counter(limit):
    for i in range(limit):
        yield i
    return "done"


show("generator value", lambda: (lambda g: (list(g), next(g, "gone")))(counter(2)))


def returned(count):
    for i in range(count):
        yield i
    return 7


def drained():
    made = returned(1)
    next(made)
    try:
        next(made)
    except StopIteration as stop:
        return (stop.value, stop.args, isinstance(stop, StopIteration))


show("return leaves a value", drained)
