"""M5 slice 4: the `_typing` surface `typing.py` imports on its first line.

`typing.py` cannot be imported here yet — it needs the PEP 695 compiler intrinsics — so this script
pins the C half directly: the type-parameter family, their reprs and attributes, the sentinel, and
the union constructor.
"""

import _typing as t

print(t._idfunc(7))

# A TypeVar answers its variance in the first character of its repr.
print(t.TypeVar('T'), t.TypeVar('T', covariant=True), t.TypeVar('T', contravariant=True))
print(t.TypeVar('T', infer_variance=True))
print(t.TypeVar('T').__name__, t.TypeVar('T').__module__)

bound = t.TypeVar('B', bound=int)
print(bound.__bound__, bound.__constraints__, bound.has_default())
constrained = t.TypeVar('C', int, str)
print(constrained.__constraints__, constrained.__bound__)
print(constrained.__covariant__, constrained.__contravariant__,
      constrained.__infer_variance__)

# A ParamSpec names a signature; its two halves are made fresh on every read.
p = t.ParamSpec('P')
print(p, p.__name__, p.__module__, p.has_default())
print(p.args, p.kwargs, p.args is p.args, p.args == p.args)
print(p.args.__origin__ is p, p.kwargs.__origin__ is p)
print(t.ParamSpecArgs(int), t.ParamSpecKwargs(int))

ts = t.TypeVarTuple('Ts')
print(ts, ts.__name__, ts.has_default())

alias = t.TypeAliasType('X', list)
print(alias, alias.__name__, alias.__module__, alias.__value__)
print(alias.__type_params__, alias.__parameters__)

# The sentinel is a singleton of a type named in builtins, and asking that type builds it.
print(t.NoDefault, type(t.NoDefault).__name__, type(t.NoDefault).__module__)
print(type(t.NoDefault)() is t.NoDefault)

print(t.Generic.__name__, t.Generic.__module__, t.Generic.__mro__)
print(t.Generic, t.Generic().__class__ is t.Generic)

# A union is built from a tuple of members, flattens duplicates, and keeps None as NoneType.
print(t.Union[int], t.Union[int, str], t.Union[int, int])
print(t.Union[None], t.Union[()] if hasattr(t.Union, '__class_getitem__') else None)
print(t.Union[int, str] == (int | str))


def refusal(call):
    try:
        call()
    except Exception as error:
        print(type(error).__name__, error)
    else:
        print("accepted")


refusal(lambda: t.TypeVar())
refusal(lambda: t.TypeVar(1))
refusal(lambda: t.TypeVar('T', int))
refusal(lambda: t.TypeVar('T', int, str, float, bytes, bool))
refusal(lambda: t.TypeVar('T', covariant=True, contravariant=True))
refusal(lambda: t.TypeVar('T', covariant=True, infer_variance=True))
refusal(lambda: t.TypeVar('T', foo=1))
refusal(lambda: t.ParamSpec())
refusal(lambda: t.ParamSpec('P', 'Q'))
refusal(lambda: t.ParamSpec('P', constraints=(int,)))
refusal(lambda: t.TypeVarTuple())
refusal(lambda: t.TypeVarTuple('Ts', covariant=True))
refusal(lambda: t.ParamSpecArgs())
refusal(lambda: t.ParamSpecKwargs())
refusal(lambda: t.TypeAliasType(1, int))
refusal(lambda: t.TypeAliasType('X'))
refusal(lambda: t.TypeAliasType('X', int, 1))
refusal(lambda: t.TypeAliasType('X', int)(1))
refusal(lambda: t.TypeAliasType('X', int)[int])
refusal(lambda: t.Generic(1))
refusal(lambda: t.Union())
refusal(lambda: t.Union[()])
