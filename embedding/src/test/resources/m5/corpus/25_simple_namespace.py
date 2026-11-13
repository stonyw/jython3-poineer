"""`types.SimpleNamespace`, the eighteen `test_types` cases slice 22 turned green.

The cluster was one shape repeated: every failure was `calling 'SimpleNamespace' is not
implemented` or `subclassing 'SimpleNamespace' is not implemented`, because the type had a
name in the reference's table but no table of its own. This script walks what a program can
see of the type once it has one: the constructor's two halves, the dictionary that is the
whole value, the repr that prints it, equality across the family, the refusal to hash, and
the two recipes `__reduce__` and `__replace__`.
"""

import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
                   "Versions/3.14/lib/python3.14")

import types


def refusal(label, action):
    try:
        action()
    except Exception as exc:
        print(label, type(exc).__name__, str(exc))
    else:
        print(label, "accepted")


class Pairs:
    """A mapping the dictionary merger has to read through `keys` rather than as pairs."""

    def __init__(self, pairs):
        self._pairs = pairs

    def keys(self):
        return list(self._pairs)

    def __getitem__(self, key):
        return self._pairs[key]


# --- construction ---------------------------------------------------------------------

plain = types.SimpleNamespace(a=1, b="two")
print("kwargs", plain, vars(plain), plain.a, plain.b, type(plain).__name__)

given = {"x": 1, "y": 2}
from_dict = types.SimpleNamespace(given)
print("mapping", from_dict, vars(from_dict) is from_dict.__dict__)
print("mapping copies", vars(from_dict) is not given, given)

over = types.SimpleNamespace(given, y=9, z=3)
print("overwrite", over, list(vars(over)))

user = types.SimpleNamespace(Pairs({"u": 1, "v": 2}))
print("keys mapping", user)

pairs = types.SimpleNamespace([("p", 1), ("q", 2)])
print("pairs", pairs)

print("empty", types.SimpleNamespace())

# `__new__` is only the first half: the constructor's keywords are `__init__`'s work.
print("new alone", vars(types.SimpleNamespace.__new__(types.SimpleNamespace, a=1)))
print("new class", type(types.SimpleNamespace.__new__(types.SimpleNamespace)).__name__)

refusal("two positional", lambda: types.SimpleNamespace(1, 2))
refusal("int key", lambda: types.SimpleNamespace({1: 2}))
refusal("bad pair", lambda: types.SimpleNamespace([(1,)]))
refusal("short pair", lambda: types.SimpleNamespace([1]))
refusal("non-iterable", lambda: types.SimpleNamespace(1))

# --- the dictionary -------------------------------------------------------------------

ns = types.SimpleNamespace(a=1)
print("dict", ns.__dict__, vars(ns) is ns.__dict__)
ns.later = 2
print("grow", vars(ns), list(vars(ns)))
del ns.later
print("shrink", vars(ns))
refusal("missing delete", lambda: delattr(ns, "missing"))
refusal("missing read", lambda: ns.missing)
refusal("set dict", lambda: setattr(ns, "__dict__", {}))
refusal("delete dict", lambda: delattr(ns, "__dict__"))
print("after refusals", vars(ns))

descriptor = types.SimpleNamespace.__dict__["__dict__"]
print(
    "descriptor",
    type(descriptor).__name__,
    descriptor.__name__,
    descriptor.__objclass__ is types.SimpleNamespace,
)

# --- repr -----------------------------------------------------------------------------

ns.__dict__[1] = "not a name"
ns.__dict__[""] = "empty"
print("skipped keys", ns)
empty_key_only = types.SimpleNamespace()
empty_key_only.__dict__[""] = 1
print("only empty key", empty_key_only)

loop = types.SimpleNamespace()
loop.me = loop
print("recursive", loop)
pair = types.SimpleNamespace()
pair.first = 1
pair.second = pair
print("recursive inside", pair)

ordered = types.SimpleNamespace(one=1, two=2, three=3)
ordered.one = ordered.one
print("stable order", ordered)

# --- equality -------------------------------------------------------------------------


class Spam(types.SimpleNamespace):
    pass


class Ham(types.SimpleNamespace):
    pass


class NoDict(types.SimpleNamespace):
    __slots__ = ()


print("eq base", Spam(a=1) == types.SimpleNamespace(a=1))
print("eq reversed", types.SimpleNamespace(a=1) == Spam(a=1))
print("eq subclass", Spam(a=1) == Spam(a=1))
print("eq cross", Spam(a=1) == Ham(a=1))
print("ne value", Spam(a=1) != Spam(a=2))
print("ne type", Spam(a=1) != Ham(a=1))
print("eq slots", NoDict(a=1) == Spam(a=1))

# --- hashing --------------------------------------------------------------------------

refusal("hash base", lambda: hash(types.SimpleNamespace()))
refusal("hash subclass", lambda: hash(Spam()))

# --- subclass shapes ------------------------------------------------------------------


class Tagged(types.SimpleNamespace):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.tag = "tagged"


print("subclass repr", repr(Spam(a=1)), str(Spam(a=1)))
print("subclass init", Tagged(a=1), vars(Tagged(a=1)))
print("subclass dict", isinstance(Spam(a=1).__dict__, dict), vars(Spam(a=1)))
refusal("subclass set dict", lambda: setattr(Spam(), "__dict__", {}))
print("slots keeps dict", hasattr(NoDict(), "__dict__"), NoDict())
slotted = NoDict()
slotted.x = 1
print("slots writes", slotted, vars(slotted))
refusal("slots two positional", lambda: NoDict(1, 2))


class Halfway(types.SimpleNamespace):
    def __new__(cls, **kwargs):
        made = super().__new__(cls)
        print("super new", type(made).__name__, vars(made))
        return made


print("halfway", Halfway(a=1), vars(Halfway(a=1)))

# --- reduce and replace ---------------------------------------------------------------

original = Spam(a=1, b=[2, 3])
kind, args, state = original.__reduce__()
restored = kind(*args)
restored.__dict__.update(state)
print("reduce", kind.__name__, args, state)
print("rebuilt", restored, restored == original, type(restored).__name__)

replaced = original.__replace__(b=9)
print("replace", replaced, type(replaced).__name__, original)
print("replace keeps", types.SimpleNamespace(a=1).__replace__(a=2))

print("replace adds", original.__replace__(c=1))
