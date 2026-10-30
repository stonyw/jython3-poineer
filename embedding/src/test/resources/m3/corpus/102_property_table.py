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


def get(self):
    "the getter's own sentence"
    return 7


def docless(self):
    return 8


def setx(self, value):
    pass


def drop(self):
    pass


class Holds:
    value = property(get)
    both = property(get, setx)
    gone = property(get, setx, drop)
    plain = property()


class SaysAbstract:
    pass


SaysAbstract.__isabstractmethod__ = True


# The fifteen names `property` keeps for itself, measured against CPython 3.14.8.
#
# `property` lives in Objects/descrobject.c beside the five descriptor kinds that landed in slice 50, and its
# dictionary is filled by the same walk: `add_tp_new_wrapper` files `__new__` before `type_ready_fill_dict`
# runs at all, then `add_operators` puts the four slot wrappers in the order `slotdefs` lists them, then
# `property_methods`, `property_members` and `property_getsetlist` follow. Two of the four wrappers are the
# same slot copied twice over — `tp_descr_set` is filed once as `__set__` and once as `__delete__`, the second
# with nothing handed over — which is the whole of why a property outranks an instance dictionary even when it
# holds no setter. There is no `__repr__` row and no `__call__` row: the struct fills neither slot, so
# `repr(p)` is `object`'s and `p()` is a complaint about the type.
#
# The four method rows are two families wearing one shape. `getter`, `setter` and `deleter` are `METH_O` with
# a plain sentence beside them and no clinic table, so their `__text_signature__` is cut out of the flag;
# `__set_name__` is `METH_VARARGS` whose `ml_doc` was written by hand in the clinic shape, so its sentence and
# its table both come out of the text. `__set_name__` is also the only row of the fifteen that counts in its
# own words rather than through a parser: the C function compares the argument tuple against two and says
# `__set_name__() takes 2 positional arguments but N were given` bare, while the count of a keyword call names
# the row as Python reads it.
#
# The four members are one `_Py_T_OBJECT` offset each, and the first three are `Py_READONLY`: `fget`, `fset`
# and `fdel` refuse both the write and the delete as `readonly attribute`. The fourth is `__doc__`, filed with
# flag `0` and no sentence, which makes it writable and deletable — and what `del p.__doc__` leaves behind is
# `None`, because a null pointer read through a `_Py_T_OBJECT` answers `None` rather than complaining. That is
# also why `property.__doc__` is not this row: a class's own `__doc__` is read through `type`, which hands back
# the type's `tp_doc` — the 750 characters of `property_init`'s clinic text under the signature block
# `_PyType_DocWithoutSignature` throws away — as a fresh string every time it is asked.
#
# The two getsets are the name and the flag. `__name__` is `property_name`: the name `__set_name__` was
# handed, or else the getter's own `__name__` and nothing else, so a property with no getter and no name is a
# refusal rather than `None` — and deleting the name does not remove the name, it uncovers the fallback under
# it. `__isabstractmethod__` asks the three functions in order and files no setter, so its write and its delete
# both say the field is not writable in the descriptor's own words.
#
# `property_init` is the only route to the five fields, which makes `p.__init__(f)` a refill of the same object
# rather than a change of one half: it empties the docstring and the name before it fills them again, and takes
# the docstring from `doc` or, when that says nothing, from the getter — raising the flag that decides what a
# later copy does. `property_copy` keeps a sentence a person wrote and re-asks the getter for one it had
# borrowed, so `property(get).setter(f)` follows the new getter while
# `property(get, doc='x').setter(f)` keeps `'x'`.
#
# Two things are left aside: a class that derives from `property`, whose `tp_init` writes `__doc__` into the
# instance dictionary instead of the field and which this runtime refuses to build at all, and `repr` of a
# property value — the reference writes the address it lives at, this one an object count — so wherever a case
# makes or reads back a property it prints the type's name rather than the value.


for name in ("__new__", "__get__", "__set__", "__delete__", "__init__"):
    row = property.__dict__[name]
    print("==", name, type(row).__name__)
    if name == "__new__":
        print("   repr", "address-bearing in the reference")
    else:
        print("   repr", repr(row)[:60])
    print("   doc", repr(row.__doc__))
    print("   text", said(lambda: row.__text_signature__))
    print("   name", repr(row.__name__), "| qualname", repr(row.__qualname__))
    print("   objclass", said(lambda: row.__objclass__))
    print("   none", said(lambda: row()))
    print("   junk", said(lambda: row(1, 2, 3)))
    print("   kw", said(lambda: row(1, x=2)))

print("== the dictionary")
print("   vars", [(n, type(v).__name__) for n, v in vars(property).items()])
print("   len", len(vars(property)), len(dir(property)))
print("   dir", sorted(dir(property)))
print("   doc", type(property.__doc__).__name__, len(property.__doc__))
print("   doc row", type(vars(property)["__doc__"]).__name__,
      "| is row", property.__doc__ is vars(property)["__doc__"],
      "| twice", property.__doc__ is property.__doc__,
      "| equal", property.__doc__ == vars(property)["__doc__"])
print("   no repr row", "__repr__" in vars(property), "| no call row", "__call__" in vars(property),
      "| no dict row", "__dict__" in vars(property))

print("== the four method rows")
for name in ("getter", "setter", "deleter", "__set_name__"):
    row = property.__dict__[name]
    print("   ", name, type(row).__name__, "|", repr(row)[:60])
    print("      doc", repr(row.__doc__))
    print("      text", said(lambda: row.__text_signature__))
    print("      name", repr(row.__name__), "| qualname", repr(row.__qualname__))
    print("      objclass", said(lambda: row.__objclass__ is property))
    print("      bare", said_kind(lambda: row(property(get), get)))
    print("      none", said(lambda: row()))
    print("      wrong", said(lambda: row("text", get)))
    print("      wrong kw", said(lambda: row("text", x=get)))

print("== made empty, then filled")
empty = property.__new__(property)
print("   new fget", repr(empty.fget), "| fset", repr(empty.fset), "| fdel", repr(empty.fdel))
print("   new doc", repr(empty.__doc__), "| name", said(lambda: empty.__name__))
print("   new abstract", said(lambda: empty.__isabstractmethod__))
print("   new get", said_kind(lambda: empty.__get__(None, int)))
print("   new dict", said(lambda: empty.__dict__))
print("   new junk", said(lambda: property.__new__(int)))
print("   new none", said(lambda: property.__new__()))
print("   new kw", said_kind(lambda: property.__new__(property, x=1)))

named = property(get)
print("== the three functions and the docstring")
print("   doc from getter", repr(named.__doc__), "| fget", said(lambda: named.fget is get))
print("   name from getter", said(lambda: named.__name__))
print("   row call", said(lambda: property.__dict__["fget"](named)))
for name in ("fget", "fset", "fdel", "__doc__"):
    row = property.__dict__[name]
    print("   ", name, type(row).__name__, "|", repr(row)[:52])
    print("      doc", repr(row.__doc__), "| name", repr(row.__name__),
          "| qualname", repr(row.__qualname__), "| objclass", said(lambda: row.__objclass__))
    print("      read", said(lambda: getattr(Holds().plain, name)))
    print("      write", said(lambda: setattr(row, "x", 1)))

print("== the writable one")
print("   fset write", said(lambda: setattr(named, "fset", setx)))
print("   fset delete", said(lambda: builtins.delattr(named, "fset")))
print("   fdel now", repr(named.fdel))
print("   doc write", said(lambda: setattr(named, "__doc__", "written")))
print("   doc now", repr(named.__doc__))
print("   doc delete", said(lambda: builtins.delattr(named, "__doc__")))
print("   doc after", repr(named.__doc__))
print("   delete twice", said(lambda: builtins.delattr(named, "__doc__")))

print("== the two getsets")
print("   name read", said(lambda: named.__name__))
print("   name write", said(lambda: setattr(named, "__name__", "given")))
print("   name now", said(lambda: named.__name__))
print("   name delete", said(lambda: builtins.delattr(named, "__name__")))
print("   name after delete", said(lambda: named.__name__))
print("   abstract write", said(lambda: setattr(named, "__isabstractmethod__", True)))
print("   abstract delete", said(lambda: builtins.delattr(named, "__isabstractmethod__")))
for name in ("__name__", "__isabstractmethod__"):
    row = property.__dict__[name]
    print("   ", name, type(row).__name__, "|", repr(row)[:52])
    print("      doc", repr(row.__doc__), "| qualname", repr(row.__qualname__),
          "| objclass", said(lambda: row.__objclass__))
    print("      through", said(lambda: row.__get__(named)))
    print("      wrong", said(lambda: row.__get__("text")))

print("== the abstract flag over the three functions")
for target in (get, SaysAbstract, docless):
    print("   around", said(lambda: property(target, target, target).__isabstractmethod__))
print("   setter only", said(lambda: property(None, SaysAbstract).__isabstractmethod__))
print("   deleter only", said(lambda: property(None, None, SaysAbstract).__isabstractmethod__))

print("== the four arguments")
print("   all positional", said_kind(lambda: property(get, setx, drop, "written")))
print("   too many", said(lambda: property(get, get, get, get, get)))
print("   all keyword", said(lambda: property(fget=get, fset=setx, fdel=None, doc="all").__doc__))
print("   unknown", said(lambda: property(get, nothing=1)))
print("   not a function", said_kind(lambda: property("text")))
print("   init on value", said(lambda: named.__init__(get, setx, drop, "again")))
print("   init after", repr(named.__doc__), said(lambda: named.__name__))
print("   init count", said(lambda: named.__init__(get, get, get, get, get)))
print("   init doc only", said(lambda: named.__init__(doc="x")))
print("   doc now", repr(named.__doc__), "| fget", repr(named.fget))

print("== what a copy keeps")
plain = property(get)
print("   doc from getter", repr(plain.__doc__))
moved = plain.getter(docless)
print("   after .getter(docless)", repr(moved.__doc__), "| fget is docless", moved.fget is docless)
print("   getter of getter", said(lambda: plain.getter(get, setx)))
print("   getter keyword", said(lambda: plain.getter(getter=get)))
kept = property(get, None, None, "explicit").setter(setx)
print("   explicit doc kept", repr(kept.__doc__))
sideways = plain.setter(setx)
print("   after .setter doc", repr(sideways.__doc__), "| name", said(lambda: sideways.__name__))
print("   with name", said(lambda: property(plain.getter(docless)).__name__))
print("   copy is new object", plain is moved, "| type", said_kind(lambda: moved))
print("   three halves", [said_kind(lambda: getattr(plain, n)) for n in ("getter", "setter", "deleter")])

print("== the descriptor half")
print("   class read", said_kind(lambda: Holds.value))
print("   instance read", said(lambda: Holds().value))
print("   get none owner", said_kind(lambda: plain.__get__(None, int)))
print("   get none none", said(lambda: plain.__get__(None, None)))
print("   get missing", said(lambda: plain.__get__()))
print("   get three", said(lambda: plain.__get__(None, int, int)))
print("   get keyword", said(lambda: plain.__get__(None, owner=int)))
print("   get string", said(lambda: property().__get__("text")))
print("   empty read", said(lambda: Holds().plain))
print("   bare set through row", said(lambda: property.__set__(Holds.both, Holds(), 1)))
print("   bare delete through row", said(lambda: property.__delete__(property(get), Holds())))
print("   set short", said(lambda: Holds.both.__set__(Holds())))
print("   delete long", said(lambda: Holds.both.__delete__(Holds(), 1)))
print("   set keyword", said(lambda: Holds.both.__set__(Holds(), 1, x=2)))

print("== a class that holds one")
print("   write", said(lambda: setattr(Holds(), "value", 1)))
print("   delete", said(lambda: builtins.delattr(Holds(), "value")))
print("   both write", said(lambda: setattr(Holds(), "both", 1)) == "ok None")
print("   both now", said(lambda: Holds().both))
print("   shadow", said(lambda: setattr(Holds(), "gone", 1)))
print("   gone delete", said(lambda: builtins.delattr(Holds(), "gone")))
print("   set_name through row", said(lambda: plain.__set_name__(Holds, "two")))
print("   set_name now", said(lambda: plain.__name__))
print("   set_name one", said(lambda: plain.__set_name__(Holds)))
print("   set_name three", said(lambda: plain.__set_name__(Holds, "three", 4)))
print("   set_name keyword", said(lambda: plain.__set_name__(owner=Holds, name="kw")))
print("   set_name bare", said(lambda: property.__dict__["__set_name__"]()))
