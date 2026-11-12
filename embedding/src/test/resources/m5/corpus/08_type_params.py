"""M5 slice 6: PEP 695's intrinsic family — parameters, generic bases and `type` aliases.

`class C[T]` and `type X = int` are compiled into `CALL_INTRINSIC_1`/`2`, whose answers are the objects
`typing` builds by hand. What is printed is the surface those objects expose: `__type_params__`,
`__parameters__`, the subscription a plain class gets, and — for a parameter the compiler made — its
module and inferred variance, which is what distinguishes it from `typing.TypeVar('T')`.

A bound, a constraint list, a default and an alias value all arrive as thunks and are only run when
read, so the last section defines declarations over a name that does not exist and reads them after.
`type Pair[T] = ...` is here for the parameters and the value only: subscripting a generic alias
(`Pair[int]`) is a separate hole and is not written.
"""

import sys

# The reference interpreter's own library, exactly as the other corpora do it.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import typing


class C[T]:
    pass


print("class", C.__type_params__, C.__parameters__, C[int], C[int].__origin__)
print("parameter", repr(C.__type_params__[0]), C.__type_params__[0].__module__,
      C.__type_params__[0].__infer_variance__)

type X = int

print("alias", X, X.__module__, X.__value__, X.__type_params__, X.__parameters__)

type Pair[T] = tuple[T, T]

print("generic alias", Pair, Pair.__type_params__, Pair.__parameters__)
print("alias value", Pair.__value__)


class D[T = int]:
    pass


print("default", D.__type_params__[0].__default__, D.__type_params__[0].has_default())
print("default class", D.__parameters__, D[int])


class B[T: int]:
    pass


print("bound", B.__type_params__[0].__bound__)


class K[T: (int, str)]:
    pass


print("constraints", K.__type_params__[0].__constraints__)


def f[T](value: T) -> T:
    return value


print("function", f.__type_params__, f(3), f.__annotations__)


def g[**P, *Ts]() -> None:
    pass


print("kinds", [repr(p) for p in g.__type_params__],
      [type(p).__name__ for p in g.__type_params__])


class SupportsAbs[T](typing.Protocol):
    def __abs__(self) -> T:
        ...


print("protocol", SupportsAbs.__type_params__, SupportsAbs.__parameters__, SupportsAbs[int])


class LazyBound[T: Missing]:
    pass


class LazyDefault[T = Missing]:
    pass


type LazyAlias = Missing

print("lazy defined")
for read in (lambda: LazyBound.__type_params__[0].__bound__,
             lambda: LazyDefault.__type_params__[0].__default__,
             lambda: LazyAlias.__value__):
    try:
        read()
    except NameError as error:
        print("lazy", type(error).__name__, error)
