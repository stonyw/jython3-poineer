"""The seams slice 21 closed, measured where a program can see them.

Three of them are what stood between the reference's own test suite and its first number:

* `_types` — a built-in module is answered from the interpreter's own table, not from
  `sys.modules`, so evicting it and importing again hands back the same module instead of
  asking a finder that would call back into the same import.
* `types.FunctionType.__code__` and `.__globals__` — the two descriptors `types.py` files as
  `GetSetDescriptorType` and `MemberDescriptorType`, and reads through a function's own
  attributes.
* `_io.TextIOWrapper` — constructible directly over a binary buffer, not only as what
  `open()` returns.

Four smaller ones that the same two goals needed are here too: a stored `NotImplemented` is a
value rather than an absent attribute, `StringIO` may be subclassed the way `doctest` does,
`[\\b]` is a backspace in a character class, and `sys` now publishes `stdlib_module_names` and
`float_repr_style`.
"""

import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
                   "Versions/3.14/lib/python3.14")

import _imp
import _io
import re
import types
from importlib.machinery import BuiltinImporter


def refusal(label, action):
    try:
        action()
    except Exception as exc:
        print(label, type(exc).__name__, str(exc))
    else:
        print(label, "accepted")


import _types

first = _types
del sys.modules["_types"]
print("evicted", "_types" in sys.modules)
import _types as again

print("rebuilt", again.__name__, again.ModuleType is first.ModuleType)
print("still answers", again.ModuleType is types.ModuleType)

del sys.modules["_types"]
spec = BuiltinImporter.find_spec("_types")
print("spec", spec.name, spec.origin)
built = BuiltinImporter.create_module(spec)
print("create_builtin", type(built).__name__, built.__name__)

code_descriptor = types.FunctionType.__code__
globals_descriptor = types.FunctionType.__globals__
print(
    "descriptor types",
    type(code_descriptor) is types.GetSetDescriptorType,
    type(globals_descriptor) is types.MemberDescriptorType,
)
print("descriptor names", code_descriptor.__name__, globals_descriptor.__name__)
print(
    "descriptor owners",
    code_descriptor.__objclass__ is types.FunctionType,
    globals_descriptor.__objclass__ is types.FunctionType,
)

A_GLOBAL = "global"


def sample():
    return A_GLOBAL


print(
    "through a function",
    type(sample.__code__).__name__,
    sample.__globals__ is globals(),
    sample.__globals__["A_GLOBAL"],
)
refusal("set code", lambda: setattr(sample, "__code__", 1))
refusal("delete code", lambda: delattr(sample, "__code__"))
refusal("delete globals", lambda: delattr(sample, "__globals__"))

buffer = _io.BytesIO(b"hello\nworld\n")
wrapper = _io.TextIOWrapper(buffer)
print("wrapper", type(wrapper).__name__, repr(wrapper.read()))
print("closed", wrapper.closed, buffer.closed)
wrapper.close()
print("after close", wrapper.closed, buffer.closed)

second = _io.TextIOWrapper(_io.BytesIO("h\u00e9llo\n".encode("utf-8")), encoding="utf-8")
print("utf-8", repr(second.read()), second.encoding, second.errors)
second.close()

line = _io.TextIOWrapper(_io.BytesIO(b"a\r\nb\rc\n"))
print("universal", repr(line.readline()), repr(line.readline()), repr(line.readline()), repr(line.readline()))
line.close()


class Holder:
    def __init__(self):
        self.value = NotImplemented


holder = Holder()
print("stored", holder.value is NotImplemented, "value" in vars(holder))
refusal("absent", lambda: holder.missing)


class Spoof(_io.StringIO):
    def getvalue(self):
        return _io.StringIO.getvalue(self) + "!"


spoof = Spoof("abc")
spoof.write("d")
print("subclass", spoof.getvalue(), type(spoof).__name__, isinstance(spoof, _io.StringIO))
print("subclass init", repr(_io.StringIO.getvalue(spoof)))
refusal("subclass write type", lambda: spoof.write(1))

print("backspace class", re.findall(r"[\b]", "a\x08b"), re.findall(r"\bword\b", "a word here"))
print("class escapes", re.findall(r"[\d]+", "a1b22"), re.findall(r"[^\w]", "a-b c"))

print(
    "stdlib names",
    type(sys.stdlib_module_names).__name__,
    "sys" in sys.stdlib_module_names,
    "test" in sys.stdlib_module_names,
    "nosuchmodule" in sys.stdlib_module_names,
)
print("float style", sys.float_repr_style, repr(0.1), repr(1e16), repr(3.0))
