# `_types`: the module that hands out the type objects the class system already has, plus four it
# cannot make. The script asks what the module holds and in what order, then which rows are the same
# object as one the runtime reaches another way — the aliases are the point of the module — and finally
# what the four types with no constructor say when called.

import _types
import sys


class Plain:
    def m(self):
        return 1


plain = Plain()

print("keys", list(vars(_types)))
print("doc", _types.__doc__)
print("basic", _types.__name__, repr(_types.__package__), _types.__loader__.__name__,
      _types.__spec__.name, _types.__spec__.origin)

print("lambda", _types.LambdaType is _types.FunctionType)
print("builtin", _types.BuiltinMethodType is _types.BuiltinFunctionType)
print("function", _types.FunctionType is type(Plain.m))
print("method", _types.MethodType is type(plain.m))
print("none", _types.NoneType is type(None))
print("notimpl", _types.NotImplementedType is type(NotImplemented))
print("ellipsis", _types.EllipsisType is type(...))
print("module", _types.ModuleType is type(sys))
print("namespace", _types.SimpleNamespace is type(type("NS", (), {})()))
print("alias", _types.GenericAlias is type(list[int]))

for row in ["AsyncGeneratorType", "CapsuleType", "CoroutineType", "UnionType",
            "FunctionType", "ModuleType", "NoneType", "GenericAlias"]:
    kind = getattr(_types, row)
    print(row, kind.__name__, kind.__module__, repr(kind))

print("union doc", _types.UnionType.__doc__)
print("capsule doc", _types.CapsuleType.__doc__)

for row in ["CapsuleType", "CoroutineType", "UnionType", "AsyncGeneratorType"]:
    try:
        getattr(_types, row)()
    except TypeError as error:
        print(row, "refused", type(error).__name__, error)
