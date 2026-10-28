DESCRIPTOR = type(bytes.hex)
WRAPPER = type(object.__init__)
CLASSMETHOD = type(object.__dict__["__subclasshook__"])
BUILTIN = type(len)

# The rows the class system files, name by name. FLAGGED names are left out of the sweeps: those are the
# rows registered as METH_NOARGS or METH_O|METH_CLASS, whose table CPython builds out of the flag rather
# than reading it out of a text, and only their sentences are measured here. NOT_SLOT names belong to a
# value type's own method table rather than to slotdefs — `list.__getitem__`, `dict.__contains__`,
# `dict.__getitem__`, `set.__contains__` and `frozenset.__contains__` are named in that table's file.
FLAGGED = [
    "__class_getitem__", "__init_subclass__", "__reversed__", "copy", "items", "keys", "values",
    "__subclasshook__",
]
NOT_SLOT = ["__getitem__", "__contains__"]

CLASS_SYSTEM = [object, type, type(object.__dict__), type(len)]
EXCEPTIONS = [
    BaseException, Exception, OSError, AttributeError, ImportError, NameError, StopIteration,
    SystemExit, SyntaxError, BaseExceptionGroup, ExceptionGroup,
]
# The slots a value type carries, which is every row of these dictionaries that slotdefs wrote.
SLOTS = [
    "__add__", "__and__", "__bool__", "__buffer__", "__contains__", "__delattr__", "__delitem__",
    "__divmod__", "__eq__", "__float__", "__floordiv__", "__ge__", "__getattribute__", "__getitem__",
    "__gt__", "__hash__", "__iadd__", "__iand__", "__index__", "__init__", "__int__", "__invert__",
    "__ior__", "__isub__", "__iter__", "__ixor__", "__le__", "__len__", "__lshift__", "__lt__",
    "__mod__", "__mul__", "__ne__", "__neg__", "__new__", "__or__", "__pos__", "__pow__", "__radd__",
    "__rand__", "__rdivmod__", "__repr__", "__rfloordiv__", "__rlshift__", "__rmod__", "__rmul__",
    "__ror__", "__rpow__", "__rrshift__", "__rshift__", "__rsub__", "__rtruediv__", "__rxor__",
    "__setattr__", "__setitem__", "__str__", "__sub__", "__truediv__", "__xor__",
]
VALUE_TYPES = [bytes, str, list, tuple, int, bool, float, dict, set, frozenset]

# An instance of a written class, because this runtime cannot call `object` itself and `object()` would
# then be the one line of the sweep that is not a read of the same row.
class Plain:
    pass


VALUE = Plain()


def sweep(kinds, names=None):
    for kind in kinds:
        for name in sorted(vars(kind)):
            if names is not None and name not in names:
                continue
            if name in FLAGGED or (names is not None and name in NOT_SLOT):
                continue
            value = vars(kind)[name]
            if isinstance(value, (DESCRIPTOR, WRAPPER, CLASSMETHOD, BUILTIN)):
                print(
                    kind.__name__, "|", name, "|", type(value).__name__, "|",
                    repr(value.__text_signature__), "|", repr(value.__doc__))


def refuses(label, fn):
    # Only the kind of the refusal is printed: the sentence each runtime writes for an unwritable
    # attribute is a deviation of its own, named in PLAN.md.
    try:
        fn()
        print(label, "-> wrote it")
    except Exception as e:
        print(label, "->", type(e).__name__)


def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "->", type(e).__name__, e)


print("== the four dictionaries of the class system ==")
sweep(CLASS_SYSTEM)

print("== the exception family ==")
sweep(EXCEPTIONS)

print("== the slots of the value types ==")
sweep(VALUE_TYPES, SLOTS)

print("== one text, two readers ==")
for bound in [(1).__lt__, "".__add__, [].__iter__, VALUE.__format__, (7).__bool__,
              VALUE.__dir__, (7).__index__]:
    print(type(bound).__name__, "|", bound.__name__, "|", repr(bound.__text_signature__), "|",
          repr(bound.__doc__))

print("== the sentence a bound row and its dictionary row share ==")
for kind, name, value in [(int, "__lt__", (1).__lt__), (list, "__iter__", [].__iter__),
                          (str, "__add__", "".__add__), (object, "__format__", VALUE.__format__),
                          (object, "__dir__", VALUE.__dir__)]:
    show("%s.%s shares" % (kind.__name__, name),
         lambda v=value, k=kind, n=name: v.__doc__ == vars(k)[n].__doc__)
    show("%s.%s table" % (kind.__name__, name),
         lambda v=value, k=kind, n=name: v.__text_signature__ == vars(k)[n].__text_signature__)

print("== the rows that name no sentence ==")
for kind, names in [(BaseException, ["__reduce__", "__setstate__"]), (OSError, ["__reduce__"]),
                    (AttributeError, ["__getstate__", "__reduce__"]),
                    (ImportError, ["__reduce__"]), (type(len), ["__reduce__"]),
                    (BaseExceptionGroup, ["derive", "split", "subgroup"])]:
    for name in names:
        row = vars(kind)[name]
        print(kind.__name__, "|", name, "|", type(row).__name__, "|",
              repr(row.__text_signature__), "|", repr(row.__doc__))

print("== the ten rows whose table CPython builds out of a flag ==")
for kind, names in [(object, ["__init_subclass__", "__subclasshook__"]),
                    (type(object.__dict__), ["keys", "values", "items", "copy", "__reversed__",
                                             "__class_getitem__"]),
                    (list, ["__class_getitem__"]), (BaseExceptionGroup, ["__class_getitem__"])]:
    for name in names:
        row = vars(kind)[name]
        print(kind.__name__, "|", name, "|", type(row).__name__, "|",
              repr(row.__doc__.split("\n")[0]))

print("== the table is a read, not a field ==")
refuses("write object.__lt__.__doc__", lambda: setattr(object.__lt__, "__doc__", "x"))
refuses("delete object.__lt__.__doc__", lambda: delattr(object.__lt__, "__doc__"))
refuses("write int.__lt__.__text_signature__", lambda: setattr(int.__lt__, "__text_signature__", "()"))
show("the row is the same object", lambda: vars(object)["__lt__"] is object.__dict__["__lt__"])
show("a second read of the table", lambda: getattr(int, "__lt__").__text_signature__
     == int.__lt__.__text_signature__)

print("== what the text does not change ==")
show("repr of a slot wrapper", lambda: repr(object.__lt__))
show("repr of a method descriptor", lambda: repr(object.__dir__))
show("__name__", lambda: VALUE.__format__.__name__)
show("__qualname__", lambda: VALUE.__format__.__qualname__)
show("__objclass__", lambda: VALUE.__format__.__objclass__.__name__)
show("new__self__", lambda: type(object.__new__.__self__).__name__)
show("is callable", lambda: callable(object.__dir__))
show("dir is sorted", lambda: dir(object) == sorted(dir(object)))
show("hash is stable", lambda: [hash(object.__lt__)] == [hash(object.__lt__)])
show("sizeof answers an int", lambda: isinstance(VALUE.__sizeof__(), int))
show("index answers seven", lambda: (7).__index__() == 7)
show("bool answers true", lambda: bool((7).__bool__()))
show("dir answers the same list twice", lambda: VALUE.__dir__() == VALUE.__dir__())
show("bound and unbound dir answer alike", lambda: object.__dir__(VALUE) == VALUE.__dir__())
show("format of an object", lambda: VALUE.__format__("") == repr(VALUE))
show("str of an object", lambda: type(VALUE.__str__()).__name__)
show("repr of an object", lambda: type(VALUE.__repr__()).__name__)
show("getstate answers", lambda: type(VALUE.__getstate__()).__name__)
show("add_note wants one", lambda: BaseException().add_note("x") is None)
show("with_traceback answers the value", lambda: isinstance(
    BaseException().with_traceback(None), BaseException))
show("mro of a class", lambda: [k.__name__ for k in int.mro()])
show("prepare", lambda: type.__prepare__.__self__.__name__)
show("instancecheck", lambda: type.__instancecheck__(type, int))
show("subclasscheck", lambda: type.__subclasscheck__(type, int))
show("and of two sets", lambda: isinstance({1}.__and__({1, 2}), set))
show("len of a list", lambda: [].__len__() == 0)
show("getitem of a list", lambda: [3].__getitem__(0) == 3)
show("setitem of a list", lambda: [].__setitem__(0, 1))
show("buffer of an object", lambda: hasattr(object, "__buffer__"))
