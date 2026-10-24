"""The metatype's turn at a write: the member it files, the hook it declares, the name it hands back.

``type_setattro`` asks the metatype before it touches the class's dictionary, so a metaclass that files a
``property`` owns the name on every class built from it. ``Owner.shaped = 7`` runs the property's setter
and the value lands wherever that setter chose; ``del Owner.shaped`` is refused as ``property 'shaped' of
'WithProp' object has no deleter`` — the property's own words, with the *metatype* named as the owner
because that is the class the property belongs to; and a name with no setter is refused the same way for
a write, while reading it still answers the getter. A metaclass that declares ``__setattr__`` rather than
filing a property is asked by ``slot_tp_setattro``, so ``Guarded.x = 1`` runs that function and never
reaches a dictionary — and because the metatype is what was asked, the class it was asked for arrives as
the first argument, which is what lets ``super().__setattr__(name, value)`` file the name and return
without being asked again.

Names the metatype says nothing about are filed in the class's own dictionary, and that is the same
dictionary ``X.__dict__`` shows: ``Owner.other = 1`` lands in the next read of it even though the
metaclass files a property under a different name, and ``Guarded.x = 1`` is visible there once the hook
has handed it to ``type.__setattr__``. A class's ``__module__`` and ``__doc__`` are the two special names
whose writing half is not a plain store, and both are visible from the namespace. Setting ``__module__``
is how a decorator moves a class, and it drops ``__firstlineno__`` on the way through, so the line the
statement was on is gone as soon as the module name is written; ``type_set_doc`` stores whatever
arrives, so ``C.__doc__ = None`` and ``C.__doc__ = 5`` both answer the value rather than a complaint about
its kind — a class whose statement wrote no docstring still files ``None`` for it, and a ``del`` of the
name is refused by the very same setter, as ``cannot delete '__doc__' attribute of immutable type
'Doced'``, the sentence every one of these special names uses for a delete once the value has been read.

The last two are reads that are only there because the write path is. A metaclass ``__getattr__`` answers
a name the class does not have, and it is asked through the metatype, so ``Annotated.anything`` reaches it
while ``Annotated.mro`` — a name the metatype does file — never does. And ``class Slotted`` with
``__slots__ = ('left',)`` is the one name a class files that is a member rather than a getset: its
writing half exists, so ``Slotted.left = 1`` is answered without a complaint and the next read of
``Slotted.left`` gives ``1`` — a write to the class, where reading the same name on a value reads that
value's own storage.
"""


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


class WithProp(type):
    @property
    def shaped(cls):
        return "shape"

    @shaped.setter
    def shaped(cls, value):
        cls.stored = value


class ReadOnly(type):
    @property
    def plain(cls):
        return "plain"


class Hooked(type):
    seen = []

    def __setattr__(cls, name, value):
        Hooked.seen.append((cls.__name__, name, value))
        super().__setattr__(name, value)

    def __delattr__(cls, name):
        Hooked.seen.append(("del", cls.__name__, name))
        super().__delattr__(name)


class Meta(type):
    @property
    def __module__(cls):
        return "claimed"

    @__module__.setter
    def __module__(cls, value):
        cls.marked = value


class DocMeta(type):
    @property
    def __doc__(cls):
        return "metaclass sentence"


class AnnoMeta(type):
    def __getattr__(cls, name):
        return "found:" + name


class Owner(metaclass=WithProp):
    pass


class Left(metaclass=ReadOnly):
    pass


class Guarded(metaclass=Hooked):
    pass


class Held(metaclass=Meta):
    pass


class Documented(metaclass=DocMeta):
    """Own sentence."""


class Annotated(metaclass=AnnoMeta):
    pass


class Slotted:
    "text"

    __slots__ = ("left",)


print("== the property the metatype files ==")
show("read", lambda: Owner.shaped)
show("write", lambda: setattr(Owner, "shaped", 7))
show("stored", lambda: Owner.stored)
show("delete", lambda: delattr(Owner, "shaped"))
show("no setter write", lambda: setattr(Left, "plain", 1))
show("no setter delete", lambda: delattr(Left, "plain"))
show("still reads", lambda: Left.plain)
show("plain name on a class with a property", lambda: (setattr(Owner, "other", 1), Owner.other))

print("== the hook the metatype declares ==")
show("write", lambda: setattr(Guarded, "x", 1))
show("after", lambda: (Guarded.x, Guarded.__dict__["x"]))
show("delete", lambda: delattr(Guarded, "x"))
show("gone", lambda: "x" in Guarded.__dict__)
show("seen", lambda: Hooked.seen)
show("special name", lambda: setattr(Guarded, "__name__", "Renamed"))
show("name after", lambda: Guarded.__name__)

print("== the names whose write is not a store ==")
show("module read", lambda: Held.__module__)
show("module write", lambda: setattr(Held, "__module__", "written"))
show("module after", lambda: (Held.__module__, Held.marked))
show("metaclass doc read", lambda: Documented.__doc__)
show("metaclass doc write", lambda: setattr(Documented, "__doc__", "written"))
show("doc filed", lambda: Documented.__dict__["__doc__"])


def lined():
    class Line:
        """Says something."""

        x: int

    show("firstlineno", lambda: Line.__firstlineno__)
    show("doc", lambda: Line.__doc__)
    show("static", lambda: Line.__static_attributes__)
    Line.__module__ = "elsewhere"
    show("firstlineno after module", lambda: hasattr(Line, "__firstlineno__"))
    show("module after", lambda: Line.__module__)


lined()

print("== the doc a class statement did and did not file ==")


class Doced:
    pass


show("no doc", lambda: (Doced.__doc__, "__doc__" in vars(Doced)))
Doced.__doc__ = None
show("none written", lambda: (Doced.__doc__, vars(Doced)["__doc__"]))
Doced.__doc__ = 5
show("number written", lambda: (Doced.__doc__, type(Doced.__doc__).__name__))
show("delete", lambda: delattr(Doced, "__doc__"))
show("after delete", lambda: (Doced.__doc__, "__doc__" in vars(Doced)))

print("== the names read through the metatype rather than written ==")
show("metaclass getattr", lambda: Annotated.anything)
show("filed name", lambda: type(Annotated.mro).__name__)
show("slot on the class", lambda: setattr(Slotted, "left", 1))
show("slot read", lambda: Slotted.left)
