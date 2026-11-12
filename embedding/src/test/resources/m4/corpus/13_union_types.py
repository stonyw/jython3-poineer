# PEP 604: `int | str` is a value of type `typing.Union`, the same type `_types.UnionType` names. The
# script pins what the operator builds and what the value holds — flattening, first-seen deduplication,
# the treatment of `None`, equality, the frozenset hash, and the accepted operands — then the two tests
# that consume a union and the sentences with which the class side refuses what it cannot absorb.
# `typing` itself is not a module here, so the difference from `typing.Union` is only the two names the
# type answers: `Union` and the `typing` module. A union of a non-type value is kept as written except
# for strings and tuples, whose typing-side rewriting needs that module and is left out.

import _types


def show(label, fn):
    try:
        print(label, "ok", fn())
    except Exception as exc:
        print(label, type(exc).__name__, exc)


union = int | str
print("type", type(union).__name__, type(union).__qualname__, type(union).__module__)
print("doc", repr(type(union).__doc__))
print("same", _types.UnionType is type(union), type(int | None) is _types.UnionType)
print("repr", repr(union), repr(int | None), repr(None | int))
print("args", union.__args__, (int | None).__args__)
print("parameters", union.__parameters__)
print("class", union.__class__ is _types.UnionType)

print("identity", int | int is int, list[int] | list[int] is list[int])
print("flatten", ((int | str) | bytes).__args__)
print("dedup", ((int | str) | int).__args__)
print("left", (bytes | (int | str)).__args__)
print("unions", ((int | str) | (str | int)).__args__)
print("aliases", int | list[int], list[int] | int, list[int] | None)

print("eq", (int | str) == (str | int), (int | str) == (int | bytes), (int | str) == int)
print("ne", (int | str) != (int | bytes))
print("hash", hash(int | str) == hash(str | int),
      hash(int | str) == hash(frozenset((int, str))))

mixed = (int | str) | 3
print("loose", mixed.__args__, (3 | (int | str)).__args__)
print("loose repr", repr(mixed))
print("alias union", repr(list[int] | (int | str)), (list[int] | (int | str)).__args__)

print("instance", isinstance(1, int | str), isinstance("a", int | str),
      isinstance(1.0, int | str), isinstance(True, int | str), isinstance(None, int | None))
print("subclass", issubclass(bool, int | str), issubclass(float, int | str),
      issubclass(int, int | str))
print("tuple", isinstance((1, 2), tuple | list), isinstance([], tuple | list))
print("generic first", isinstance(1, int | list[int]))
show("generic second", lambda: isinstance(1, list[int] | int))
show("subclass generic", lambda: issubclass(bool, list[int] | int))
show("subclass first arg", lambda: issubclass(1, int | str))
show("mixed hit", lambda: isinstance(1, mixed))
show("mixed miss", lambda: isinstance(1.0, mixed))
show("mixed subclass", lambda: issubclass(float, mixed))

print("descriptor", type.__or__(int, str), type.__ror__(int, str))
print("descriptor alias", type.__or__(int, list[int]), type.__ror__(int, list[int]))
print("not implemented", type.__or__(int, 3), type.__ror__(int, 3))
print("union row", repr(union.__or__(bytes)), repr(union.__ror__(bytes)))
print("union row args", union.__or__(bytes).__args__, union.__ror__(bytes).__args__)

show("type with int", lambda: int | 3)
show("none with none", lambda: None | None)
show("new union", lambda: type(union)())
show("subscript", lambda: union[0])
show("alias with int", lambda: list[int] | 3)
show("int or", lambda: 1 | 2)
show("set or", lambda: {1} | {2})
