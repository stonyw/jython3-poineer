"""The dictionary a type publishes, the proxy that publishes it, and the two storage names in it.

``X.__dict__`` is not the dictionary a class body filled. ``type_getdict`` answers a
``MappingProxyType``, so every read of a type's namespace is a view that refuses to be written: it files
no ``__setitem__``, no ``__delitem__``, no ``pop``, ``update``, ``setdefault`` or ``clear``, and each of
those is an ``AttributeError`` naming ``mappingproxy`` as the owner. The names it does have are the read
half of the mapping protocol — ``len``, ``in``, ``[]``, ``get``, ``keys``, ``values``, ``items``, ``copy``,
and ``|`` with a dictionary on either side. Two of those are worth pinning beside each other: ``copy()``
answers a plain ``dict`` that can then be written, and ``|`` answers a plain ``dict`` too, so neither is a
way of getting behind the view.

The view is a new object every time it is read — ``X.__dict__ is X.__dict__`` is false, and so is
``vars(X) is X.__dict__`` — and what answers a compare is the mapping underneath rather than the window in
hand, so two windows agree exactly when their dictionaries do: ``Thing.__dict__ == dict(Thing.__dict__)`` is
true and ``Thing.__dict__ == Sub.__dict__`` is false, the second because a subclass that adds nothing
files the four names of an empty statement where its base files the eight of a filled one. Two classes
made the same way on the same line are a pair of different dictionaries whose contents do agree, and they
compare equal for exactly that reason. Being a view rather than a copy is what makes it live: read the
namespace once, write ``Thing.new = 9`` after that, and the object already in hand answers nine.

Then the two names a class keeps for its values' storage. ``vars(Thing)["__dict__"]`` and
``vars(Thing)["__weakref__"]`` are the descriptors CPython's ``type_new`` files in the namespace it built —
``getset_descriptor``s, each carrying ``__name__``, a ``__qualname__`` of ``Thing.__dict__``, an
``__objclass__`` that is the class itself and the doc sentence the type table says; and the writing half
exists for one of the pair and not the other, which is why ``t.__dict__ = {}`` is answered while
``del t.__weakref__`` is ``attribute '__weakref__' of 'Thing' objects is not writable``. A name out of
``__slots__ = ("a",)`` is the other kind, a ``member_descriptor``, and a class that says neither gets
neither: which of the pair a class files is a question about that class alone, visible as names in its own
dictionary rather than as an attribute of its values.

What is *not* here is a built-in type's namespace. ``int.__dict__`` has sixty names in the interpreter
whose text this case was measured against and a handful in this one, so every listing below is of a class a
statement wrote.
"""


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


class Thing:
    """a class"""

    value = 1

    def method(self):
        return "m"


class Sub(Thing):
    pass


class Filled(Sub):
    extra = 2


class Slotted:
    __slots__ = ("a",)


class OnlyDict:
    __slots__ = ("__dict__",)


class OnlyWeak:
    __slots__ = ("__weakref__",)


class Both:
    __slots__ = ("__dict__", "b")


class Error(Exception):
    pass


class Meta(type):
    pass


STORAGE = ("__dict__", "__weakref__")


print("== what kind of object a type's namespace is ==")
namespace = Thing.__dict__
show("type", lambda: type(namespace).__name__)
show("class is that type", lambda: namespace.__class__ is type(namespace))
show("module", lambda: type(namespace).__module__)
show("bases", lambda: [b.__name__ for b in type(namespace).__mro__])
show("subclassable", lambda: issubclass(type(namespace), object))
show("vars is dict", lambda: vars(Thing) is Thing.__dict__)
show("twice", lambda: Thing.__dict__ is Thing.__dict__)
show("sub differs", lambda: Sub.__dict__ is Thing.__dict__)
show("names", lambda: sorted(Thing.__dict__))
show("len", lambda: len(Thing.__dict__))
show("in", lambda: ("value" in Thing.__dict__, "other" in Thing.__dict__))
show("get", lambda: (Thing.__dict__.get("value"), Thing.__dict__.get("nope", "?")))
show("index", lambda: Thing.__dict__["value"])
show("keys", lambda: sorted(Thing.__dict__.keys()))
show("values", lambda: type(Thing.__dict__.values()).__name__)
show("items", lambda: sorted(k for k, v in Thing.__dict__.items() if isinstance(k, str)))
show("iter", lambda: sorted(Thing.__dict__))
show("eq dict", lambda: Thing.__dict__ == dict(Thing.__dict__))
show("eq other", lambda: Thing.__dict__ == Sub.__dict__)
Twin = type("Twin", (), {"__slots__": (), "x": 1})
Other = type("Twin", (), {"__slots__": (), "x": 1})
show("same names", lambda: sorted(Twin.__dict__) == sorted(Other.__dict__))
show("two dictionaries, one answer", lambda: Twin.__dict__ == Other.__dict__)
show("as a value", lambda: {"k": Twin.__dict__} == {"k": Other.__dict__})
show("not a key", lambda: {Twin.__dict__: 1})
show("copy", lambda: (type(Thing.__dict__.copy()).__name__, "value" in Thing.__dict__.copy()))
show("or", lambda: (type(Thing.__dict__ | {"x": 1}).__name__, (Thing.__dict__ | {"x": 1})["x"]))
show("ror", lambda: ({"x": 1} | Thing.__dict__)["value"])
show("repr kind", lambda: type(repr(namespace)).__name__)
show("bool", lambda: bool(namespace))
show("empty", lambda: bool(type("Empty", (), {}).__dict__) is False)

print("== the proxy is not a way of writing the namespace ==")
show("set", lambda: namespace.__setitem__("x", 1))
show("del", lambda: namespace.__delitem__("value"))
show("assign", lambda: namespace.__setattr__("x", 1))
show("pop", lambda: namespace.pop("value"))
show("update", lambda: namespace.update({"value": 2}))
show("setdefault", lambda: namespace.setdefault("value", 3))
show("clear", lambda: namespace.clear())
show("d[k] = 1", lambda: namespace.__setitem__("x", 1))
show("read __setitem__", lambda: namespace.__setitem__)
show("read __delitem__", lambda: namespace.__delitem__)
show("class keeps nothing", lambda: "x" in Thing.__dict__, )
show("value still one", lambda: Thing.value)

print("== what writing the class does to the view ==")
Thing.new = 9
show("in the namespace", lambda: Thing.__dict__["new"])
show("in the same view", lambda: namespace["new"])
show("sorted", lambda: sorted(k for k in namespace if k.startswith("new") or k == "value"))
del Thing.new
show("after delete", lambda: "new" in namespace)
show("proxy object", lambda: Thing.__dict__ is namespace)
show("subclass empty", lambda: sorted(Sub.__dict__))
show("filled", lambda: sorted(Filled.__dict__))
show("inherits", lambda: Filled.value, )
show("its own", lambda: Filled.__dict__["extra"])

print("== the two storage names, and which class files which ==")
for kind in (Thing, Sub, Slotted, OnlyDict, OnlyWeak, Both, Error, Meta):
    shown = kind.__dict__
    print(
        "%-8s %-9s %-24s %-16s %s"
        % (kind.__name__,
           probe("filed", lambda s=shown: [n for n in STORAGE if n in s]),
           probe("kinds", lambda s=shown: [type(s[n]).__name__ for n in STORAGE if n in s]),
           probe("other", lambda k=kind: [n for n in sorted(k.__dict__)
                                          if n not in STORAGE and not n.startswith("__")]),
           probe("all", lambda k=kind: sorted(k.__dict__)),
           )
    )

print("== the two storage names read back at themselves ==")
dict_descriptor = Thing.__dict__["__dict__"]
weak_descriptor = Thing.__dict__["__weakref__"]
slot_descriptor = Slotted.__dict__["a"]
show("name", lambda: (dict_descriptor.__name__, weak_descriptor.__name__, slot_descriptor.__name__))
show("qualname", lambda: (dict_descriptor.__qualname__, slot_descriptor.__qualname__))
show("objclass", lambda: (dict_descriptor.__objclass__ is Thing, slot_descriptor.__objclass__ is Slotted))
show("type", lambda: type(dict_descriptor).__name__)
show("dict doc", lambda: dict_descriptor.__doc__)
show("weak doc", lambda: weak_descriptor.__doc__)
show("slot doc", lambda: slot_descriptor.__doc__)
show("same object twice", lambda: Thing.__dict__["__dict__"] is dict_descriptor)
show("read of the read", lambda: dict_descriptor.__objclass__.__name__)
show("weakref of a class", lambda: type(Meta.__dict__.get("__weakref__")).__name__)

print("== what the values answer, and where the storage is ==")
thing = Thing()
show("dict", lambda: thing.__dict__)
show("vars", lambda: vars(thing))
show("descriptor agrees", lambda: dict_descriptor.__get__(thing) is thing.__dict__)
show("weak", lambda: thing.__weakref__)
show("write through", lambda: (setattr(thing, "z", 1), thing.__dict__))
show("del through", lambda: (thing.__delattr__("z"), thing.__dict__))
thing.__dict__ = {"replaced": True}
show("replaced", lambda: (thing.__dict__, thing.replaced))
show("through the descriptor", lambda: dict_descriptor.__get__(thing))
del thing.__dict__
show("after del", lambda: thing.__dict__)
show("write again", lambda: (setattr(thing, "w", 2), thing.__dict__))
def twice(thunk):
    """Two attempts on one line, where the first takes something away and the second asks again."""
    try:
        thunk()
        first = "took"
    except BaseException as error:
        first = "%s: %s" % (type(error).__name__, error)
    try:
        thunk()
        second = "took"
    except BaseException as error:
        second = "%s: %s" % (type(error).__name__, error)
    return first + " | " + second


show("delete dict twice", lambda: twice(lambda: thing.__delattr__("__dict__")))
show("weak set", lambda: (setattr(thing, "__weakref__", 1), thing.__weakref__))
show("weak del", lambda: delattr(thing, "__weakref__"))
show("slot write", lambda: (setattr(Slotted(), "a", 1), "a"))
shown = Slotted()
show("slot read", lambda: shown.a)
show("no dict", lambda: shown.__dict__)
show("no weak", lambda: shown.__weakref__)
show("vars", lambda: vars(shown))
show("slot unset", lambda: Slotted().a)
show("slot del unset", lambda: delattr(Slotted(), "a"))
both = Both()
show("both dict", lambda: (both.__dict__, vars(both)))
show("both slot", lambda: both.b)
show("both weak", lambda: both.__weakref__)
show("only weak dict", lambda: OnlyWeak().__dict__)
show("only dict weak", lambda: OnlyDict().__weakref__)
error = Error("e")
show("error dict", lambda: (error.__dict__, vars(error)))
show("error weak", lambda: error.__weakref__)
show("error descriptor", lambda: type(Error.__dict__["__dict__"]).__name__)

print("== a metaclass namespace is a namespace too ==")
show("type", lambda: type(Meta.__dict__).__name__)
show("names", lambda: [n for n in sorted(Meta.__dict__) if not n.startswith("__")])
def write(owner, name, value):
    """Write one name through the ordinary path, and report both what was stored and what the proxy saw."""
    try:
        setattr(owner, name, value)
        stored = repr(getattr(owner, name))
    except BaseException as error:
        stored = "%s: %s" % (type(error).__name__, error)
    return "%s | %s" % (stored, probe("proxy", lambda n=name, o=owner: o.__dict__[n]))


show("written", lambda: write(Meta, "tag", 1))
show("of an instance", lambda: type(Meta("X2", (), {}).__dict__).__name__)
show("instance of it", lambda: sorted(k for k in Meta("X3", (), {}).__dict__ if k == "tag"))

print("== dir is the sorted names, proxy or not ==")
show("thing", lambda: dir(thing) == sorted(set(dir(Thing)) | {"replaced", "w"}))
show("sub has the base", lambda: [n for n in ("value", "method") if n in dir(Sub)])
show("slotted", lambda: [n for n in dir(Slotted) if n in ("a", "__dict__", "__weakref__")])
show("dunder order", lambda: dir(Thing)[:3])
show("on the proxy", lambda: sorted(n for n in dir(namespace) if n.startswith("__and")) == [])
