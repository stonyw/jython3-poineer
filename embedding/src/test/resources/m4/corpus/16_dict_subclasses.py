# A dict subclass is two stores in one object: the entries every dict has, and the instance
# dictionary only a subclass gets. This script pins the difference — `vars(d)` can be empty while
# `len(d)` is not — and then pins which spelling of each operation goes through the class's own
# `__setitem__`/`__getitem__` and which goes straight to the entries, because that is what
# `enum.EnumDict` depends on: the class body's assignments must reach its `__setitem__`.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)


class D(dict):
    pass


d = D({"a": 1}, b=2)
print("type", type(d).__name__, isinstance(d, dict), D.__bases__)
print("entries", len(d), sorted(d), d["a"], d.get("b"), d.get("nope", 7))
print("instance dict", vars(d), d.__dict__ is vars(d))
d.stamp = "kept"
print("two stores", sorted(vars(d)), len(d), "stamp" in d)
d["c"] = 3
print("setitem", d["c"], sorted(d), d.update({"d": 4}), sorted(d))
print("update kwargs", d.update(e=5), d.get("e"), sorted(d))

print("get", d.get("a"), d.get("zzz"), d.get("zzz", 0))
print("views", sorted(d.keys()), sorted(d.values()), sorted(d.items()))
print("in", "a" in d, "zzz" in d, bool(d), bool(D()))
print("pop", d.pop("a"), d.pop("zzz", 9), sorted(d))
print("setdefault", d.setdefault("f", 6), d.setdefault("f", 60), d["f"])
print("delete", sorted(d.items()))
del d["b"]
print("deleted", sorted(d))

copy = d.copy()
print("copy", type(copy).__name__, sorted(copy))
print("dict()", type(dict(d)).__name__, sorted(dict(d)))
print("fromkeys", type(D.fromkeys([1, 2])).__name__, sorted(D.fromkeys([1, 2])))
print("fromkeys value", D.fromkeys([1], []), D.fromkeys([1], [])[1] is None)
print("plain fromkeys", type(dict.fromkeys([1])).__name__)

print("eq", D(a=1) == {"a": 1}, {"a": 1} == D(a=1), D(a=1) == D(a=1), D(a=1) == D(a=2))
print("or", d | {"z": 26}, type(d | {}).__name__)
print("ior")
d |= {"y": 25}
print("ior result", type(d).__name__, d["y"])
print("repr", repr(D(a=1)), str(D(a=1)))


class Watched(dict):
    def __setitem__(self, key, value):
        print("store", key, value)
        super().__setitem__(key, value)

    def __getitem__(self, key):
        print("read", key)
        return super().__getitem__(key)


w = Watched()
w["a"] = 1
print("watched item", w["a"])
w.update(b=2)
w.setdefault("c", 3)
print("update bypasses setitem", sorted(w.items()))
print("dict.__getitem__ bypasses getitem", dict.__getitem__(w, "a"))
print("direct read", dict.get(w, "b"))


class Sized(dict):
    def __len__(self):
        return 100

    def __contains__(self, key):
        return key == "a"


print("slots", len(Sized(a=1)), "a" in Sized(), "b" in Sized())


class Missing(dict):
    def __missing__(self, key):
        return "no " + key


m = Missing(a=1)
print("missing", m["a"], m["zzz"], m.get("zzz"))


class Unhashable(dict):
    __hash__ = None


try:
    {Unhashable(): 1}
except TypeError as exc:
    print("hash", type(exc).__name__, exc)


class Custom(dict):
    def __init__(self, **kw):
        super().__init__()
        self.names = sorted(kw)
        self.update(kw)


c = Custom(x=1, y=2)
print("init", c.names, sorted(c), sorted(vars(c)))

try:
    D(a=1) < D(a=2)
except TypeError as exc:
    print("order", type(exc).__name__, exc)


# The class statement writes every name through the namespace `__prepare__` returned, which is the
# road `enum.EnumDict.__setitem__` takes to see each member. A plain dict would see nothing.
class Recording(dict):
    def __setitem__(self, key, value):
        if not key.startswith("__"):
            print("record", key, type(value).__name__)
        super().__setitem__(key, value)


class Meta(type):
    @classmethod
    def __prepare__(mcs, name, bases, **kwds):
        print("prepare", name, sorted(kwds))
        return Recording()

    def __new__(mcs, name, bases, ns, **kwds):
        print("new", name, type(ns).__name__, sorted(ns), sorted(kwds))
        return super().__new__(mcs, name, bases, dict(ns))


class Recorded(metaclass=Meta, flag=7):
    first = 1
    second = first + 1

    def method(self):
        return Recorded.first


print("recorded", Recorded.first, Recorded.second, Recorded().method(), Recorded.__class__.__name__)
print("class dict", type(Recorded.__dict__).__name__)


class Two(dict):
    def __init__(self, first, second):
        super().__init__()
        self["first"] = first
        self["second"] = second


print("positional init", sorted(Two(1, 2).items()), Two(1, 2)["second"])
