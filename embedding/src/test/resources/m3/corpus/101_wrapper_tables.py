STATIC = staticmethod
CLASS = classmethod
KINDS = (STATIC, CLASS)
ROWS = ("__new__", "__repr__", "__call__", "__get__", "__init__", "__class_getitem__")
STORED = ("__func__", "__wrapped__", "__isabstractmethod__", "__dict__", "__annotations__", "__annotate__")


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


def doubled(action):
    try:
        return action() * 2
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:96]


class Abstract:
    @property
    def __isabstractmethod__(self):
        return True


class Holds:
    __isabstractmethod__ = True


class SaysNo:
    __isabstractmethod__ = None


class Bare:
    pass


class Hold:
    @staticmethod
    def s(x):
        return x * 2

    @classmethod
    def c(cls, x):
        return (cls.__name__, x * 2)


# The two dictionaries the wrapper objects keep for themselves, measured against CPython 3.14.8.
#
# Both live in Objects/funcobject.c and both are filled the same way: one `PyMemberDef` pair, one
# `PyGetSetDef` of four, one `PyMethodDef` of a single `METH_CLASS` row and the `tp_new`, `tp_init`,
# `tp_repr`, `tp_getattro` and `tp_descr_get` that `type_ready_fill_dict` walks into the dictionary ahead of
# them, in that order. `staticmethod` files thirteen names and `classmethod` twelve; the row the second one
# lacks is `tp_call`, which its own type table does not carry, so `C.c()` reaches the function with the class
# already prepended while `C.s()` reaches it bare.
#
# The two member rows, `__func__` and `__wrapped__`, are two `_Py_T_OBJECT Py_READONLY` entries over the one
# `wm_callable` field. They are not the same object — `PyMemberDescriptor_New` is called twice — and a
# `member_descriptor` compares by identity, so the two rows are unequal as well. Both write refuse with
# `readonly attribute` and both delete the same way, because `member_descriptor` files a setter that only
# answers `AttributeError` and `tp_descr_set` is reached for the write, not the delete.
#
# `__dict__` is `PyObject_GenericGetDict`: the first read creates the dictionary, which is why `vars()` on a
# value that was only looked at answers a real `dict` and why a value made by `tp_new` alone answers `{}`.
# Deleting it is refused. The four names a wrap copies — `__module__`, `__name__`, `__qualname__`, `__doc__`
# — are copied by `functools_wraps` with `PyObject_GetOptionalAttr` and `PyObject_SetAttr`, so a callable that
# has no name keeps the dictionary without it: `staticmethod(f)` lands four names, `staticmethod(Bare)` four
# and `staticmethod(5)` only `__doc__`, which the int answers from its own type. A name read back after the
# copy is read from the copy, not from the callable: renaming the function leaves `__name__` where it was.
#
# `__annotations__` and `__annotate__` go one step further than the four: `descriptor_get_wrapped_attribute`
# looks in the value's dictionary first, and when the name is not there reads it off the callable and *files
# what it found* in the dictionary. So a class that answers `{}` keeps `{}` after one read even when the class
# is annotated afterwards, and a callable that answers nothing leaves the dictionary untouched and the read
# raises. `__isabstractmethod__` is `_PyObject_IsAbstract(callable)`, which reads the name off the callable like
# any other attribute — so a class that holds `True` under it answers true, one that holds `None` answers
# false, and a callable that answers nothing at all is false rather than a complaint. The row files no setter,
# and the write of each of the four getsets says `... of 'staticmethod' objects is not writable`.
#
# Three names are left to later slices: `property`, whose fifteen rows are the next dictionary, the `method`
# rows that a bound classmethod reads, and the address in a function's `repr`, which this runtime does not
# name the same way. `staticmethod.__doc__` and `classmethod.__doc__` are transcribed whole below, because a
# type with a `tp_doc` files the sentence and nothing else reads it.
for kind in KINDS:
    print("==", kind.__name__)
    print("   vars", list(vars(kind)))
    print("   rows", [(n, type(r).__name__) for n, r in vars(kind).items()])
    print("   dir", sorted(dir(kind)))
    print("   len", len(vars(kind)), len(dir(kind)))
    print("   doc", type(kind.__doc__).__name__, kind.__doc__ is vars(kind)["__doc__"])
    print("   doc row", type(vars(kind)["__doc__"]).__qualname__ if "__doc__" in vars(kind) else "-")

for kind in KINDS:
    print("==", kind.__name__, "rows called bare")
    for name in ROWS:
        if name not in vars(kind):
            print("   ", name, "| absent")
            continue
        row = vars(kind)[name]
        # A `builtin_function_or_method` counts its address in the reference and not here.
        if name == "__new__":
            print("   ", name, type(row).__name__)
        else:
            print("   ", name, type(row).__name__, "|", repr(row)[:60])
        print("      none", said(lambda: row()))
        print("      junk", said(lambda: row(1, 2, 3, 4)))
        print("      kw", said(lambda: row(1, x=2)))
    for name in STORED:
        row = vars(kind)[name]
        print("   ", name, type(row).__name__, "|", repr(row)[:60])
        print("      text", said(lambda: repr(row.__text_signature__)))
        print("      call", said(lambda: row()))
        print("      doc", repr(row.__doc__))

print("== one value of each")
for kind in KINDS:
    one = kind(doubled)
    print("   ", kind.__name__, "vars", vars(one))
    print("    dir len", len(dir(one)))
    print("    func", said(lambda: one.__func__ is doubled))
    print("    wrapped", said(lambda: one.__wrapped__ is doubled))
    print("    two rows", vars(kind)["__func__"] is vars(kind)["__wrapped__"],
          vars(kind)["__func__"] == vars(kind)["__wrapped__"])
    print("    write", [said(lambda n=n: setattr(one, n, Bare)) for n in ("__func__", "__wrapped__")])
    print("    delete", [said(lambda n=n: delattr(one, n)) for n in ("__func__", "__wrapped__")])
    print("    after", sorted(vars(one)))

print("== the store")
for kind in KINDS:
    one = kind(doubled)
    print("   ", kind.__name__, "twice", one.__dict__ is one.__dict__, "| vars is dict", one.__dict__ is vars(one))
    print("    empty before", sorted(getattr(kind.__new__(kind), "__dict__")))
    print("    write", said(lambda: setattr(one, "__dict__", {"a": 1})))
    print("    after write", sorted(vars(one)))
    print("    delete", said(lambda: delattr(one, "__dict__")))
    print("    junk", said(lambda: setattr(one, "__dict__", 5)))
    print("    back", said(lambda: (setattr(one, "__dict__", {"b": 2}), sorted(vars(one)))))

print("== the two that read through")
for kind in KINDS:
    for target in (Bare, 5):
        one = kind(target)
        print("   ", kind.__name__, "around", type(target).__name__)
        print("      annots", said(lambda: one.__annotations__))
        print("      cached", "__annotations__" in vars(one))
        print("      annotate", said(lambda: one.__annotate__))
        print("      cached", "__annotate__" in vars(one))
        print("      vars", sorted(vars(one)))

print("== one read keeps what it found")
held = STATIC(Bare)
first = held.__annotations__
first["zz"] = 1
print("   same object", held.__annotations__ is first)
print("   kept", said(lambda: held.__annotations__))
Bare.__annotations__ = {"later": int}
print("   target now", said(lambda: Bare.__annotations__))
print("   held still", said(lambda: held.__annotations__))
print("   write", said(lambda: setattr(held, "__annotations__", {"b": 2})))
print("   after write", said(lambda: held.__annotations__))
print("   delete", said(lambda: delattr(held, "__annotations__")))
print("   after delete", said(lambda: held.__annotations__))
print("   junk", said(lambda: setattr(held, "__annotations__", 5)))

print("== the abstract flag")
for kind in KINDS:
    one = kind(doubled)
    print("   ", kind.__name__, "plain", one.__isabstractmethod__,
          "| instance", kind(Abstract()).__isabstractmethod__,
          "| class", kind(Holds).__isabstractmethod__,
          "| says no", kind(SaysNo).__isabstractmethod__,
          "| missing", kind(5).__isabstractmethod__,
          "| empty", kind(None).__isabstractmethod__)
    print("    write", said(lambda: setattr(one, "__isabstractmethod__", True)))
    print("    delete", said(lambda: delattr(one, "__isabstractmethod__")))

print("== the alias row")
for kind in KINDS:
    print("   ", kind.__name__, "one", said_kind(lambda: kind[int]))
    print("    args", said(lambda: kind[int].__args__))
    print("    two", said(lambda: kind[int, str].__args__))
    print("    row", type(kind.__class_getitem__).__name__,
          "|", type(vars(kind)["__class_getitem__"]).__name__)
    print("    self", said(lambda: kind.__class_getitem__.__self__ is kind))
    print("    func", said(lambda: kind.__class_getitem__.__func__))
    print("    name", said(lambda: kind.__class_getitem__.__name__))
    print("    qualname", said(lambda: kind.__class_getitem__.__qualname__))
    print("    doc", said(lambda: repr(kind.__class_getitem__.__doc__)))
    print("    bare", said(lambda: kind.__class_getitem__(kind)))
    print("    wrong", said(lambda: kind.__class_getitem__(kind, 1, 2, 3, 4)))
    print("    junk", said(lambda: kind.__class_getitem__(1, 2)))

print("== made empty, then filled")
for kind in KINDS:
    bare = kind.__new__(kind)
    print("   ", kind.__name__, "func", said(lambda: bare.__func__))
    print("    wrapped", said(lambda: bare.__wrapped__))
    print("    dict", said(lambda: bare.__dict__))
    print("    name", said(lambda: bare.__name__))
    print("    doc", said(lambda: bare.__doc__))
    print("    annots", said(lambda: bare.__annotations__))
    print("    abstract", said(lambda: bare.__isabstractmethod__))
    print("    call", said(lambda: bare()))
    print("    get", said_kind(lambda: bare.__get__(None, kind)))
    print("    init", said(lambda: bare.__init__(doubled)))
    print("    func now", said(lambda: bare.__func__ is doubled))
    print("    dict now", sorted(vars(bare)))
    print("    init junk", said(lambda: bare.__init__(doubled, 1, 2)))
    print("    init kw", said(lambda: bare.__init__(doubled, x=1)))
    print("    call now", said(lambda: bare(3)))

print("== new refused")
for kind in KINDS:
    print("   ", kind.__name__, "none", said(lambda: kind.__new__(kind)))
    print("    junk", said(lambda: kind.__new__(int)))
    print("    kw", said(lambda: kind.__new__(kind, x=1)))
    print("    two", said(lambda: kind.__new__(kind, doubled, 1)))

print("== bound through a class")
for name in ("s", "c"):
    row = Hold.__dict__[name]
    print("   ", name, type(row).__name__)
    print("    dict", sorted(vars(row)))
    print("    get none", said_kind(lambda: row.__get__(None, Hold)))
    print("    get obj", said_kind(lambda: row.__get__(Hold(), Hold)))
    print("    get str", said_kind(lambda: row.__get__("x", None)))
    print("    get both none", said_kind(lambda: row.__get__(None, None)))
    print("    class read", said_kind(lambda: getattr(Hold, name)))
    print("    instance read", said_kind(lambda: getattr(Hold(), name)))
    print("    class call", said(lambda: getattr(Hold, name)(3)))
    print("    instance call", said(lambda: getattr(Hold(), name)(3)))
    print("    through get", said(lambda: row.__get__(None, Hold)(3)))
    print("    func", said_kind(lambda: row.__func__))
    print("    wrong obj", said_kind(lambda: row.__get__(None, "x")))
    print("    wrong obj call", said(lambda: row.__get__(None, "x")(3)))
    print("    no args", said(lambda: row.__get__()))

print("== the sentence each kind carries")
for kind in KINDS:
    print("   ", kind.__name__, len(kind.__doc__), "|", repr(kind.__doc__))
    print("    fresh", kind.__doc__ is vars(kind)["__doc__"], "| twice", kind.__doc__ is kind.__doc__,
          "| equal", kind.__doc__ == vars(kind)["__doc__"])
    print("    row", type(vars(kind)["__doc__"]).__name__,
          "|", repr(kind.__dict__["__doc__"])[:40])
