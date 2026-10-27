"""The eight rows ``ImportError`` files, and the four fields they hold out.

``ImportError.__dict__`` has eight names and ``dir(ImportError)`` counts thirty-seven: four fields, a
``__str__``, a ``__reduce__``, an initialiser and the sentence. It is the largest of the family's leaves and
the only one of them with a ``wrapper_descriptor`` for ``__str__``, because it is the only one whose text is
read from a field rather than from the arguments. The order is the one ``type_ready_fill_dict`` writes:
``__str__``, then ``__init__``, then ``__reduce__`` from ``tp_methods``, then ``msg``, ``name``, ``path`` and
``name_from`` in the order ``PyImportErrorObject`` declares its pointers, then ``__doc__`` last. There is no
``__new__`` row and no ``__getstate__`` row, and ``ModuleNotFoundError`` under it files ``__doc__`` alone.

The four fields are ``_Py_T_OBJECT`` offsets, and the table the initialiser reads is ``|$OOO``: ``name``,
``path`` and ``name_from`` may all be handed by name, none of them may be handed positionally, and any of them
takes whatever arrives — ``ImportError('m', name=1)`` keeps the ``1``. ``msg`` is the one field filled from
the arguments, and the only one filled by a test on the count: exactly one positional is the message, two are
not, and a second argument therefore empties a message the first one made. That test is what puts
``str(ImportError('a', 'b'))`` back on ``BaseException_str`` and prints the pair, and it is the same test that
makes ``ImportError(name='n')`` a value with no arguments, no message and one name.

``__str__`` is ``ImportError_str``: a message that is exactly a ``str`` is printed as it stands, and anything
else — no message, a pair of arguments, a message overwritten with an ``int`` — falls through to the
``BaseException`` text. ``__reduce__`` answers two places when there is nothing to restore and three when
there is, and the state it hands the third place is ``ImportError_getstate``'s answer rather than
``object``'s: the dictionary itself when it is not empty, not a copy of it, which is what makes
``e.__dict__ is ImportError('m').__reduce__()[2]`` true, with ``name``, ``path`` and ``name_from`` added only
where their pointers are set. ``msg`` never counts towards that state, so a value whose only field is its
message still reduces to the pair.

The two classes share one initialiser and one reduction, and the refusal of a name the table does not have is
worded by the C function behind the row: ``ModuleNotFoundError('m', bad=1)`` is refused as ``ImportError()``,
and so is a class written under ``ModuleNotFoundError`` — while a keyword handed to a class under
``StopIteration`` is refused in that class's own words, because that one is refused by ``_PyArg_NoKeywords``,
which reads the value's type rather than its own table.

What this case does not print is the ``name`` and ``path`` the import machinery leaves on the
``ModuleNotFoundError`` it raises for a module that is not there, the ``multiple bases have instance lay-out
conflict`` that a class written under both this class and ``NameError`` would be refused, the ``__str__`` a
``UnicodeDecodeError`` keeps out of its own dictionary, and the address in the ``repr`` of a row.
"""


OWNED = ImportError.__dict__
MODULE_OWNED = ModuleNotFoundError.__dict__


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


print(len(OWNED), len(dir(ImportError)), list(OWNED))
for row in list(OWNED):
    print(line(OWNED, row))
print(len(MODULE_OWNED), len(dir(ModuleNotFoundError)), list(MODULE_OWNED))
for row in list(MODULE_OWNED):
    print(line(MODULE_OWNED, row))

show("doc", lambda: ImportError.__doc__)
show("module doc", lambda: ModuleNotFoundError.__doc__)
show("no new row", lambda: "__new__" in OWNED)
show("no getstate row", lambda: "__getstate__" in OWNED)
show("str row is not object's", lambda: ImportError.__dict__["__str__"] is object.__dict__["__str__"])
show("module has no str row", lambda: "__str__" in MODULE_OWNED)

show("bare four", lambda: (lambda e: (e.msg, e.name, e.path, e.name_from))(ImportError()))
show("one arg is the message", lambda: ImportError("m").msg)
show("one arg keeps nothing else", lambda: (lambda e: (e.name, e.path, e.name_from))(ImportError("m")))
show("two args are no message", lambda: ImportError("a", "b").msg)
show("two args are two arguments", lambda: ImportError("a", "b").args)
show("number is a message", lambda: ImportError(5).msg)
show("named message", lambda: (lambda e: (e.msg, e.name))(ImportError("m", name="n")))
show("all three names", lambda: (lambda e: (e.msg, e.name, e.path, e.name_from))(ImportError("m", name="n", path="p", name_from="f")))
show("names are not arguments", lambda: ImportError("m", name="n").args)
show("no message but a name", lambda: (lambda e: (e.args, e.msg, e.name, e.name_from))(ImportError(name="n")))
show("four names refused", lambda: ImportError("m", name="n", path="p", name_from="f", extra=1))
show("unknown name", lambda: ImportError("m", bad=1))
show("near name suggested", lambda: ImportError("m", pathn="p"))
show("near name suggested twice", lambda: ImportError("m", namee="n", pathh="p"))

show("str of one", lambda: str(ImportError("m")))
show("str of two", lambda: str(ImportError("a", "b")))
show("str of none", lambda: str(ImportError()))
show("str of a number", lambda: str(ImportError(5)))
show("str with a name", lambda: str(ImportError("m", name="n")))
show("str after a number written", lambda: (lambda e: (setattr(e, "msg", 5), str(e)))(ImportError("m")))
show("str after none written", lambda: (lambda e: (setattr(e, "msg", None), str(e)))(ImportError("m")))
show("repr", lambda: repr(ImportError("m")))

show("reduce of one", lambda: ImportError("m").__reduce__())
show("reduce of none", lambda: ImportError().__reduce__())
show("reduce with a name", lambda: ImportError("m", name="n").__reduce__())
show("reduce with all three", lambda: ImportError("m", name=1, path=2, name_from=3).__reduce__())
show("reduce does not count msg", lambda: len(ImportError("m").__reduce__()))
show("state is the dict", lambda: (lambda e: e.__dict__ is e.__reduce__()[2])(ImportError("m", name="n")))
show("empty dict gives no state", lambda: (lambda e: (e.__dict__, e.__reduce__()))(ImportError("m")))
show("reduce after delete", lambda: (lambda e: (delattr(e, "name"), e.__reduce__()))(ImportError("m", name="n")))
show("reduce takes nothing", lambda: ImportError("m").__reduce__(1))
show("reduce of an int", lambda: ImportError.__dict__["__reduce__"](3))

show("write name", lambda: (lambda e: (setattr(e, "name", "written"), e.name))(ImportError("m")))
show("write path", lambda: (lambda e: (setattr(e, "path", "p"), e.path))(ImportError("m")))
show("delete name", lambda: (lambda e: (delattr(e, "name"), e.name))(ImportError("m", name="n")))
show("delete twice", lambda: (lambda e: (delattr(e, "path"), delattr(e, "path"), e.path))(ImportError("m", path="p")))
show("msg is not in the state", lambda: (lambda e: ("msg" in e.__reduce__()[2], e.__reduce__()[2].get("name")))(ImportError("m", name="n")))
show("re-init keeps the name", lambda: (lambda e: (e.__init__("m2"), (e.args, e.msg, e.name)))(ImportError("m", name="n")))
show("re-init overwrites", lambda: (lambda e: (e.__init__("m2", name="n2"), (e.args, e.msg, e.name)))(ImportError("m", name="n")))


class Missing(ImportError):
    pass


class Deep(ModuleNotFoundError):
    pass


show("subclass keeps the fields", lambda: Missing("m", name="n").name)
show("subclass refusal names the row", lambda: Missing("m", bad=1))
show("module refusal names the row", lambda: ModuleNotFoundError("m", bad=1))
show("deep refusal names the row", lambda: Deep("m", bad=1))
show("module str of one", lambda: str(ModuleNotFoundError("No module named 'm'", name="m")))
show("module str of two", lambda: str(ModuleNotFoundError("a", "b")))
show("module reduce", lambda: ModuleNotFoundError("m", name="n").__reduce__())
show("subclass dict", lambda: list(Deep.__dict__))
show("row of another class", lambda: ImportError.__dict__["__reduce__"](AttributeError("a")))
show("str row of another class", lambda: ImportError.__dict__["__str__"](AttributeError("a")))
