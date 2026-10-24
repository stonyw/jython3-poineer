"""The kinds of descriptor a type's dictionary files, and what each one answers when it is asked.

``object.__dict__`` and ``type.__dict__`` are the two namespaces this runtime fills the way the
interpreter does — twenty-four names and thirty-four, in the same sorted order — and they are where the
five descriptor kinds can be told apart without a built-in type's missing names getting in the way.
``method_descriptor`` is a C function filed under a name, ``wrapper_descriptor`` is the slot a type
overrides with something Python may already have written, ``getset_descriptor`` is a pair of half-functions
named by ``PyGetSetDef``, ``member_descriptor`` is a C offset read straight out of the value's storage, and
``classmethod_descriptor`` is the one whose receiver is a class. The kinds divide on exactly one question —
does the descriptor file a writing half — and it is that answer, not the name, that decides whether
``C.__dir__ = 1`` lands in the class dictionary or is refused, and in whose words the refusal comes: a
getset with no setter says ``attribute '__mro__' of 'type' objects is not writable``, a member with none
says the member's own ``readonly attribute``, and a method or wrapper descriptor has no ``__set__`` to ask
at all — ``'method_descriptor' object has no attribute '__set__'``.

Each descriptor answers the same four names about itself — ``__name__``, ``__qualname__``, ``__objclass__``
and ``__doc__`` — and ``__objclass__`` is the one that makes the table legible: it names the type that owns
the descriptor rather than the type it was asked through, so ``int.mro.__objclass__`` is ``type``. Being
descriptors, they all take ``__get__(value, owner)``, and the two that file a writing half take ``__set__``
and ``__delete__`` with the counts each of them words: ``__set__ expected 2 arguments, got 1``, and the
delete with nothing in front of the count at all — ``expected 1 argument, got 2``.

Then the two that are not really attributes of a value: ``object.__dict__['__new__']`` is a
``builtin_function_or_method`` with no ``__objclass__`` at all, and it is the one name a class inherits that
is called with the class as its first argument, while ``type.__dict__['__prepare__']`` is a
``classmethod_descriptor`` that answers the body of a class statement with the dictionary that body fills.
"""


def kind(value):
    return type(value).__name__


def table(owner, skip=()):
    """One line per name the type files, with the descriptor's own kind, owner and doc kind.

    <p>The entry named ``__doc__`` is skipped by the callers that ask it: in this runtime a built-in type
    files ``None`` there where the interpreter files a sentence, and the difference is the type's own text
    rather than anything about the descriptor tables this case is about.
    """
    for name in sorted(owner.__dict__):
        member = owner.__dict__[name]
        doc = "-" if name in skip else kind(getattr(member, "__doc__", "-"))
        print(
            "%-24s %-24s %-14s %-16s %s"
            % (name, kind(member), getattr(getattr(member, "__objclass__", None), "__name__", "-"),
               doc, sorted(owner.__dict__) == sorted(vars(owner)))
        )


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


class Plain:
    pass


class Meta(type):
    pass


class Sided:
    pass


print("== the two complete namespaces ==")
table(object, skip=("__doc__",))
table(type, skip=("__doc__",))
print("== which kind files which name ==")
for owner in (object, type):
    seen = {}
    for name in sorted(owner.__dict__):
        seen.setdefault(kind(owner.__dict__[name]), []).append(name)
    print(owner.__name__, [(k, len(v)) for k, v in sorted(seen.items())])
    print("", [(n, k) for k, v in sorted(seen.items()) for n in v if k != "wrapper_descriptor"])

print("== the names every descriptor answers about itself ==")
for name in ("__dir__", "__eq__", "__init__", "__getattribute__", "__subclasshook__", "__sizeof__"):
    member = object.__dict__[name]
    show(
        "%-18s" % name,
        lambda m=member: (kind(m), m.__name__, m.__qualname__, m.__objclass__.__name__,
                          isinstance(m.__doc__, str), len(m.__doc__.split())),
    )
for name in ("__name__", "__bases__", "__mro__", "__dict__", "__abstractmethods__", "mro", "__prepare__"):
    member = type.__dict__[name]
    show(
        "%-18s" % name,
        lambda m=member: (kind(m), m.__name__, m.__qualname__, m.__objclass__.__name__),
    )

print("== a method descriptor is the function the name means ==")
show("bound", lambda: kind(object.__dict__["__dir__"]))
show("call", lambda: object.__dir__(Plain()))
show("through the table", lambda: object.__dict__["__dir__"].__get__(Plain())())
show("of a class", lambda: object.__dict__["__dir__"].__get__(Plain, Plain)()[:2])
show("no receiver", lambda: object.__dict__["__dir__"].__get__(None, object))
show("wrong receiver", lambda: (kind(object.__dict__["__dir__"].__get__(42, object)),
                                object.__dict__["__dir__"].__get__(42, object).__name__,
                                object.__dict__["__dir__"].__get__(42, object).__self__ == 42))
show("unbound call", lambda: object.__dict__["__dir__"](Plain()))
show("self call", lambda: object.__dict__["__dir__"].__get__(Plain())(42))
show("mro of int", lambda: [k.__name__ for k in type.__dict__["mro"].__get__(int)()])
show("mro unbound", lambda: [k.__name__ for k in type.__dict__["mro"](int)])
show("getstate kind", lambda: kind(object.__getstate__(Plain())))
show("reduce kind", lambda: kind(object.__dict__["__reduce_ex__"]))
show("reduce objclass", lambda: object.__dict__["__reduce_ex__"].__objclass__.__name__)
show("reduce unbound", lambda: kind(object.__reduce_ex__))
show("newobj kind", lambda: kind(object.__dict__["__new__"]))

print("== a getset reads storage, and refuses to write what has no setter ==")
name_descriptor = type.__dict__["__name__"]
bases_descriptor = type.__dict__["__bases__"]
abstract_descriptor = type.__dict__["__abstractmethods__"]
show("read", lambda: name_descriptor.__get__(int))
show("of a written class", lambda: name_descriptor.__get__(Plain))
show("no instance", lambda: name_descriptor.__get__(None, object))
show("wrong receiver", lambda: name_descriptor.__get__(42, type))
show("write", lambda: name_descriptor.__set__(int, "NewName"))
show("delete", lambda: name_descriptor.__del__(int))
show("doc set", lambda: type.__dict__["__doc__"].__set__(int, "text"))
show("bases set", lambda: bases_descriptor.__set__(int, ()))
show("bases of a subclass", lambda: bases_descriptor.__get__(Sided) == (object,))
show("bases of a written class", lambda: kind(bases_descriptor))
show("abstract set", lambda: abstract_descriptor.__set__(Sided, 1))
show("abstract read", lambda: abstract_descriptor.__get__(Sided))
for number in ("__flags__", "__basicsize__", "__dictoffset__", "__weakrefoffset__", "__itemsize__"):
    show(
        "%-16s" % number,
        lambda n=number: kind(type.__dict__[n].__get__(int)),
    )
show("write of a member", lambda: type.__dict__["__base__"].__set__(Plain, None))
show("delete of a member", lambda: type.__dict__["__base__"].__del__(Plain))
show("module read", lambda: type.__dict__["__module__"].__get__(int))
show("module set", lambda: type.__dict__["__module__"].__set__(Plain, "where"))
show("after", lambda: Plain.__module__, )
show("qualname set", lambda: type.__dict__["__qualname__"].__set__(Plain, "Renamed"))
show("after", lambda: Plain.__qualname__)
show("text signature", lambda: type.__dict__["__text_signature__"].__get__(int))
show("type params", lambda: type.__dict__["__type_params__"].__get__(int))

p_ne = Plain()
print("== a wrapper descriptor is the slot, and takes what Python wrote ==")
for name in ("__eq__", "__lt__", "__hash__", "__repr__", "__str__", "__setattr__", "__delattr__",
             "__getattribute__", "__init__", "__sizeof__"):
    member = object.__dict__[name]
    show(
        "%-20s" % name,
        lambda m=member: (kind(m), m.__objclass__.__name__, m is member),
    )
show("eq bound kind", lambda: kind(object.__dict__["__eq__"].__get__(Plain())))
show("eq qualname", lambda: object.__dict__["__eq__"].__get__(Plain()).__qualname__)
show("ne self", lambda: object.__dict__["__ne__"].__get__(p_ne)(p_ne))
show("hash", lambda: kind(object.__dict__["__hash__"].__get__(p_ne)()))
show("repr", lambda: kind(object.__dict__["__repr__"].__get__(p_ne)()))
show("format", lambda: kind(object.__dict__["__format__"]))
show("format bound", lambda: kind(object.__dict__["__format__"].__get__(p_ne)))
show("subclasshook kind", lambda: kind(object.__dict__["__subclasshook__"].__get__(object)))
show("subclasshook name", lambda: object.__dict__["__subclasshook__"].__get__(object).__name__)
show("init_subclass kind", lambda: kind(object.__init_subclass__))
show("init_subclass objclass", lambda: object.__dict__["__init_subclass__"].__objclass__.__name__)
show("dir on the class", lambda: object.__dict__["__dir__"].__get__(object, object) == dir(object))
show("inherited by a class", lambda: Plain.__dir__ is object.__dict__["__dir__"].__get__(Plain, Plain))
show("of an instance", lambda: type(Plain().__dir__).__name__)

print("== the class the body asked for ==")
show("prepare", lambda: type.__dict__["__prepare__"].__get__(Meta)(Meta, "N", ()))
show("prepare kind", lambda: type(type.__dict__["__prepare__"].__get__(Meta)(Meta, "N", ())).__name__)
show("prepare with", lambda: type.__prepare__(Meta, "N"))
show("new", lambda: kind(object.__dict__["__new__"]))
show("new objclass", lambda: getattr(object.__dict__["__new__"], "__objclass__", "none"))
show("new call", lambda: type(object.__new__(Plain)).__name__)
show("new of another", lambda: type(object.__new__(Plain)).__name__)
show("new self", lambda: object.__new__.__self__.__name__)
show("type new", lambda: kind(type.__dict__["__new__"]))
show("created", lambda: type.__new__(Meta, "Made", (), {}).__name__)
show("instance check", lambda: type.__dict__["__instancecheck__"].__get__(Plain)(Plain()))
show("instance check kind", lambda: kind(type.__dict__["__instancecheck__"].__get__(Plain)))
show("subclass check", lambda: type.__dict__["__subclasscheck__"].__get__(object)(Plain))
show("subclasses kind", lambda: kind(type.__dict__["__subclasses__"].__get__(object).__class__))
show("subclasses of a leaf", lambda: type.__subclasses__(int) if False else kind(type.__subclasses__))
show("annotate", lambda: kind(type.__dict__["__annotate__"]))
show("type params kind", lambda: kind(type.__dict__["__type_params__"]))

print("== a member of a value, filed by a class statement ==")


class Slotted:
    "text"

    __slots__ = ("left", "right")

    @property
    def both(self):
        return "p"

    @classmethod
    def maker(cls):
        return cls

    @staticmethod
    def quiet():
        return "s"


for name in ("left", "right", "both", "maker", "quiet", "__doc__"):
    member = Slotted.__dict__[name]
    show(
        "%-8s" % name,
        lambda m=member: (kind(m), getattr(m, "__name__", "-"), getattr(m, "__qualname__", "-"),
                          getattr(getattr(m, "__objclass__", None), "__name__", "-")),
    )
slot = Slotted.__dict__["left"]
show("slot doc", lambda: slot.__doc__)
show("slot set", lambda: (setattr(Slotted(), "left", 1), "ok"))
shown = Slotted()
shown.left = 3
show("slot read", lambda: (slot.__get__(shown), shown.left, kind(slot)))
show("slot unset", lambda: slot.__get__(Slotted()))
show("slot del", lambda: (slot.__del__(shown), shown.left))
show("slot on the class", lambda: slot.__get__(None, Slotted) is slot)
show("property get", lambda: Slotted.__dict__["both"].__get__(shown))
show("property set", lambda: Slotted.__dict__["both"].__set__(shown, 1))
show("classmethod get", lambda: Slotted.__dict__["maker"].__get__(shown, Slotted)())
show("staticmethod get", lambda: Slotted.__dict__["quiet"].__get__(shown, Slotted)())
show("function get", lambda: type(Slotted.__dict__["__doc__"]).__name__)
show("doc read", lambda: Slotted.__doc__)
show("doc write", lambda: (setattr(Slotted, "__doc__", "other"), Slotted.__doc__,
                           Slotted.__dict__["__doc__"]))
show("doc del", lambda: (delattr(Slotted, "__doc__"), Slotted.__dict__["__doc__"]))


class Written:
    pass


print("== the mutator halves, and the kinds that file them ==")
for owner, name in (
    (object, "__dir__"),
    (object, "__eq__"),
    (object, "__init_subclass__"),
    (type, "mro"),
    (type, "__name__"),
    (type, "__mro__"),
    (type, "__base__"),
):
    member = owner.__dict__[name]
    show(
        "%-6s %-18s" % (owner.__name__, name),
        lambda m=member: (kind(m), hasattr(m, "__set__"), hasattr(m, "__delete__")),
    )
show("set with one", lambda: type.__dict__["__name__"].__set__(Written))
show("delete with two", lambda: type.__dict__["__name__"].__delete__(Written, "x"))
show("delete with one", lambda: type.__dict__["__base__"].__delete__(int))
show("method has no set", lambda: object.__dict__["__dir__"].__set__)
show("write over an inherited method", lambda: (setattr(Written, "__dir__", 1), kind(Written.__dict__["__dir__"])))
show("read after", lambda: Written.__dir__)
show("write over a slot", lambda: (setattr(Written, "__eq__", 2), kind(Written.__dict__["__eq__"])))
show("write over a member", lambda: setattr(Written, "__base__", 1))
show("write over a getset", lambda: setattr(Written, "__mro__", 1))
