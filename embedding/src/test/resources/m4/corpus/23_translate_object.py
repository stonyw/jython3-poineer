"""str.translate and object(): the two builtins that unblock textwrap and _pydatetime."""

import sys

sys.path.insert(
    0,
    "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14",
)

import re
import textwrap

ADDRESS = re.compile(r" at (0x[0-9a-f]+|\d+)")


def clean(text):
    return ADDRESS.sub(" at ADDR", text)


def attempt(label, thunk):
    try:
        print(label, repr(thunk()))
    except Exception as exc:
        print(label, type(exc).__name__ + ":", exc)


print("translate str", "abc".translate({97: "x", 98: None}))
print("translate ordinals", "abc".translate({97: 98, 99: 0x1F600}))
print("translate untouched", "abc".translate({1: "x"}))
print("translate astral key", "\U0001F600".translate({0x1F600: "x"}))
print("translate empty", "".translate({}))
print("translate maketrans", "abc".translate(str.maketrans("ab", "xy", "c")))
attempt("translate none", lambda: "abc".translate(None))
attempt("translate int", lambda: "abc".translate(97))
attempt("translate list", lambda: "abc".translate([1, 2]))
attempt("translate float value", lambda: "abc".translate({97: 1.5}))
attempt("translate bytes value", lambda: "abc".translate({97: b"x"}))
attempt("translate negative", lambda: "abc".translate({97: -1}))
attempt("translate wide", lambda: "abc".translate({97: 0x110000}))
attempt("translate no args", lambda: "abc".translate())
attempt("translate two args", lambda: "abc".translate({}, {}))
attempt("translate keyword", lambda: "abc".translate(table={}))

# Both call sites that were blocked: re.escape translates, textwrap wraps and escapes.
print("escape", re.escape("a.b*c"))
print("textwrap", textwrap.fill("the quick brown fox jumps", 9))

first = object()
second = object()
print("object type", type(first).__name__, type(first) is object)
print("object identity", first is first, first is second, first == second)
print("object truth", bool(first))
print("object repr", clean(repr(first)))
attempt("object positional", lambda: object(1))
attempt("object keyword", lambda: object(x=1))
attempt("object two", lambda: object(1, 2))
