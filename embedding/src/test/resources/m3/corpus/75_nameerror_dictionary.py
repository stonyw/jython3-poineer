"""The three rows ``NameError`` files, and the one row the class under it files.

``NameError.__dict__`` is ``__init__``, ``name`` and ``__doc__`` and ``dir(NameError)`` counts thirty-four:
one name fewer than ``AttributeError``, because the field that says what was asked for has no second half
here. There is no ``__new__`` row for the same reason there is none for ``AttributeError`` — the creator is
``BaseException``'s and the class inherits it — and no ``__reduce__`` row, so a name error reduces to the two
places every plain exception does and never to the three ``AttributeError`` reaches for. The keyword table is
``|$O`` against an emptied argument tuple: one name only, positionals never counted, so ``NameError('a', 1,
name='n')`` keeps two arguments and fills one field.

``UnboundLocalError`` is the case that makes the row worth looking at rather than the field. It declares
nothing of its own and files exactly one name, ``__doc__``, so ``len(UnboundLocalError.__dict__)`` is one and
its ``dir`` is the same thirty-four its parent reports. Its creator and its initialiser are ``NameError``'s,
which means the complaint about a name that table does not have is made in ``NameError``'s words however many
classes have been written underneath — ``UnboundLocalError('m', bad=1)`` is refused as ``NameError()``, and so
is a class under ``UnboundLocalError``. The suggestion is the same mechanism: ``nme=`` is offered ``name``,
and the second name of ``AttributeError`` is not offered at all, because a table of one cannot suggest what it
does not hold.

The field is a ``_Py_T_OBJECT`` offset like ``AttributeError``'s two, so unset reads ``None``, deleting reads
``None`` a second time, and writing an object into the name is not the same thing as the interpreter putting
one there after a failed lookup — that path is slice 15's and fills the field after the message is already
written.

What this case does not print is the ``Did you mean`` sentence of a ``NameError`` the interpreter raised for a
name that was not there, the ``multiple bases have instance lay-out conflict`` that a class written under both
this class and ``ImportError`` would be refused — the two keep different extra fields and this runtime has no
instance layout to compare — or the ``repr`` of a ``method_descriptor``, which names an address.
"""


NAME_OWNED = NameError.__dict__
UNBOUND_OWNED = UnboundLocalError.__dict__


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def line(owned, name):
    value = owned[name]
    return "%-22s %-24s %-28s set=%-5s get=%-5s doc=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__set__"),
        hasattr(value, "__get__"),
        repr(getattr(value, "__doc__", "-")),
    )


print(len(NAME_OWNED), len(dir(NameError)), list(NAME_OWNED))
for row in list(NAME_OWNED):
    print(line(NAME_OWNED, row))
print(len(UNBOUND_OWNED), len(dir(UnboundLocalError)), list(UNBOUND_OWNED))
for row in list(UNBOUND_OWNED):
    print(line(UNBOUND_OWNED, row))

show("name doc", lambda: NameError.__doc__)
show("unbound doc", lambda: UnboundLocalError.__doc__)
show("no new row", lambda: "__new__" in NAME_OWNED)
show("no reduce row", lambda: "__reduce__" in NAME_OWNED)
show("no obj row", lambda: "obj" in NAME_OWNED)
show("bare name", lambda: NameError().name)
show("one arg", lambda: NameError("a").args)
show("one arg name", lambda: NameError("a").name)
show("named", lambda: NameError("m", name="n").name)
show("named does not argue", lambda: NameError("m", name="n").args)
show("positional is not named", lambda: NameError("m", "n").name)
show("positionals kept", lambda: NameError("m", 1, name="n").args)
show("two names refused", lambda: NameError("m", name="n", obj=1))
show("obj refused by name", lambda: NameError("m", obj=1))
show("near name suggested", lambda: NameError("m", nme="n"))
show("write name", lambda: (lambda e: (setattr(e, "name", "written"), e.name))(NameError("m")))
show("delete name", lambda: (lambda e: (delattr(e, "name"), e.name, e.__reduce__()))(NameError("m", name="n")))
show("reduce of named", lambda: NameError("m", name="n").__reduce__())
show("reduce is two places", lambda: len(NameError("m", name="n").__reduce__()))
show("str of named", lambda: str(NameError("m", name="n")))
show("repr of named", lambda: repr(NameError("m", name="n")))
show("init again", lambda: (lambda e: (e.__init__("z"), (e.args, e.name)))(NameError("m", name="n")))
show("init with the name", lambda: (lambda e: (e.__init__("z", name="q"), (e.args, e.name)))(NameError("m", name="n")))

show("unbound named", lambda: UnboundLocalError("m", name="n").name)
show("unbound bad name", lambda: UnboundLocalError("m", bad=1))
show("unbound too many", lambda: UnboundLocalError("m", name="n", path="p"))
show("unbound suggestion", lambda: UnboundLocalError("m", nme="n"))


class Under(UnboundLocalError):
    pass


class Local(NameError):
    pass


show("grandchild row", lambda: Under("m", name="n").name)
show("grandchild refusal", lambda: Under("m", bad=1))
show("child refusal", lambda: Local("m", bad=1))
show("child init names itself", lambda: Local("m").__init__(bad=1))
show("row of another class", lambda: NameError.__dict__["__init__"](AttributeError("a")))
show("row with no receiver", lambda: NameError.__dict__["__init__"]())
