import builtins


def said(action):
    try:
        return "ok " + repr(action())[:78]
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:78]


def said_kind(action):
    try:
        return "ok " + type(action()).__name__
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:78]


def said_masked(action):
    """The repr a value answers, with the address the reference writes beside it taken back out."""
    try:
        made = action()
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:78]
    text = made if isinstance(made, str) else repr(made)
    before, at, after = text.rpartition(" at ")
    return repr(before + ">") if at else repr(text)


def kinds():
    return [
        ("builtin_function_or_method", type(len)),
        ("method-wrapper", type((3).__add__)),
        ("method_descriptor", type(str.upper)),
        ("wrapper_descriptor", type(object.__dict__["__repr__"])),
        ("classmethod_descriptor", type(dict.__dict__["fromkeys"])),
        ("getset_descriptor", type(object.__dict__["__class__"])),
        ("member_descriptor", type(type.__dict__["__base__"])),
    ]


class Plain:
    pass


wrapper = (3).__add__
kind = type(wrapper)

# The sixteen names `method-wrapper` keeps for itself, and the seven kinds that cannot be built at all,
# measured against CPython 3.14.8.
#
# `_PyMethodWrapper_Type` lives in Objects/descrobject.c beside the five descriptor kinds of slice 50, and its
# dictionary is filled the same way: `add_operators` first, over the four slots this type fills, then
# `wrapper_methods`, `wrapper_members` and `wrapper_getsets`. The nine slot rows are `__repr__`, `__hash__`,
# `__call__` and the six halves of `__lt__` through `__ge__`, and every one of them runs over the value the
# wrapper holds rather than over the wrapper — `wrapper_call` hands `wp->self` to `wrapperdescr_raw_call`, so
# `wrapper.__call__(4)` is the same 7 as `wrapper(4)`. But only the call row answers about the value:
# `wrapper_repr` writes the name of the descriptor, the class of the value and the address the value lives at,
# `wrapper_hash` mixes the identity of the value with the pointer of the descriptor, and the six ordering rows
# share one C function that ranks nothing — `wrapper_richcompare` answers only `==` and `!=`, out of the
# descriptor and the value together, and declines everything else, which is why `wrapper < wrapper` complains
# about `<` while `wrapper.__lt__(wrapper)` hands back `NotImplemented`.
#
# There is no `__new__` row, and that absence is half of this slice. `PyWrapper_New` makes the wrapper from the
# descriptor and the value in C, and the static record files no `tp_new`, so `type_ready_set_new` — which looks
# for a null `tp_new` over a base of `object` on a type that is not built at runtime — marks the type
# `Py_TPFLAGS_DISALLOW_INSTANTIATION` and clears the slot again instead of reaching `add_tp_new_wrapper`. Six
# other static builtins are in the same position: `builtin_function_or_method` and the five descriptor kinds.
# All seven refuse whichever of the five routes a program takes — `kind()`, `kind(1, 2)`, `kind(x=1)`,
# `object.__new__(kind)` or `kind.__new__(kind)` — in the same words, because the slot is read before any
# argument is and `object_new` and `tp_new_wrapper` read it too. `property`, `staticmethod`, `classmethod`,
# `mappingproxy` and `str` fill the slot, so they keep the safety complaint instead, and `dict_keys` stands
# with the seven: it refuses the same ways, and is measured here by its refusals alone because this runtime has
# not filed the twenty-three rows of its own dictionary yet.
#
# `__reduce__` and `__self__` are the record's one `PyMethodDef` and its one `PyMemberDef`. The first names the
# value and the slot rather than the type and the slot, which is the difference from the `descr_methods` row the
# three unbound kinds file; the second is registered `Py_READONLY`, so writing and deleting both say
# `readonly attribute`, and it is the only row of the sixteen that reads a field of the struct rather than the
# answer of a function. The five getsets fill no setter either, and refuse in their own longer words.
#
# This type files no `tp_doc`, so `type_dict_set_doc` leaves the dictionary alone and the `__doc__` row that
# sits among the five getsets is what a read of `method-wrapper.__doc__` hands back: the `getset_descriptor`
# itself, the same object the dictionary holds.
#
# Three things are left aside: a class that derives from any of the seven, which CPython refuses as an
# unacceptable base and this runtime refuses as unimplemented; the `repr` of a wrapper, since the reference
# writes the address of the value it holds and this one an object count — so wherever a case reads one back it
# prints the type's name or masks what it is; and `mappingproxy`'s own creator, which this runtime reads as a
# mapping where `tp_new_wrapper` strips it as the class.

print("== the sixteen rows")
for name in kind.__dict__:
    row = kind.__dict__[name]
    print("   ", name, type(row).__name__)
    print("      doc", repr(row.__doc__), "| text", said(lambda: row.__text_signature__))
    print("      name", repr(row.__name__), "| qualname", repr(row.__qualname__))
    print("      objclass", said(lambda: row.__objclass__ is kind))

print("== the dictionary")
print("   vars", [(n, type(v).__name__) for n, v in vars(kind).items()])
print("   len", len(vars(kind)), len(dir(kind)))
print("   dir", sorted(dir(kind)))
print("   new row", "__new__" in vars(kind), "| dict row", "__dict__" in vars(kind))
print("   doc", type(kind.__doc__).__name__,
      "| is row", kind.__doc__ is vars(kind)["__doc__"],
      "| twice", kind.__doc__ is kind.__doc__)
print("   text", said(lambda: kind.__text_signature__))
print("   bases", [t.__name__ for t in kind.__bases__], [t.__name__ for t in kind.__mro__])

print("== the wrapper itself")
print("   name", repr(wrapper.__name__), "| qualname", repr(wrapper.__qualname__))
print("   objclass", said(lambda: wrapper.__objclass__ is int))
print("   self", repr(wrapper.__self__), "| doc", repr(wrapper.__doc__))
print("   text", repr(wrapper.__text_signature__))
print("   dict", said(lambda: wrapper.__dict__))
print("   get", said(lambda: wrapper.__get__))
print("   call", said(lambda: wrapper(4)), "| through row", said(lambda: kind.__dict__["__call__"](wrapper, 4)))
print("   reduce", said(lambda: wrapper.__reduce__()))
print("   reduce row", type(vars(kind)["__reduce__"]).__name__,
      "| bare", said(lambda: kind.__dict__["__reduce__"]()))
print("   reduce long", said(lambda: wrapper.__reduce__(1)))
print("   reduce kw", said(lambda: wrapper.__reduce__(x=1)))

print("== the nine slot rows run over a wrapper")
print("   repr", said_masked(lambda: kind.__dict__["__repr__"](wrapper)))
print("   repr twice", said(lambda: kind.__dict__["__repr__"](wrapper, wrapper)))
print("   repr wrong", said(lambda: kind.__dict__["__repr__"](3)))
print("   repr none", said(lambda: kind.__dict__["__repr__"]()))
print("   hash", said_kind(lambda: kind.__dict__["__hash__"](wrapper)))
print("   lt", said(lambda: kind.__dict__["__lt__"](wrapper, wrapper)))
print("   eq", said(lambda: kind.__dict__["__eq__"](wrapper, wrapper)))
print("   eq other", said(lambda: kind.__dict__["__eq__"](wrapper, 1)))
print("   ne", said(lambda: kind.__dict__["__ne__"](wrapper, 1)))
print("   eq wrong", said(lambda: kind.__dict__["__eq__"](1, 1)))
print("   read twice equal", wrapper == (3).__add__, "| same object", wrapper is (3).__add__)
print("   two values equal", wrapper == (4).__add__)
print("   less than", said(lambda: wrapper < wrapper))

print("== the one member and the five getsets")
for name in ("__self__", "__objclass__", "__name__", "__qualname__", "__doc__", "__text_signature__"):
    row = kind.__dict__[name]
    print("   ", name, type(row).__name__, "| doc", repr(row.__doc__))
    print("      read", said(lambda: row.__get__(wrapper)))
    print("      none", said(lambda: row.__get__(None, kind)))
    print("      wrong", said(lambda: row.__get__(3)))
    print("      write", said(lambda: setattr(wrapper, name, 1)))
    print("      delete", said(lambda: builtins.delattr(wrapper, name)))
    print("      text", said(lambda: row.__text_signature__))

print("== a wrapper of another value")
shown = print.__repr__
print("   print.__repr__", said(lambda: shown.__reduce__()))
print("   qualname", repr(shown.__qualname__), "| objclass", said(lambda: shown.__objclass__))
print("   self", type(shown.__self__).__name__, "| doc", repr(shown.__doc__))
print("   called", said(lambda: shown(1, 2)))
print("   through call row", said(lambda: kind.__dict__["__call__"](shown)))

print("== the seven kinds with no creator")
for label, kind_of in kinds():
    print("   ", label, len(vars(kind_of)))
    print("      call", said(lambda: kind_of()))
    print("      junk", said(lambda: kind_of(1, 2)))
    print("      keyword", said(lambda: kind_of(x=1)))
    print("      object new", said(lambda: object.__new__(kind_of)))
    print("      own new", said(lambda: kind_of.__new__(kind_of)))
    print("      new of junk", said(lambda: kind_of.__new__("text")))
    print("      other new", said(lambda: kind_of.__new__(int)))

print("== the kinds that do fill a creator")
views = type({}.keys())
for label, kind_of in (
    ("property", property),
    ("staticmethod", staticmethod),
    ("classmethod", classmethod),
    ("mappingproxy", type(vars(int))),
    ("str", str),
    ("Plain", Plain),
):
    print("   ", label, len(vars(kind_of)))
    print("      object new", said_kind(lambda: object.__new__(kind_of)))
    # `mappingproxy.__new__(mappingproxy)` is left aside: this runtime's row reads its first argument as the
    # mapping, where `tp_new_wrapper` strips it as the class and hands the empty list to `mappingproxy_new`.
    if label != "mappingproxy":
        print("      own new", said_kind(lambda: kind_of.__new__(kind_of)))

print("== the views, whose own rows this runtime has not filed")
print("   object new", said(lambda: object.__new__(views)))
print("   own new", said(lambda: views.__new__(views)))
print("   other new", said(lambda: views.__new__(int)))

print("   new rows", [name for name, kind_of in kinds() if "__new__" in vars(kind_of)])
print("   has new", [said_kind(lambda: k.__new__) for k in
                    (property, staticmethod, classmethod, type(vars(int)), views)])
