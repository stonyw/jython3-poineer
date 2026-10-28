"""The two fields a group keeps, and the one subscript its class answers with.

``BaseExceptionGroup_members`` is two rows, ``message`` and ``exceptions``, and both are ``_Py_T_OBJECT``
pointers marked ``Py_READONLY`` — the only two of the family's nine-odd object fields that are read-only, and
the only two whose ``PyMemberDef`` carries a sentence of its own besides ``OSError``'s four. A read of the name
off the class hands back the ``member_descriptor``, and a read off a value reads the field: ``g.message`` is the
first argument the call was given and ``g.exceptions`` is the tuple the creator built out of the second, which
is why the second is a tuple where ``g.args[1]`` is still the list that was handed over.

Being read-only is not the same as being absent. ``tp_descr_set`` is written for both rows because CPython gives
every ``PyMemberDef`` a setter and lets the setter refuse, so these are *data* descriptors and outrank the
value's own dictionary: a write is refused with ``readonly attribute``, a delete is the same call with nothing
handed over and so says the same sentence, and neither reaches the dictionary at all — ``__reduce__``, which
reads ``self->args && self->dict``, keeps answering two items after four such refusals. Asking for ``__dict__``
is a different matter: the getset that hands it out allocates the dictionary when there is none, so the one
probe that proves the dictionary stayed empty is also what turns that answer into three. A class written in
Python can install a ``__setattr__`` of its own, and that replaces the whole lookup: the hook runs, and still
refuses, because ``object.__setattr__`` finds the same descriptor a plain assignment finds.

The subscript is the one row of the eleven that is a ``classmethod_descriptor``, and ``Py_GenericAlias`` builds
the answer with no checks at all: ``BaseExceptionGroup[int]``, ``BaseExceptionGroup[1]`` and
``BaseExceptionGroup[[int]]`` are all aliases, the origin is the class that was subscripted and the arguments are
whatever the key was, spread when the key is a tuple. Calling the row out of the dictionary has to be handed the
class first, which is why three refusals name the descriptor rather than the group — and one of them, ``requires
a subtype``, exists only because the row is read off ``BaseExceptionGroup`` and so knows what it can stand for.
A subclass keeps the row and changes the answer: ``type("G", (ExceptionGroup,), {})[int]`` is an alias of ``G``.
What this case does not print is the alias's own dictionary, which is the type ``types.GenericAlias`` rather than
either of these classes.
"""


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


FIELDS = ("message", "exceptions")
GROUP = ExceptionGroup("many", [ValueError("a"), TypeError("b")])
BASE = BaseExceptionGroup("raw", (KeyboardInterrupt(),))

show("message row", lambda: BaseExceptionGroup.message)
show("exceptions row", lambda: BaseExceptionGroup.exceptions)
show("row types", lambda: [type(BaseExceptionGroup.__dict__[n]).__name__ for n in FIELDS])
show("row docs", lambda: [BaseExceptionGroup.__dict__[n].__doc__ for n in FIELDS])
show("row qualnames", lambda: [BaseExceptionGroup.__dict__[n].__qualname__ for n in FIELDS])
show("row objclass", lambda: [BaseExceptionGroup.__dict__[n].__objclass__.__name__ for n in FIELDS])
show("rows are data", lambda: [hasattr(BaseExceptionGroup.__dict__[n], "__set__") for n in FIELDS])
show("row repr", lambda: repr(BaseExceptionGroup.__dict__["message"]))
show("group message", lambda: GROUP.message)
show("group exceptions", lambda: GROUP.exceptions)
show("base message", lambda: BASE.message)
show("base exceptions", lambda: BASE.exceptions)
show("args keep the list", lambda: GROUP.args)
show("dir has both", lambda: [n for n in dir(GROUP) if n in FIELDS])

show("reduce before anything", lambda: len(GROUP.__reduce__()))
show("write message", lambda: setattr(GROUP, "message", "other"))
show("write exceptions", lambda: setattr(GROUP, "exceptions", []))
show("delete message", lambda: delattr(GROUP, "message"))
show("delete exceptions", lambda: delattr(GROUP, "exceptions"))
show("reduce after refusals", lambda: len(GROUP.__reduce__()))
show("reads after refusals", lambda: (GROUP.message, GROUP.exceptions))
show("dict stays empty", lambda: GROUP.__dict__)
show("reduce after the dict read", lambda: len(GROUP.__reduce__()))
show("write through class", lambda: setattr(BaseExceptionGroup, "message", "other"))
show("write own name", lambda: (setattr(GROUP, "extra", 1), GROUP.extra)[1])
show("reduce of a value never asked", lambda: len(BASE.__reduce__()))

FIELD = BaseExceptionGroup.__dict__["message"]
show("get on value", lambda: FIELD.__get__(GROUP))
show("get with owner", lambda: FIELD.__get__(GROUP, ExceptionGroup))
show("get on class", lambda: FIELD.__get__(BaseExceptionGroup))
show("set refused", lambda: FIELD.__set__(GROUP, "x"))
show("delete refused", lambda: FIELD.__delete__(GROUP))
show("wrong receiver", lambda: FIELD.__get__(ValueError("x")))
show("no receiver", lambda: FIELD.__get__())
show("three args", lambda: FIELD.__set__(GROUP, "x", "y"))
show("keyword set", lambda: FIELD.__set__(obj=GROUP, value="x"))
show("read of a plain base", lambda: FIELD.__get__(BaseException("x")))

SHOWING = type("S", (ExceptionGroup,), {})
show("subclass rows", lambda: [n for n in FIELDS if n in SHOWING.__dict__])
show("subclass reads", lambda: SHOWING("m", [ValueError()]).message)
show("subclass row qualname", lambda: SHOWING.message)


class Routed(ExceptionGroup):
    def __setattr__(self, name, value):
        object.__setattr__(self, name, value)


HIDDEN = Routed("real", [ValueError("x")])
show("hook writes", lambda: setattr(HIDDEN, "message", "stored"))
show("read after hook", lambda: HIDDEN.message)
show("reduce after hook", lambda: HIDDEN.__reduce__())
show("dict after hook", lambda: HIDDEN.__dict__)
show("reduce after the second dict read", lambda: HIDDEN.__reduce__())


class Read(ExceptionGroup):
    def __delattr__(self, name):
        self.mark = name


MARKED = Read("real", [ValueError("x")])
show("hook deletes", lambda: delattr(MARKED, "message"))
show("hook left a mark", lambda: MARKED.__dict__)
show("field still reads", lambda: MARKED.message)

ITEM = BaseExceptionGroup.__dict__["__class_getitem__"]
show("getitem kind", lambda: type(ITEM).__name__)
show("getitem unbound empty", lambda: ITEM())
show("getitem unbound int", lambda: ITEM(3))
show("getitem unbound other class", lambda: ITEM(ValueError))
show("getitem unbound own class", lambda: ITEM(BaseExceptionGroup))
show("getitem unbound two", lambda: ITEM(BaseExceptionGroup, int))
show("bound no key", lambda: BaseExceptionGroup.__class_getitem__())
show("bound two keys", lambda: BaseExceptionGroup.__class_getitem__(int, str))
show("bound keyword", lambda: BaseExceptionGroup.__class_getitem__(item=int))

show("alias", lambda: BaseExceptionGroup[int])
show("alias two", lambda: BaseExceptionGroup[int, str])
show("alias empty", lambda: BaseExceptionGroup[()])
show("alias number", lambda: BaseExceptionGroup[1])
show("alias text", lambda: BaseExceptionGroup["text"])
show("alias list", lambda: BaseExceptionGroup[[int]])
show("alias nested", lambda: BaseExceptionGroup[BaseExceptionGroup[int]])
show("alias slice", lambda: BaseExceptionGroup[1:2])
show("alias origin", lambda: BaseExceptionGroup[int].__origin__ is BaseExceptionGroup)
show("alias args", lambda: BaseExceptionGroup[int, str].__args__)
show("alias parameters", lambda: BaseExceptionGroup[int].__parameters__)
show("alias equal", lambda: BaseExceptionGroup[int] == BaseExceptionGroup[int])
show("alias hash equal", lambda: hash(BaseExceptionGroup[int]) == hash(BaseExceptionGroup[int]))
show("alias str", lambda: str(BaseExceptionGroup[int]))
show("group alias", lambda: ExceptionGroup[int])
show("subclass alias", lambda: type("G", (ExceptionGroup,), {})[int])
show("instance not subscriptable", lambda: GROUP[int])
show("alias of a base value", lambda: BASE[int])
show("module row reads on instance", lambda: GROUP.__module__)
show("base instance module", lambda: BASE.__module__)
