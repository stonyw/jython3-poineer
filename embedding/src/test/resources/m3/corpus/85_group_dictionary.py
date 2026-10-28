"""The eleven names ``BaseExceptionGroup`` files and the three ``ExceptionGroup`` files beside them.

``BaseExceptionGroup`` is the only class of the family that writes two display slots, and it shows in the
dictionary: ``__repr__`` and ``__str__`` are both ``wrapper_descriptor``s, filed in that order because
``add_operators`` walks ``slotdefs`` and ``tp_repr`` comes before ``tp_str``. Every other class of the family
writes one or neither, so this is the one row set where ``repr()`` is a name the class owns rather than
something read off ``BaseException``. ``__new__`` sits ahead of both as a ``builtin_function_or_method``,
because ``ComplexExtendsException`` names ``BaseExceptionGroup_new`` for the ``tp_new`` slot and a creator
that is not the inherited one is copied into the dictionary before the methods are read.

Then ``__init__``, then the four names of ``BaseExceptionGroup_methods`` — ``__class_getitem__`` as a
``classmethod_descriptor``, and ``derive``, ``split`` and ``subgroup`` as ``method_descriptor``s — then the two
rows of ``BaseExceptionGroup_members``, then the sentence. Eleven names, thirty-nine in ``dir``.
``__class_getitem__`` is the only one of the four with a docstring that runs as a sentence: the collections
say "lists are generic over the type of their contents" in the lower case of a note, and this says
``Exception groups are generic over the type of their contained exceptions`` because the subject is a plural
noun rather than a type's name. Nine of the eleven names are descriptors and so answer an ``__objclass__`` of
their own; the creator is a static method already bound to its class and the sentence is a string, so neither
of the two has one to answer.

``ExceptionGroup`` files three names and forty-one in ``dir``, and the first of them is the reason it is not
like the rest of the family. ``create_exception_group_class`` hands ``builtins.ExceptionGroup`` to
``PyErr_NewException``, which takes the half before the last dot, writes it into the class dictionary as
``__module__``, and calls ``type()`` with the two bases — so this is a heap class rather than a static struct,
and it is the only class of the family whose dictionary holds a ``__module__`` outright. Reading
``BaseExceptionGroup.__module__`` answers ``builtins`` too, but from ``tp_name`` and not from a row: the name
is not in that dictionary at all. The other two rows are the weak reference list that ``type()`` gives a heap
class with no slots of its own and a ``__doc__`` that is ``None``, because nothing handed ``PyErr_NewException``
a sentence to write.

A read of ``ExceptionGroup.__str__`` finds the row one class up and hands back the very same object, while
``ExceptionGroup.__class_getitem__`` is a new bound function on every read: a ``wrapper_descriptor`` gives
back itself when a class asks, and a ``classmethod_descriptor`` builds a binding. What this case does not
print is the address in the ``repr`` of a ``__new__`` row, or the two fields themselves, which are the next
case.
"""


import builtins

GROUPS = ("BaseExceptionGroup", "ExceptionGroup")
ROWS = (
    "__new__",
    "__repr__",
    "__str__",
    "__init__",
    "__class_getitem__",
    "derive",
    "split",
    "subgroup",
    "message",
    "exceptions",
    "__doc__",
)


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def line(owned, name):
    value = owned[name]
    return "%-20s %-24s %-38s set=%-5s get=%-5s doc=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__set__"),
        hasattr(value, "__get__"),
        repr(getattr(value, "__doc__", "-")),
    )


for name in GROUPS:
    kind = getattr(builtins, name)
    print("==", name, len(kind.__dict__), len(dir(kind)))
    for row in list(kind.__dict__):
        # The last row is read as the value it holds rather than through `line()`, because the sentence of
        # `ExceptionGroup` is `None` and `None.__doc__` would print the docstring of a different type.
        if row == "__doc__":
            print("%-20s %-24s %s" % (row, type(kind.__dict__[row]).__name__, repr(kind.__dict__[row])))
        else:
            print(line(kind.__dict__, row))

show("group rows in order", lambda: list(BaseExceptionGroup.__dict__) == list(ROWS))
show("base doc", lambda: BaseExceptionGroup.__doc__)
show("group doc", lambda: ExceptionGroup.__doc__)
show("group bases", lambda: [kind.__name__ for kind in ExceptionGroup.__bases__])
show("group mro", lambda: [kind.__name__ for kind in ExceptionGroup.__mro__])
show("module row only on group", lambda: "__module__" in ExceptionGroup.__dict__)
show("module row not on base", lambda: "__module__" in BaseExceptionGroup.__dict__)
show("module reads anyway", lambda: BaseExceptionGroup.__module__)
show("module value", lambda: ExceptionGroup.__dict__["__module__"])
show("module type", lambda: type(ExceptionGroup.__dict__["__module__"]).__name__)
show("weakref row", lambda: type(ExceptionGroup.__dict__["__weakref__"]).__name__)
show("weakref doc", lambda: ExceptionGroup.__dict__["__weakref__"].__doc__)
show("qualname row filed", lambda: "__qualname__" in ExceptionGroup.__dict__)

show("str row is not the base's", lambda: BaseExceptionGroup.__str__ is BaseException.__str__)
show("repr row is not the base's", lambda: BaseExceptionGroup.__repr__ is BaseException.__repr__)
show("str inherited whole", lambda: ExceptionGroup.__str__ is BaseExceptionGroup.__str__)
show("repr inherited whole", lambda: ExceptionGroup.__repr__ is BaseExceptionGroup.__repr__)
show("group files no str", lambda: "__str__" in ExceptionGroup.__dict__)
show("str docs", lambda: (BaseExceptionGroup.__dict__["__str__"].__doc__, BaseExceptionGroup.__dict__["__repr__"].__doc__))
show("str objclass", lambda: BaseExceptionGroup.__dict__["__str__"].__objclass__.__name__)
show("repr objclass", lambda: BaseExceptionGroup.__dict__["__repr__"].__objclass__.__name__)
show("kinds", lambda: sorted({type(BaseExceptionGroup.__dict__[n]).__name__ for n in ROWS}))
show("docs filed", lambda: [n for n in ROWS if BaseExceptionGroup.__dict__[n].__doc__ is None])
show("rows with an owner", lambda: [n for n in ROWS if hasattr(BaseExceptionGroup.__dict__[n], "__objclass__")])

show("getitem row", lambda: type(BaseExceptionGroup.__dict__["__class_getitem__"]).__name__)
show("getitem row doc", lambda: BaseExceptionGroup.__dict__["__class_getitem__"].__doc__)
show("getitem row qualname", lambda: BaseExceptionGroup.__dict__["__class_getitem__"].__qualname__)
show("getitem row objclass", lambda: BaseExceptionGroup.__dict__["__class_getitem__"].__objclass__ is BaseExceptionGroup)
show("getitem bound type", lambda: type(BaseExceptionGroup.__class_getitem__).__name__)
show("getitem bound qualname", lambda: BaseExceptionGroup.__class_getitem__.__qualname__)
show("getitem not shared", lambda: ExceptionGroup.__class_getitem__ is BaseExceptionGroup.__class_getitem__)
show("getitem not repeated", lambda: BaseExceptionGroup.__class_getitem__ is BaseExceptionGroup.__class_getitem__)
show("getitem bound self", lambda: BaseExceptionGroup.__class_getitem__.__self__ is BaseExceptionGroup)
show("new row is not inherited", lambda: ExceptionGroup.__new__ is BaseExceptionGroup.__new__)
show("new qualname stays", lambda: ExceptionGroup.__new__.__qualname__)
show("derive row", lambda: BaseExceptionGroup.derive.__qualname__)
