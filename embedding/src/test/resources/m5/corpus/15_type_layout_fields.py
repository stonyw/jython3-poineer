"""The three numbers `type` reports about a class's layout: `__flags__`, `__basicsize__`,
`__itemsize__`.

`__flags__` is the one field that says what a class is as much as what it has: `_HEAPTYPE`
separates a class the program wrote from one the binary shipped, `_BASETYPE` says whether a
subclass may name it, `_ABSTRACT` is the bit `abc` flips after the class exists, and the
managed-dictionary bits say where a value's attributes and weak references live.  A class that
declares `__slots__` has neither a dictionary nor a weak-reference list unless the declaration
names them, so the bits track the slots.  `__basicsize__` is the shape a value occupies — the
base's size plus one pointer per declared slot — and `__itemsize__` is the width of one item in a
value that carries a variable-length tail, which a subclass inherits from the root of its family.
"""


def show(label, cls):
    print(label, cls.__flags__, cls.__basicsize__, cls.__itemsize__)


# The compiled-in roots: the flags the binary declared, the sizes of their C structs, and the
# item widths of the families that carry one.
for builtin in (
    object,
    type,
    int,
    bool,
    float,
    complex,
    str,
    bytes,
    bytearray,
    list,
    tuple,
    dict,
    set,
    frozenset,
    range,
    slice,
    property,
    staticmethod,
    classmethod,
    super,
    BaseException,
    ValueError,
    ExceptionGroup,
):
    show(builtin.__name__, builtin)


# Classes written in Python.  A plain class gets a managed dictionary, inline values and a weak
# reference; declaring no slot for either takes that storage away again.
class Plain:
    pass


class Slotted:
    __slots__ = ("a", "b")


class EmptySlots:
    __slots__ = ()


class WithDict:
    __slots__ = ("__dict__",)


class WithWeak:
    __slots__ = ("__weakref__",)


class Derived(Plain):
    pass


class DerivedSlots(Slotted):
    __slots__ = ("c",)


for written in (Plain, Slotted, EmptySlots, WithDict, WithWeak, Derived, DerivedSlots):
    show(written.__name__, written)


# The same over a compiled-in base: the base's family bits survive, `_HEAPTYPE` replaces
# `_STATIC_BUILTIN`, and the storage bits follow the family — `int` and `tuple` keep no
# weak-reference storage and inherit `_LONG_SUBCLASS` / `_TUPLE_SUBCLASS` so `__itemsize__` stays the
# family's.  Six families are not covered: this runtime refuses to subclass `bytes`,
# `bytearray`, `float`, `complex`, `set` and `frozenset` at all.
class MyInt(int):
    pass


class MyIntSlots(int):
    __slots__ = ()


class MyStr(str):
    pass


class MyTuple(tuple):
    pass


class MyList(list):
    pass


class MyDict(dict):
    pass


class MyExc(ValueError):
    pass


class MyExcSlots(ValueError):
    __slots__ = ()


class MyType(type):
    pass


class MyProp(property):
    pass


for written in (
    MyInt,
    MyIntSlots,
    MyStr,
    MyTuple,
    MyList,
    MyDict,
    MyExc,
    MyExcSlots,
    MyType,
    MyProp,
):
    show(written.__name__, written)


# `_ABSTRACT` follows the `__abstractmethods__` setter, not the class body: a class whose
# namespace already names the attribute is born without the bit, and only a write through the
# setter sets or clears it.
class Marked:
    pass


show("Marked", Marked)
Marked.__abstractmethods__ = frozenset({"run"})
show("Marked abstract", Marked)
del Marked.__abstractmethods__
show("Marked cleared", Marked)


class BornAbstract:
    __abstractmethods__ = frozenset({"run"})


show("BornAbstract", BornAbstract)


# An abstract class.  `abc.ABCMeta` marks the method with `__isabstractmethod__` and then writes
# the set of names it collected through the `__abstractmethods__` setter, so the class carries the
# bit; a subclass that implements the method does not.  The two writes are spelled out here rather
# than importing `abc`, because the class is the same either way.
class Shape:
    def area(self):
        ...


Shape.area.__isabstractmethod__ = True
Shape.__abstractmethods__ = frozenset({"area"})


class Square(Shape):
    def area(self):
        return 1


show("Shape", Shape)
show("Square", Square)
print("Shape is abstract", bool(Shape.__flags__ & (1 << 20)))
print("Square is abstract", bool(Square.__flags__ & (1 << 20)))
