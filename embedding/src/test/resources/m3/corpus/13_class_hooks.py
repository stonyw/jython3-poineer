"""The other half of the protocol: type() as a call, and the members a metaclass answers with."""


class Meta(type):
    @classmethod
    def __prepare__(mcs, name, bases):
        print("prepare", name)
        return {}

    def __new__(mcs, name, bases, namespace):
        print("new", name, bases)
        return type.__new__(mcs, name, bases, namespace)

    def __init__(cls, name, bases, namespace):
        print("init", name)
        type.__init__(cls, name, bases, namespace)

    def __call__(cls, *args, **kwds):
        print("call", cls.__name__, args, kwds)
        return type.__call__(cls, *args, **kwds)


Built = Meta("Built", (), {"go": lambda self: "gone"})
print(type(Built).__name__, Built().go())

Dynamic = type("Dynamic", (), {"describe": lambda self: "dynamic"})
print(type(Dynamic), Dynamic().describe())

try:
    type("OneArg")
except TypeError as exc:
    print("TypeError:", exc)

try:
    type("Two", (object,))
except TypeError as exc:
    print("TypeError:", exc)

try:
    type("Wrong", (), {}, extra=1)
except TypeError as exc:
    print("TypeError:", exc)

ViaKwarg = type("ViaKwarg", (), {})
print(type(ViaKwarg).__name__)

try:
    type("Cell", (), {}, metaclass=Meta)
except TypeError as exc:
    print("TypeError:", exc)

try:
    type("Quiet", (), {}, __qualname__="Outer.Quiet")
except TypeError as exc:
    print("TypeError:", exc)


class Numbers:
    def __class_getitem__(cls, item):
        return "generic %s %s" % (cls.__name__, item)


print(Numbers[int], Numbers["text"], Numbers[1, 2])
print(Numbers.__class_getitem__(str))

try:
    Numbers(0)[1]
except TypeError as exc:
    print("TypeError:", exc)


class Itemed(type):
    def __getitem__(cls, item):
        return "itemed %s by %s" % (cls.__name__, item)


class Held(metaclass=Itemed):
    pass


print(Held[int])

try:
    Held("no")
except TypeError as exc:
    print("TypeError:", exc)


class Chained(Itemed):
    def __getitem__(cls, item):
        return "chained " + str(item)


class Child(metaclass=Chained):
    pass


print(Child["x"], Held["y"])


class Checked(type):
    def __instancecheck__(cls, other):
        print("instancecheck", cls.__name__, other)
        return isinstance(other, int)


class Even(metaclass=Checked):
    pass


print(isinstance(3, Even), isinstance("x", Even))
print(isinstance(3, (Even, str)), isinstance("x", (str, Even)))


class Bound(type):
    @classmethod
    def __instancecheck__(cls, other):
        print("classmethod hook receiver", cls.__name__)
        return True


class Unbound(type):
    def __instancecheck__(cls, other):
        print("plain hook receiver", cls.__name__)
        return True


class HeldBound(metaclass=Bound):
    pass


class HeldPlain(metaclass=Unbound):
    pass


print(isinstance(1, HeldBound), isinstance(1, HeldPlain))


class SubChecked(type):
    def __subclasscheck__(cls, other):
        print("subclasscheck", cls.__name__, other)
        return other is int


class Kind(metaclass=SubChecked):
    pass


print(issubclass(int, Kind), issubclass(str, Kind))
print(issubclass(int, (str, Kind)))

try:
    print("hook first:", issubclass("not a class", Kind))
except TypeError as exc:
    print("TypeError:", exc)

try:
    issubclass(int, "not a class")
except TypeError as exc:
    print("TypeError:", exc)

try:
    isinstance(1, "not a type")
except TypeError as exc:
    print("TypeError:", exc)


class BodyHook:
    def __instancecheck__(cls, other):
        print("body hook reached")
        return True


print(isinstance(1, BodyHook), isinstance(1, BodyHook()))


class Registry(type):
    seen = []

    def __new__(mcs, name, bases, namespace):
        cls = type.__new__(mcs, name, bases, namespace)
        mcs.seen.append(name)
        return cls


class First(metaclass=Registry):
    pass


class Second(First):
    pass


print(Registry.seen)


def refused(label, call):
    try:
        print(label, call())
    except TypeError as exc:
        print(label, "TypeError:", exc)


refused("new one", lambda: type.__new__(type, "a"))
refused("new name", lambda: type.__new__(type, 1, (), {}))
refused("new bases", lambda: type.__new__(type, "a", {}, {}))
refused("new dict", lambda: type.__new__(type, "a", (), 3))
refused("init none", lambda: type.__init__(type("Empty", (), {})))
refused("init four", lambda: type.__init__(type("Full", (), {}), "a", (), {}, "extra"))
refused("init keyword", lambda: type.__init__(type("Keyed", (), {}), "a", (), {}, k=1))


class ReturnsValue(type):
    def __new__(mcs, name, bases, namespace):
        return "not a class at all"


print(ReturnsValue("N", (), {}))


class Proxy:
    @property
    def __class__(self):
        return int

    def asked(self, name):
        return "asked " + name


print(isinstance(Proxy(), int), isinstance(Proxy(), Proxy), isinstance(Proxy(), object))
print(Proxy().__class__ is int)


class Claims:
    __class__ = int


print(isinstance(Claims(), int), isinstance(Claims(), Claims))

try:
    print(Proxy().missing)
except AttributeError as exc:
    print("AttributeError:", exc)


class Asked:
    def __getattr__(self, name):
        return "asked " + name


print(Asked().__class__ is Asked, Asked().anything)
