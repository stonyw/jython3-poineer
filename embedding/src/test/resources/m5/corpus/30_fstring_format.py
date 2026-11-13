"""f-strings hand the specification to the value's type's `__format__`, the slice 27 repair.

`FORMAT_SIMPLE` and `FORMAT_WITH_SPEC` are `PyObject_Format`: the specification goes to the
`__format__` the *type* publishes, so a bound copy put on an instance is dead code, a class that
writes one receives the specification text, and `object.__format__` answers an empty one with
`str(value)` and any other with the `TypeError` naming the type. `format()` and `str.format` enter
through the same door, which is why their answers agree with the f-string's. A family with its own
writer keeps it, and that writer sees the value itself: `format(True)` is `'True'`, not `'1'`, while
a numeric specification still presents the bool as the integer it is.
"""

import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
                   "Versions/3.14/lib/python3.14")


def show(label, thunk):
    try:
        print(label, repr(thunk()))
    except Exception as exc:
        print(label, type(exc).__name__, str(exc))


# --- the lookup is the type's, and the instance's copy is never consulted -----------------

calls = []


class X:
    def __format__(self, spec):
        calls.append(spec)
        return "class" + spec


x = X()
y = X()
y.__format__ = lambda spec: "instance"

print("instance bypassed", repr(f"{y}"), repr(format(y)))
print("type member", repr(f"{x:>7}"), repr(type(x).__format__(x, ">7")))
print("instance-only call", repr(y.__format__("z")), calls)

# --- the specification reaches the class unchanged, empty or not --------------------------

class O:
    def __format__(self, spec):
        return "*" if not spec else spec


print("spec text", repr(f"{O()}"), repr(f"{O():}"), repr(f"{O():x}"), repr(f"{O():{3}}"))
print("plain int spec", repr(f"{3:}"), repr(f"{3!s:}"))

# --- object.__format__: empty is str(value), anything else names the type -----------------

class Plain:
    def __str__(self):
        return "plain"


show("object empty", lambda: format(Plain()))
show("object spec", lambda: f"{Plain():>4}")
show("object spec format", lambda: format(object(), "x"))

# --- format() and str.format take the same route -----------------------------------------

show("format agrees", lambda: (f"{x:^9}", format(x, "^9")))
show("str.format", lambda: ("{:^9}".format(X()), "{0!s:>3}".format(3)))

# --- the built-in families keep their own writer, and it sees the value itself -----------

print("bool text", repr(format(True)), repr(f"{True}"), repr(f"{False!r}"))
print("bool spec", repr(format(True, ">5")), repr(f"{False:=^7}"))
print("width", repr(format("12", "6")), repr(format(12, "6")))
print("float", repr(f"{3.14159:10.3f}"), repr(format(2.5, ".0f")))

# --- side effects happen once each, in order ---------------------------------------------

seen = []


def tick():
    seen.append(len(seen) + 1)
    return len(seen)


print("order", repr(f"{tick()} {tick()}"), seen)

# --- an int subclass: its owner's writer wins, the inherited one still sees the value ----

class Inherits(int):
    pass


class Writes(int):
    def __format__(self, spec):
        return "j" + spec


print("int subclass", repr(f"{Inherits(3)}"), repr(f"{Inherits(3):>4}"),
      repr(format(Writes(3), "^5")))

# --- a writer that does not answer text is refused ---------------------------------------

class NotText:
    def __format__(self, spec):
        return 42


show("non-str result", lambda: f"{NotText()}")
show("non-str result format", lambda: format(NotText()))

# --- a raw f-string keeps the backslash in the specification -----------------------------

class Echo:
    def __format__(self, spec):
        return spec


print("raw spec", repr(f"{Echo():\xFF}"), repr(rf"{Echo():\xFF}"))
print("nested spec", repr(f"{Echo():{'ab'}}"), repr(rf"{Echo():{'\xFF'}}"))
