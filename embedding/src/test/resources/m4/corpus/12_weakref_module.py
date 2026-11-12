# `_weakref`: the module that hands out references which do not keep their referent alive. Nothing
# here lets a referent die — whether the JVM has collected it by the time a line runs is not something
# a differential test can hold still — so the script asks only what the reference says while the
# object is alive: that the two names are one type, that the three types are filed under `weakref`,
# that calling a reference gives the object back, and that a proxy answers through it. The refusals
# come last, including the one the dict helper answers with a value rather than an error.

import _weakref


class Thing:
    def __init__(self, n):
        self.n = n

    def double(self):
        return self.n * 2


def seven():
    return 7


print("keys", list(vars(_weakref)))
print("doc", _weakref.__doc__)
print("basic", _weakref.__name__, repr(_weakref.__package__), _weakref.__loader__.__name__,
      _weakref.__spec__.name, _weakref.__spec__.origin)
print("same", _weakref.ref is _weakref.ReferenceType)
print("names", _weakref.ref.__name__, _weakref.ref.__module__, _weakref.proxy.__module__)
print("types", repr(_weakref.ReferenceType), repr(_weakref.ProxyType),
      repr(_weakref.CallableProxyType))

thing = Thing(21)
reference = _weakref.ref(thing)
print("call", reference() is thing)
print("callback", reference.__callback__ is None)
print("count", _weakref.getweakrefcount(thing))
print("refs", _weakref.getweakrefs(thing) == [reference])
print("same referent", _weakref.ref(thing) == reference)
print("type", type(reference).__name__, type(reference).__module__)

proxy = _weakref.proxy(thing)
print("proxy type", type(proxy) is _weakref.ProxyType, type(proxy).__name__)
print("proxy forwards", proxy.n, proxy.double())
print("proxy equal", proxy == thing)
print("proxy callable", callable(proxy))

callable_proxy = _weakref.proxy(seven)
print("callable proxy", type(callable_proxy) is _weakref.CallableProxyType,
      callable(callable_proxy), callable_proxy())

mapping = {1: reference}
_weakref._remove_dead_weakref(mapping, 1)
print("live key kept", len(mapping))
_weakref._remove_dead_weakref({}, "absent")
print("absent key ignored", True)

for row, action in [
    ("int", lambda: _weakref.ref(1)),
    ("str proxy", lambda: _weakref.proxy("s")),
    ("hash", lambda: hash(proxy)),
    ("call args", lambda: reference(1)),
    ("not a dict", lambda: _weakref._remove_dead_weakref([], "k")),
]:
    try:
        action()
    except Exception as error:
        print(row, type(error).__name__, error)
