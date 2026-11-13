"""`types.UnionType` and the bar, the ten `test_types` cases slice 23 turned green.

The cluster had four shapes. The checked builders — `types.UnionType[...]`, `typing.Union[...]`
and a union's own bar — were missing, so `int | "str"` built a union out of a string instead of
a `ForwardRef`, and the two empty forms refused with the wrong sentence. The unchecked builders
— `type.__or__` and an alias's bar — accepted operands they should have declined. `__parameters__`
was empty, so `(list[T] | int)[str]` had nothing to fill in. And the reduction of an alias was
not filed where `object.__reduce_ex__` looks, so copy and pickle fell back to a recipe that could
not rebuild one.

This script walks the surface those four touch: construction and its refusals, the flattening
and deduplication a bar does, the checked conversion of a string to a forward reference, the
hash split that makes a union of unhashable classes constructible but not hashable, and the
reductions.
"""

import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
                   "Versions/3.14/lib/python3.14")

import collections.abc
import copy
import pickle
import types
import typing

T = typing.TypeVar("T")


def refusal(label, action):
    try:
        action()
    except BaseException as exc:
        print(label, type(exc).__name__, str(exc))
    else:
        print(label, "accepted")


# --- construction ---------------------------------------------------------------------

print("one", types.UnionType[int] is int, types.UnionType[int])
print("two", types.UnionType[int, str])
print("tuple key", types.UnionType[(int, str)])
print("same", types.UnionType[int, int] is int)
print("none", types.UnionType[int, None])

refusal("no arguments", lambda: types.UnionType())
refusal("direct call", lambda: types.UnionType(int, str))
refusal("typing empty", lambda: typing.Union[()])

# `types.UnionType[...]` is the checked builder: a string becomes a forward reference.
checked = types.UnionType[int, "str"]
print("checked args", checked.__args__[0], type(checked.__args__[1]).__name__,
      checked.__args__[1].__forward_arg__)

# --- the bar --------------------------------------------------------------------------

print("bar", int | str, str | int)
print("none bar", int | None, None | int, int | None | str)
print("flatten", int | (str | bytes), (int | str) | (bytes | float))
print("dedup", int | int, (int | str) | int, int | (int | str))
print("subclass kept", int | bool)
print("chain", int | str | bytes)
print("alias", list[int] | dict[str, int])

refusal("int or three", lambda: int | 3)
refusal("three or int", lambda: 3 | int)
refusal("int or string", lambda: int | "str")
refusal("alias or three", lambda: list[int] | 3)

# A forward reference answers the bar itself, which is the reflected half of `int | ref`.
forward = int | typing.ForwardRef("str")
print("forward", forward.__args__[0], type(forward.__args__[1]).__name__,
      forward.__args__[1].__forward_arg__)
print("forward bar", type(typing.ForwardRef("str") | int).__name__)

# --- attributes and parameters --------------------------------------------------------

union = int | str
print("origin", union.__origin__ is types.UnionType)
print("name", union.__name__, union.__qualname__, union.__module__)
print("args", union.__args__)
print("parameters", union.__parameters__)

generic = list[T] | int
print("generic parameters", generic.__parameters__, generic.__parameters__[0] is T)
print("substituted", generic[str])
print("nested substituted", (dict[str, T] | int)[float])
refusal("nothing to substitute", lambda: union[int])
refusal("cannot subclass", lambda: union.__mro_entries__(()))

# --- isinstance -----------------------------------------------------------------------

print("isinstance yes", isinstance(1, int | str), isinstance(None, int | None))
print("isinstance no", isinstance(1.5, int | str), isinstance("x", int | None))
print("issubclass", issubclass(bool, int | str))
refusal("isinstance generic", lambda: isinstance(1, list[int] | int))
refusal("issubclass generic", lambda: issubclass(int, collections.abc.Callable[..., str] | int))

# --- hashing and equality -------------------------------------------------------------

print("hash order", hash(int | str) == hash(str | int))
print("equal", int | str == str | int, int | str != int | bytes)
print("equal self", (int | str) == (int | str), (int | str) == int)


class UnhashableMeta(type):
    __hash__ = None


class UnhashableA(metaclass=UnhashableMeta):
    pass


class UnhashableB(metaclass=UnhashableMeta):
    pass


print("unhashable args", (UnhashableA | UnhashableB).__args__[0].__name__,
      (UnhashableA | UnhashableB).__args__[1].__name__)
refusal("unhashable hash", lambda: hash(UnhashableA | UnhashableB))
refusal("unhashable plus int", lambda: hash(int | UnhashableB))

late = False


class LateMeta(type):
    def __hash__(cls):
        if late:
            return 1
        raise TypeError("not hashable")


class LateA(metaclass=LateMeta):
    pass


class LateB(metaclass=LateMeta):
    pass


late_union = LateA | LateB
refusal("late first", lambda: hash(late_union))
late = True
refusal("late second", lambda: hash(late_union))


class BadType(type):
    def __eq__(self, other):
        return 1 / 0


bad_one = BadType("bad_one", (), {})
bad_two = BadType("bad_two", (), {})


def compare_bad():
    return (int | bad_one) == (int | bad_two)


def build_bad():
    return bad_one | bad_two


refusal("bad compare", compare_bad)
refusal("bad build", build_bad)

# --- reductions -----------------------------------------------------------------------

print("pickle", pickle.loads(pickle.dumps(int | str)))
print("pickle generic", pickle.loads(pickle.dumps(list[T] | int)))
print("copy", copy.copy(list[T] | int))
print("deepcopy", copy.deepcopy(list[T] | int))
print("typevar", pickle.loads(pickle.dumps(T)) is T)
