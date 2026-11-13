"""PEP 553: `breakpoint()` and the hook it calls, the slice 25 repair.

`breakpoint` was missing from the builtins namespace, so all twelve tests of
`test_builtin.TestBreakpoint` errored on `sys.__breakpointhook__` before any of them ran. The
builtin is now the two-line forwarder CPython writes: call `sys.breakpointhook(*args, **kws)`,
and complain `lost sys.breakpointhook` when the name has been deleted. The default hook reads
`PYTHONBREAKPOINT` from `os.environ` when it runs, which is what makes `EnvironmentVarGuard`
effective: `''` or unset drops into `pdb.set_trace`, `'0'` does nothing, and a `module.attr`
name is imported and called, with an unimportable one warning and then doing nothing.

The same slice also repaired `object.__ne__`, which had been answering identity rather than
the negation of the value's own `__eq__`. That is what `unittest.mock._Call` rides on — it
writes `__ne__ = object.__ne__` — so the assertion helpers compared two equal calls as
unequal; six of the twelve breakpoint tests failed on that as well.

This script walks both: the hook's identity and documentation, the passthrough of positional
and keyword arguments, the lost-hook refusal, the five `PYTHONBREAKPOINT` readings, and the
negation that now delegates.
"""

import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
                   "Versions/3.14/lib/python3.14")

import builtins
import os
import warnings

# --- the builtin and the default hook --------------------------------------------------

print("doc first line", builtins.breakpoint.__doc__.splitlines()[0])
print("text signature", builtins.breakpoint.__text_signature__)
print("module", builtins.breakpoint.__module__, builtins.breakpoint.__qualname__)
print("default hook", sys.breakpointhook is sys.__breakpointhook__)
print("hook name", sys.breakpointhook.__name__, sys.breakpointhook.__qualname__,
      sys.breakpointhook.__module__)
print("hook doc", repr(sys.breakpointhook.__doc__))
print("hook signature", sys.breakpointhook.__text_signature__)

# --- a replaced hook, with the arguments passed through untouched -----------------------

calls = []


def recording_hook(*args, **kwargs):
    calls.append((args, kwargs))
    return "hooked"


saved = sys.breakpointhook
sys.breakpointhook = recording_hook
print("passthrough plain", builtins.breakpoint())
print("passthrough args", builtins.breakpoint(1, 2, 3, four=4, five=5))
print("recorded", calls)

# --- a deleted hook -------------------------------------------------------------------

del sys.breakpointhook
try:
    builtins.breakpoint()
except RuntimeError as exc:
    print("lost hook", type(exc).__name__, str(exc))
finally:
    sys.breakpointhook = saved

# --- PYTHONBREAKPOINT, read when the hook runs ----------------------------------------

def with_setting(value, action):
    previous = os.environ.get("PYTHONBREAKPOINT")
    if value is None:
        os.environ.pop("PYTHONBREAKPOINT", None)
    else:
        os.environ["PYTHONBREAKPOINT"] = value
    try:
        return action()
    finally:
        if previous is None:
            os.environ.pop("PYTHONBREAKPOINT", None)
        else:
            os.environ["PYTHONBREAKPOINT"] = previous


print("noop", with_setting("0", lambda: builtins.breakpoint()))
print("builtin name", with_setting("int", lambda: builtins.breakpoint("7")))
try:
    with_setting("sys.exit", lambda: builtins.breakpoint())
except SystemExit as exc:
    print("sys.exit", type(exc).__name__, exc.code)


def unimportable(value):
    def run():
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            answer = builtins.breakpoint()
        return answer, [(type(w.message).__name__, str(w.message)) for w in caught]

    return run


for setting in (".", "int.", "nosuchbuiltin", "nosuchmodule.nosuchcallable"):
    print("unimportable", setting, with_setting(setting, unimportable(setting)))

# --- the negation that delegates ------------------------------------------------------

class OnlyEq:
    def __eq__(self, other):
        return True


print("only eq", OnlyEq() == OnlyEq(), OnlyEq() != OnlyEq())


class NoEq:
    pass


first, second = NoEq(), NoEq()
print("identity", first == second, first != second, first == first, first != first)
print("object ne", object.__ne__(first, second), object.__ne__(first, first))
print("none ne", type(None).__ne__(None, 1), None != None, None != 1)


class AssignsNe:
    def __init__(self, value):
        self.value = value

    def __eq__(self, other):
        return isinstance(other, AssignsNe) and other.value == self.value

    __ne__ = object.__ne__


print("assigned ne", AssignsNe(1) != AssignsNe(1), AssignsNe(1) != AssignsNe(2),
      AssignsNe(1) == AssignsNe(1))
