# Modules and the one loader that exists without an import system: `type(sys)` as a constructor, the
# five names a module starts with, `BuiltinImporter`, and `sys.modules` as the dict it is.

import builtins
import sys

Module = type(sys)
Loader = sys.__loader__

print("== module(name[, doc]) ==")
plain = Module("foo")
print(vars(plain))
print(Module("foo", "bar").__doc__)
print(Module(name="named").__name__, Module("named", doc=7).__doc__)

for call in [lambda: Module(), lambda: Module(5), lambda: Module(None),
             lambda: Module("a", "b", "c"), lambda: Module("a", foo=1),
             lambda: Module(doc="x"), lambda: Module("a", doc="x", name="b")]:
    try:
        call()
    except TypeError as error:
        print(error)

print("== module attributes ==")
print(repr(plain))
plain.__name__ = "bar"
print(repr(plain))
del plain.__name__
print(repr(plain))
try:
    plain.nope
except AttributeError as error:
    print(error)
plain.__name__ = 5
print(repr(plain), plain.__name__)
try:
    plain.nope
except AttributeError as error:
    print(error)
for remove in [lambda: setattr(plain, "__dict__", {}),
               lambda: delattr(plain, "__dict__")]:
    try:
        remove()
    except AttributeError as error:
        print(error)
print(vars(plain) is plain.__dict__)

print("== BuiltinImporter ==")
print(Loader.__name__, Loader.__module__, Loader.__firstlineno__)
print(repr(Loader.__doc__))
print(Loader._ORIGIN, Loader.__static_attributes__)
print(list(vars(Loader)))
for name in ["find_spec", "create_module", "exec_module", "get_code", "get_source",
             "is_package", "load_module"]:
    print(name, repr(getattr(Loader, name).__doc__))

print(Loader.get_code("sys"), Loader.get_source("sys"), Loader.is_package("sys"))
print(Loader.find_spec("nope"))
try:
    Loader.find_spec(5)
except TypeError as error:
    print(error)
for value in [5, None, "nope"]:
    try:
        Loader.get_code(value)
    except ImportError as error:
        print(error, repr(error.name))

for spec in [None, "nope", type("Spec", (), {"name": "nope"})()]:
    try:
        Loader.create_module(spec)
    except (AttributeError, ImportError) as error:
        print(type(error).__name__, error)

try:
    Loader.load_module("nope")
except ImportError as error:
    print(error, repr(error.name))
print(Loader.exec_module(None))

for call in [lambda: Loader.get_code(), lambda: Loader.get_code(foo=1),
             lambda: Loader.get_code("sys", fullname="x"),
             lambda: Loader.find_spec("a", None, None, None),
             lambda: Loader.create_module(), lambda: Loader.exec_module(),
             lambda: Loader.exec_module("x", "y")]:
    try:
        call()
    except TypeError as error:
        print(error)

print("== sys.modules ==")
print(type(sys.modules) is dict, sys.modules["sys"] is sys,
      sys.modules["builtins"] is builtins)
print(type(Loader) is type, repr(sys.__package__), repr(builtins.__package__))
print(sys.__loader__ is builtins.__loader__)
saved = sys.modules
sys.modules = {}
print("rebound", sys.modules == {})
import builtins as again
print("again", again is builtins)
sys.modules = saved
sys.modules["probe"] = 42
print(sys.modules["probe"])
del sys.modules["probe"]
print("probe" in sys.modules, sys.modules is saved)
