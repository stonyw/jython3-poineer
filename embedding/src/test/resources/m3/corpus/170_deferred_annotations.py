# PEP 649: the compiler no longer builds an annotations dictionary at definition time. A function
# carries an annotate function in its hidden slot, a class carries one under `__annotate_func__` in
# its dictionary, and `__annotations__` is whatever that function answers the first time it is asked.
# Nothing here prints a function's repr, which carries an address.

def f(x: int, y: "str" = 1, *args: float, z: bytes = b"", **kw: bool) -> None:
    pass


print(f.__annotations__)
print(f.__annotations__ is f.__annotations__)
print(f.__annotate__.__qualname__)
print(f.__annotate__(1) == f.__annotations__)
print(sorted(f.__annotate__(2)))
print("__annotations__" in f.__dict__, f.__dict__)

try:
    f.__annotate__(3)
except NotImplementedError:
    print("forward-ref format refused")

try:
    f.__annotate__(None)
except TypeError:
    print("format must be a number")


def g():
    pass


print(g.__annotate__, g.__annotations__, g.__annotations__ is g.__annotations__)


# The class body files its annotate function, not a dictionary; the name it reads its spellings
# through is the namespace the body was filling, which is what LOAD_FROM_DICT_OR_GLOBALS is for.
Alias = int


class C:
    x: int
    y: "str"
    other = float
    z: other
    w: Alias

    def m(self, a: "C") -> None:
        pass


print(type(vars(C)["__annotate_func__"]).__name__)
print("__annotations__" in vars(C), "annotat" in "".join(sorted(vars(C))))
print(C.__annotations__)
print(C.__annotations__ is C.__annotations__)
print("__annotations_cache__" in vars(C))


# Replacing the annotate function forgets the cache, so the next read runs the new one.
C.__annotate__ = lambda fmt: {"z": 5}
print(C.__annotations__)
print("__annotations_cache__" in vars(C), C.__annotate__ is not None)


# Writing the dictionary files it under the cache name and forgets the function; deleting it makes
# the next read start over from nothing.
C.__annotations__ = {"q": 2}
print(C.__annotations__, C.__annotate__)
del C.__annotations__
print(C.__annotations__)


class D:
    pass


print(D.__annotate__)
print(sorted(vars(D)))


class E:
    pass


print(E.__annotations__)
print(sorted(vars(E)))


class F:
    pass


F.__annotate__ = lambda fmt: 7
try:
    print(F.__annotations__)
except TypeError as error:
    print("non-dict:", error)

try:
    F.__annotate__ = 3
except TypeError as error:
    print("callable:", error)

try:
    del F.__annotate__
except TypeError as error:
    print("delete:", error)
