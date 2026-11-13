"""A frame's builtins come from its globals' `__builtins__`, the slice 26 repair.

CPython does not look a name up in the interpreter's own builtins dictionary. The first time a
frame runs it copies `globals['__builtins__']` into `f_builtins` — a module contributes its
dictionary and everything else is kept as it stands — and `LOAD_GLOBAL` asks *that* mapping, only
after the globals have missed. `LOAD_BUILD_CLASS` and `IMPORT_NAME` are ordinary reads of the
same mapping (`__build_class__`, `__import__`), which is why `exec(code, {'__builtins__': {}})`
cannot print and cannot define a class, and why a mapping that raises on `__getitem__` raises out
of a global read.

This script walks the mapping: empty and non-mapping `__builtins__`, a dict subclass and a
mappingproxy (hit and miss), `__build_class__` missing and present, `__import__` missing and
replaced with the five arguments CPython passes, and `__reduce__`'s own read of `iter`.
"""

import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
                   "Versions/3.14/lib/python3.14")

import types

# --- an empty and a non-mapping __builtins__ ------------------------------------------

try:
    exec("print('unreachable')", {"__builtins__": {}})
except NameError as exc:
    print("empty builtins", type(exc).__name__, str(exc))

try:
    exec("print('unreachable')", {"__builtins__": 123})
except TypeError as exc:
    print("non-mapping builtins", type(exc).__name__, str(exc))

# --- a read-only builtins mapping gets asked, and its refusal travels out -------------

class frozen_error(Exception):
    pass


class frozendict(dict):
    def __setitem__(self, key, value):
        raise frozen_error("frozendict is readonly")


frozen_builtins = frozendict(__builtins__.__dict__ if isinstance(__builtins__, types.ModuleType)
                             else __builtins__)
try:
    exec("__builtins__['superglobal'] = 2; print(superglobal)",
         {"__builtins__": frozen_builtins})
except frozen_error as exc:
    print("frozen builtins", type(exc).__name__, str(exc))

# --- __build_class__ is read from that same mapping ------------------------------------

try:
    exec("class A: pass", {"__builtins__": {}})
except NameError as exc:
    print("no build class", type(exc).__name__, str(exc))

namespace = {"__builtins__": frozen_builtins}
exec("class A: pass", namespace)
print("build class from mapping", namespace["A"].__name__, namespace["A"].__bases__)

try:
    exec("class A: pass", {"__builtins__": frozendict()})
except NameError as exc:
    print("empty frozendict", type(exc).__name__, str(exc))

# --- a dict subclass and a mappingproxy: hits and misses ------------------------------

class customdict(dict):
    pass


ns = {"__builtins__": customdict({"superglobal": 1})}
print("customdict hit", eval(compile("superglobal", "test", "eval"), ns))
try:
    eval(compile("superglobal", "test", "eval"), {"__builtins__": customdict()})
except NameError as exc:
    print("customdict miss", type(exc).__name__, str(exc))

proxy = types.MappingProxyType({"superglobal": 1})
print("mappingproxy hit", eval(compile("superglobal", "test", "eval"),
                               {"__builtins__": proxy}))
try:
    eval(compile("superglobal", "test", "eval"),
         {"__builtins__": types.MappingProxyType({})})
except NameError as exc:
    print("mappingproxy miss", type(exc).__name__, str(exc))

# --- an __import__ that raises, and one that records what it was handed ---------------

try:
    exec(compile("import foo.bar", "test", "exec"),
         {"__builtins__": types.MappingProxyType({})})
except ImportError as exc:
    print("no import", type(exc).__name__, str(exc))

ns = {"__builtins__": types.MappingProxyType({"__import__": lambda *args: args})}
exec(compile("import foo.bar", "test", "exec"), ns)
answer = ns["foo"]
print("import args", answer[0], answer[1] is ns, answer[2] is ns, answer[3], answer[4])

# --- __reduce__ reads `iter` from the same mapping ------------------------------------

iterator = iter([1, 2])
try:
    eval(compile("x.__reduce__()", "test", "eval"),
         {"__builtins__": types.MappingProxyType({}), "x": iterator})
except AttributeError as exc:
    print("reduce missing iter", type(exc).__name__, str(exc))

reduced = eval(compile("x.__reduce__()", "test", "eval"),
               {"__builtins__": types.MappingProxyType({"iter": iter}), "x": iterator})
print("reduce", reduced[0] is iter, reduced[1], reduced[2])
