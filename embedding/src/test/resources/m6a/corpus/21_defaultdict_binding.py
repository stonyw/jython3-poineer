"""A hand-built class files its methods as descriptors: `defaultdict` binds them on a read.

Its three members are `__init__`, `__missing__` and `__repr__`, and only `__repr__` is asked by `repr`
rather than by a name a program writes. When `__missing__` was a plain builtin in the class dictionary it
did not bind, so every missing key raised `KeyError`; when the same held for `__repr__`, a defaultdict
printed as a plain dict. `Task.__wakeup` is one caller: `defaultdict` is what a future's done-callbacks
live in, and the unbound method made the callback raise, which left `run_until_complete` waiting forever.
"""

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import collections

counts = collections.defaultdict(int)
print(counts["a"], counts["b"], counts["a"])
print(repr(counts))
print(dict(counts))

words = collections.defaultdict(list)
for word in "a b a".split():
    words[word].append(word.upper())
print(sorted(words.items()))

# The method itself is reachable and bound: it takes the key alone, not the value first.
print(counts.__missing__("c"), dict(counts))

# A factory that is not callable is no factory, and the key error is the plain one.
counts.default_factory = None
try:
    counts["d"]
except KeyError as exc:
    print("KeyError", exc)


class Sub(dict):
    """A Python subclass still answers through its own `__missing__`."""

    def __missing__(self, key):
        return "missing:" + key


print(Sub()["x"])


class Override(dict):
    """A `__getitem__` written in the class wins whole: `__missing__` is not asked after it."""

    def __getitem__(self, key):
        raise KeyError(key)

    def __missing__(self, key):
        return 7


try:
    Override()["k"]
except KeyError as exc:
    print("override raises", exc)
