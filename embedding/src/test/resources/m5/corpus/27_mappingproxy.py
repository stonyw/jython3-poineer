"""`mappingproxy` as a window over the mapping it was handed, the slice 24 repair.

The window used to be built over a `PyDict` body, which meant it only worked for plain dicts:
a `ChainMap` had no body to take, a `__missing__` never ran, and a subclass that overrode
`__contains__`, `__iter__`, `__len__`, `get`, `copy`, `keys`, `items`, `values` or
`__getitem__` was answered around. The window now keeps the mapping itself and forwards each
protocol step to it, with the plain-dict path kept as a fast route.

This script walks that surface: which arguments construct one and which are refused, the
forwarded reads in a plain dict and in an overridden one, a `ChainMap` and a `__missing__`,
the live link the window has to mutations of the mapping, the read-only refusals, `reversed`,
`copy`, the union in both directions, the hash the mapping decides, and the comparison that
goes through the mapping's values rather than the window's identity.

The floating-point and integer `__format__` cases from the same slice are walked elsewhere;
the one left alone — `float('5\\0')` — has a root of its own in string parsing.
"""

import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
                   "Versions/3.14/lib/python3.14")

import collections
import types


def refusal(label, action):
    try:
        action()
    except BaseException as exc:
        print(label, type(exc).__name__, str(exc))
    else:
        print(label, "accepted")


MP = types.MappingProxyType

# --- construction ---------------------------------------------------------------------

plain = {'a': 1, 'b': 2}
view = MP(plain)
print("plain", repr(view), str(view), len(view), sorted(view))
print("plain equal", view == plain, plain == view, view == {'a': 1}, view == {'a': 2})


class Sub(dict):
    pass


sub = Sub(x=1)
print("subclass", sorted(MP(sub)), MP(sub) == sub)


class OnlyGetitem:
    def __getitem__(self, key):
        return "item:%s" % key


print("getitem only", MP(OnlyGetitem())['k'])

refusal("int", lambda: MP(10))
refusal("tuple", lambda: MP(("a", "tuple")))
refusal("list", lambda: MP(["a", "list"]))
refusal("set", lambda: MP({'a'}))


class OnlyLen:
    def __len__(self):
        return 1


refusal("len only", lambda: MP(OnlyLen()))
refusal("plain class", lambda: MP(OnlyGetitem))

# --- forwarding through a custom dict -------------------------------------------------

class customdict(dict):
    def __contains__(self, key):
        return True if key == "magic" else dict.__contains__(self, key)

    def __iter__(self):
        return iter(("iter",))

    def __len__(self):
        return 500

    def copy(self):
        return "copy"

    def keys(self):
        return "keys"

    def items(self):
        return "items"

    def values(self):
        return "values"

    def __getitem__(self, key):
        return "getitem=%s" % dict.__getitem__(self, key)

    def get(self, key, default=None):
        return "get=%s" % dict.get(self, key, 'default=%r' % default)


custom = MP(customdict({'key': 'value'}))
print("custom contains", 'key' in custom, 'magic' in custom, 'xxx' in custom)
print("custom item", custom['key'])
refusal("custom item miss", lambda: custom.__getitem__('xxx'))
print("custom iter", tuple(custom))
print("custom len", len(custom))
print("custom copy", custom.copy())
print("custom get", custom.get('key'), custom.get('xxx'))
print("custom views", custom.items(), custom.keys(), custom.values())

# --- __missing__ and ChainMap ---------------------------------------------------------

class dictmissing(dict):
    def __missing__(self, key):
        return "missing=%s" % key


missing = MP(dictmissing(x=1))
print("missing", missing['x'], missing['y'], missing.get('x'), missing.get('y'),
      missing.get('y', 42), 'x' in missing, 'y' in missing)

chain = MP(collections.ChainMap({'x': 1}, {'y': 2}))
print("chain", sorted(chain), chain['x'], chain['y'], 'z' in chain)
refusal("chain miss", lambda: chain['z'])
print("chain get", chain.get('x'), chain.get('y'), chain.get('z'))
print("chain views", sorted(chain.items()), sorted(chain.keys()), sorted(chain.values()))
chaincopy = chain.copy()
print("chain copy", type(chaincopy).__name__, chaincopy == collections.ChainMap({'x': 1}, {'y': 2}))

# --- the live link and the views ------------------------------------------------------

live = {}
window = MP(live)
keys, values, items = window.keys(), window.values(), window.items()
print("live empty", list(keys), list(values), list(items), len(window))
live['key'] = 'value'
print("live filled", list(keys), list(values), list(items), len(window), window['key'])
live['second'] = 2
print("live again", list(items), len(window))
del live['key']
print("live deleted", list(window), list(items), tuple(reversed(window)))

# --- read-only ------------------------------------------------------------------------

refusal("assign", lambda: window.__setitem__('x', 1))
refusal("delete", lambda: window.__delitem__('x'))

# --- copy -----------------------------------------------------------------------------

original = {'key1': 27, 'key2': 51}
copy = MP(original).copy()
print("copy", type(copy).__name__, copy == original)
original['key1'] = 70
print("copy detached", copy['key1'], MP(original)['key1'])

# --- union ----------------------------------------------------------------------------

mapping = {'a': 0, 'b': 1, 'c': 2}
bar = MP(mapping)
other = {'c': 3, 'p': 0}
print("union", bar | other)
print("union reflected", other | bar)
print("union self", bar | bar)
refusal("union int", lambda: bar | 3)
refusal("union list", lambda: bar | [('r', 2)])
refusal("union inplace", lambda: bar.__ior__([('r', 2)]))
print("union left alone", mapping == {'a': 0, 'b': 1, 'c': 2},
      other == {'c': 3, 'p': 0})

# --- comparison -----------------------------------------------------------------------

print("windows equal", MP({'a': 1}) == MP({'a': 1}), MP({'a': 1}) != MP({'a': 2}))
print("window vs dict", MP({'a': 1}) == {'a': 1}, {'a': 1} == MP({'a': 1}))
refusal("order", lambda: MP({}) < MP({}))
refusal("order value", lambda: MP({'a': 1}) < MP({'a': 2}))

# --- hash -----------------------------------------------------------------------------

refusal("unhashable", lambda: hash(MP({'a': 1})))


class HashableDict(dict):
    def __hash__(self):
        return 3844817361


hashed = HashableDict({'a': 1})
print("hash", hash(MP(hashed)) == hash(hashed))

# --- the method surface ----------------------------------------------------------------

print("methods", sorted(set(dir(MP({}))) - set(dir(object()))))
