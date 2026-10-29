DESCRIPTOR = type(int.__dict__["bit_length"])
WRAPPER = type(object.__dict__["__init__"])
CLASSMETHOD = type(object.__dict__["__subclasshook__"])
GETSET = type(BaseException.__dict__["args"])
MEMBER = type(type.__dict__["__basicsize__"])
KINDS = (DESCRIPTOR, WRAPPER, CLASSMETHOD, GETSET, MEMBER)
ROWS = [int.__dict__["bit_length"], object.__dict__["__init__"],
        object.__dict__["__subclasshook__"], BaseException.__dict__["args"],
        type.__dict__["__basicsize__"]]
NAMES = ("__repr__", "__get__", "__set__", "__delete__", "__call__", "__reduce__")


def said(action):
    try:
        return "ok " + repr(action())[:96]
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:96]


def said_kind(action):
    try:
        return "ok " + type(action()).__name__
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:96]


# The five dictionaries the `PyDescrObject` family keeps for itself, measured against CPython 3.14.8.
#
# Four C sources fill these nine, nine, eight, eight and nine names, in an order a Python read cannot show.
# `type_ready_fill_dict` (Objects/typeobject.c) runs `add_operators` first — the walk of `slotdefs` that files
# each filled pointer as a `wrapper_descriptor` — and only then `type_add_methods` (`descr_methods`:
# `__reduce__`), `type_add_members` (`descr_members`: `__objclass__`, `__name__`) and `type_add_getset` (the
# kind's own getset list). One `slotdefs` pointer is two names: `__set__` and `__delete__` are two entries over
# the same `tp_descr_set`, the first handed to `wrap_descr_set` with its own name and the second to
# `wrap_descr_delete` with a NULL. So `method_descriptor` and `wrapper_descriptor` hold nine names each,
# `classmethod_descriptor` eight because it files no `tp_methods`, and `getset_descriptor` eight because it
# files neither `tp_methods` nor `tp_call`; `member_descriptor` holds the nine of those eight plus pickle's row.
# The two writing kinds stop their getset list at `__qualname__`, where the other three carry on to
# `__text_signature__`.
#
# The sentence a kind answers for itself is `type_get_doc` (Objects/typeobject.c:1950) taking its second half:
# a static type with no `tp_doc` reads its own dictionary's `__doc__` entry and runs it through that entry's
# descriptor, and a getset row run with no instance is the row, so all five kinds answer the `getset_descriptor`
# they file rather than a sentence — and `type`, which has both a `tp_doc` and a row of its own, answers the
# 79 characters of `type_doc` instead. The same step reaches a class Python wrote: `class Held` below holds a
# `property` under the name, reads the property back off the class and reads what the property says off a value.
#
# A row read with no instance, `__get__(None, owner)`, is the row for four of the five kinds; the fifth,
# `classmethod_descriptor`, binds the owner it was handed instead, so `is` says `False` there and `True` for
# the other four. With both halves of the call left `None` all five refuse by name, because `wrap_descr_get`
# turns each `None` into not-given and then finds nothing to read the row through.
#
# Two neighbouring dictionaries are left to a later slice, and one of their names is not read here at all:
# a `method_descriptor` bound to a value answers `builtin_function_or_method` in the reference and `method`
# here, so the type of a bound row is only asked where both runtimes name it the same — `method-wrapper`,
# behind the three slot rows — and `staticmethod`, which files thirteen names of its own, is reached by
# nothing below.
for kind in KINDS:
    print("==", kind.__name__)
    print("   vars", list(vars(kind)))
    print("   rows", [(n, type(r).__name__) for n, r in vars(kind).items()])
    print("   dir", sorted(dir(kind)))
    print("   doc", type(kind.__doc__).__name__, kind.__doc__ is vars(kind)["__doc__"])
    print("   name", kind.__name__, "|", kind.__qualname__, "|", kind.__module__)
    print("   qualname of doc row", kind.__doc__.__qualname__)
    print("   objclass of doc row", kind.__doc__.__objclass__ is kind)
    print("   text", said(lambda: repr(kind.__text_signature__)))
    print("   len", len(vars(kind)))

for kind, sample in zip(KINDS, ROWS):
    print("==", kind.__name__, "as a value")
    for name in NAMES:
        if name not in vars(kind):
            print("   ", name, "| absent")
            continue
        at = getattr(sample, name)
        if type(vars(kind)[name]).__name__ == "wrapper_descriptor":
            print("   ", name, "bound", type(at).__name__)
        print("   ", name, "none", said(lambda: at()))
        print("   ", name, "junk", said(lambda: at(1, 2, 3, 4)))
        print("   ", name, "kw", said(lambda: at(1, x=2)))
    print("    reduce", said(lambda: sample.__reduce__()))
    print("    pair", said(lambda: sample.__reduce__()[1]))

print("== five rows of five kinds")
for row in ROWS:
    kind = type(row)
    owner = row.__objclass__
    print("   ", kind.__name__, "|", repr(row)[:60])
    print("    name", row.__name__, "|", row.__qualname__, "|", owner.__name__)
    print("    doc", repr(row.__doc__)[:70])
    print("    text", said(lambda: repr(row.__text_signature__)))
    print("    get none owner", row.__get__(None, owner) is row)
    print("    get none none", said(lambda: row.__get__(None, None)))
    print("    get none", said(lambda: row.__get__(None)))
    print("    get str", said_kind(lambda: row.__get__("no", None)))
    print("    get owner", said_kind(lambda: row.__get__(owner)))
    print("    get four", said(lambda: row.__get__(1, 2, 3, 4)))
    print("    called", said(lambda: row()))
    print("    reduce", said(lambda: row.__reduce__()))
    print("    write", [said(lambda n=n: setattr(row, n, "x")) for n in
                        ("__doc__", "__qualname__", "__name__", "__text_signature__", "zzz", "__dict__")])
    print("    delete", [said(lambda n=n: delattr(row, n)) for n in
                         ("__doc__", "__qualname__", "__name__", "__text_signature__", "zzz", "__dict__")])

print("== type_doc")
print("   ", len(type.__doc__), "|", repr(type.__doc__))
print("   ", type.__doc__ is vars(type)["__doc__"], "|", type(vars(type)["__doc__"]).__name__)
print("   read twice equal", type.__doc__ == type.__doc__)


class Bare:
    pass


class Said:
    "A sentence."


class Held:
    __doc__ = property(lambda self: "the value reads this")


print("== a class three ways")
for kind in (Bare, Said, Held):
    row = vars(kind).get("__doc__")
    print("   ", kind.__name__, "|", type(row).__name__, "|", kind.__doc__ is row)
    # Only a sentence is repr-ed here: a class holding a descriptor under the name reads that descriptor
    # back, and its repr counts an address this runtime has no way of naming the same.
    print("    class", repr(kind.__doc__)[:70] if isinstance(kind.__doc__, str) else type(kind.__doc__).__name__)
    print("    value", repr(kind().__doc__)[:70])
print("    inherited", repr(type("Child", (Said,), {}).__doc__))
print("    delete", said(lambda: delattr(Held, "__doc__")))
print("    write", said(lambda: setattr(Bare, "__doc__", "now said")))
print("    after", repr(Bare.__doc__), "|", repr(Said.__doc__))
