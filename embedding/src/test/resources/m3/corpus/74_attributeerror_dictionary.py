"""The six rows ``AttributeError`` files, and the two fields they hand out to a value.

``AttributeError.__dict__`` holds six names and ``dir(AttributeError)`` counts thirty-five: the six, and the
twenty-nine every class of the family reaches through ``BaseException`` and ``object``. ``add_operators``
files nothing here — the class runs ``BaseException``'s ``tp_repr`` and its own ``tp_str`` is only reached
through ``__init__``'s arguments — so the table is one ``wrapper_descriptor``, two ``method_descriptor`` rows
and two ``member_descriptor`` rows beside the sentence it ends with. ``__new__`` is not among them: the
creator is ``BaseException``'s own and the class inherits the slot rather than filling it, which is why a
call of this class keeps its positionals and reads its names in ``__init__`` and nowhere else.

The two fields are the reason the class has a dictionary at all. ``name`` and ``obj`` are ``_Py_T_OBJECT``
offsets, so the two things a person expects of an attribute failure — what was wanted, and of what — are
readable, writable and deletable, and an unfilled one reads ``None`` rather than complaining. A bare
``AttributeError()`` therefore answers ``None`` twice, and ``AttributeError('a', name='n', obj=3)`` answers
the two objects it was handed by name. The keyword table is ``|$OO`` parsed against an argument tuple that
has just been emptied: no positional can ever reach those two names, ``AttributeError('a', 'b')`` keeps both
as arguments and gives no field, and the count that ``takes at most 2 keyword arguments (3 given)`` complains
about counts only what was named.

``__getstate__`` is the one of the family's two places that a class files for itself, and it is what makes
pickling of this class different from every other leaf. The state is a copy of the dictionary — reading it
never makes one, and ``e.__getstate__() is e.__dict__`` is false — with ``args`` in it always, because
``BaseException_new`` leaves that pointer non-null even for a call with no arguments, and ``name`` in it only
when the pointer is set. ``obj`` is not in it at all. ``__reduce__`` therefore always answers three places
where ``BaseException`` answers two or three on the same pointers, and a value with only an object to show
for itself reduces to the pair-plus-empty-state that ``AttributeError('a', obj=3)`` prints.

What this case does not print is the address in ``repr`` of ``__new__``, the ``__basicsize__`` and
``__flags__`` this runtime answers as zero, the ``dir()`` of the two member descriptors — whose own
dictionaries belong to the descriptor types and not to this class — the ``AttributeError`` an interpreter
leaves its ``name`` and ``obj`` on after a failed read, which is slice 15's face, and the
``multiple bases have instance lay-out conflict`` that a class under this one and ``OSError`` would be
refused for, which needs a layout to disagree about.
"""


OWNED = AttributeError.__dict__


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def line(name):
    """One row of the table: its kind, its qualified name and what the kind carries."""
    value = OWNED[name]
    return "%-22s %-24s %-28s set=%-5s get=%-5s doc=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__set__"),
        hasattr(value, "__get__"),
        repr(getattr(value, "__doc__", "-")),
    )


print(len(OWNED), len(dir(AttributeError)))
for row in list(OWNED):
    print(line(row))

show("doc", lambda: AttributeError.__doc__)
show("no new row", lambda: "__new__" in OWNED)
show("str is BaseException str", lambda: AttributeError.__str__ is BaseException.__str__)
show("repr is BaseException repr", lambda: AttributeError.__repr__ is BaseException.__repr__)

show("bare name", lambda: AttributeError().name)
show("bare obj", lambda: AttributeError().obj)
show("bare args", lambda: AttributeError().args)
show("bare str", lambda: str(AttributeError()))
show("positional only", lambda: AttributeError("a", "b").args)
show("positional name", lambda: AttributeError("a", name="n").name)
show("positional obj", lambda: AttributeError("a", name="n", obj=3).obj)
show("obj identity kept", lambda: (lambda o: AttributeError("a", obj=o).obj is o)([1, 2]))
show("named is not positional", lambda: AttributeError("a", 1, name="n").args)
show("name only, no message", lambda: AttributeError(name="n").args)
show("three names refused", lambda: AttributeError("a", name="n", obj=1, extra=2))
show("unknown name suggested", lambda: AttributeError("a", nme="n"))
show("bad name after good", lambda: AttributeError("a", name="n", objj=1))

show("write name", lambda: (lambda e: (setattr(e, "name", "written"), e.name))(AttributeError("a")))
show("write obj", lambda: (lambda e: (setattr(e, "obj", 42), e.obj))(AttributeError("a")))
show("write None reads None", lambda: (lambda e: (setattr(e, "obj", None), e.obj))(AttributeError("a")))
show("delete reads None", lambda: (lambda e: (delattr(e, "name"), e.name))(AttributeError("a", name="n")))
show("delete twice", lambda: (lambda e: (delattr(e, "name"), delattr(e, "name"), e.name))(AttributeError("a", name="n")))
show("delete is not dict", lambda: (lambda e: (delattr(e, "name"), dict(e.__dict__)))(AttributeError("a", name="n")))
show("field over dict", lambda: (lambda e: (setattr(e, "name", "field"), e.name))(AttributeError("a")))
show("re-init with the name", lambda: (lambda e: (e.__init__("z", name="q"), (e.args, e.name, e.__reduce__())))(AttributeError("a", name="n")))
show("re-init without it", lambda: (lambda e: (e.__init__("z"), (e.args, e.name, e.__reduce__())))(AttributeError("a", name="n")))
show("field after delete and dict write", lambda: (lambda e: (delattr(e, "name"), setattr(e, "name", "again"), e.name))(AttributeError("a", name="n")))

def entered(value, item):
    """A value with one entry written into the dictionary it already had."""
    value.__dict__["x"] = item
    return value


show("getstate of bare", lambda: AttributeError().__getstate__())
show("getstate of new", lambda: AttributeError.__new__(AttributeError).__getstate__())
show("getstate is dict", lambda: (lambda e: e.__getstate__() is e.__dict__)(AttributeError("a")))
show("getstate copies dict", lambda: entered(AttributeError("a", name="n"), 1).__getstate__())
show("getstate makes no dict", lambda: (lambda e: (e.__getstate__(), list(e.__dict__)))(AttributeError("a")))
show("reduce of bare", lambda: AttributeError().__reduce__())
show("reduce with name", lambda: AttributeError("a", name="n").__reduce__())
show("reduce with obj only", lambda: AttributeError("a", obj=3).__reduce__())
show("reduce with dict entry", lambda: entered(AttributeError("a", name="n"), 1).__reduce__())
show("reduce takes nothing", lambda: AttributeError("a").__reduce__(1))
show("getstate takes no keyword", lambda: AttributeError("a").__getstate__(x=1))
show("reduce of another class", lambda: AttributeError.__dict__["__reduce__"](ImportError("m")))
show("getstate of an int", lambda: AttributeError.__dict__["__getstate__"](3))
show("init of an int", lambda: AttributeError.__dict__["__init__"](3))
show("init of nothing", lambda: AttributeError.__dict__["__init__"]())


class Sub(AttributeError):
    pass


class Own(Sub):
    def __init__(self, message, **fields):
        pass


show("subclass keeps the row", lambda: Sub("a", name="n").name)
show("subclass refusal names the row", lambda: Sub("a", bad=1))
show("subclass doc is none", lambda: Sub.__doc__)
show("subclass dict has doc", lambda: "__doc__" in Sub.__dict__)
show("own init names itself", lambda: Own("a", name="n"))
show("own init keeps nothing", lambda: Own("a").args)
