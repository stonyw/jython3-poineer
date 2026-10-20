"""The metaclass protocol: what a class statement asks, in what order, and with what answers."""


class Recorder(type):
    @classmethod
    def __prepare__(mcs, name, bases, **kwds):
        print("prepare", mcs.__name__, name, bases, kwds)
        return {"seed": name.lower()}

    def __new__(mcs, name, bases, namespace, **kwds):
        keys = sorted([k for k in namespace if not k.startswith("_")])
        print("new", mcs.__name__, name, bases, keys)
        return type.__new__(mcs, name, bases, namespace)

    def __init__(cls, name, bases, namespace, **kwds):
        print("init", cls.__name__, name, kwds)
        type.__init__(cls, name, bases, namespace)

    def __call__(cls, *args, **kwds):
        print("call", cls.__name__, args, kwds)
        return type.__call__(cls, *args, **kwds)


class Tracked(metaclass=Recorder):
    attr = 1

    def value(self):
        return self.attr


print(type(Tracked).__name__, Tracked.__class__ is Recorder)
print(Tracked.seed, Tracked.attr)
print(Tracked().value())

try:
    Tracked(1, key=3)
except TypeError as exc:
    print("TypeError:", exc)


class Sub(Tracked, note="child"):
    pass


print(type(Sub).__name__, Sub.seed, Sub().value())
print([c.__name__ for c in Sub.__mro__])


class Plain(Sub):
    """No metaclass= and no class kwargs: the base's metaclass wins, quietly."""


print(type(Plain).__name__, Plain.seed)


class Again(Plain, metaclass=Recorder, tag="explicit"):
    pass


print(type(Again).__name__, Again.seed)


class M1(type):
    pass


class M2(type):
    pass


class A(metaclass=M1):
    pass


class B(metaclass=M2):
    pass


try:

    class Both(A, B):
        pass

except TypeError as exc:
    print("TypeError:", exc)

try:

    class BothFixed(A, B, metaclass=M1):
        pass

except TypeError as exc:
    print("TypeError:", exc)


class Watched:
    def __init_subclass__(cls, **kwds):
        print("subclass hook", cls.__name__, sorted(kwds))

    def name(self):
        return "watched"


class Traced(Watched, label="t"):
    pass


print(Traced().name())

try:

    class Refused(Watched, 2):
        pass

except TypeError as exc:
    print("TypeError:", exc)

try:

    class Extra(Watched, unexpected=True):
        pass

except TypeError as exc:
    print("TypeError:", exc)


class OnlyPositional(Watched):
    def __init_subclass__(cls, tag):
        print("positional hook", cls.__name__, tag)


try:

    class MissingTag(OnlyPositional):
        pass

except TypeError as exc:
    print("TypeError:", exc)


class WithTag(OnlyPositional, tag="given"):
    pass


class Unwrapped(type):
    def __prepare__(mcs, name, bases):
        print("this never runs")
        return {}


try:

    class Trapped(metaclass=Unwrapped):
        pass

except TypeError as exc:
    print("TypeError:", exc)


class TwoParameter(type):
    def __prepare__(name, bases):
        print("prepare takes the name where mcs belongs", name, bases)
        return {"seed": "silent"}


class Silent(metaclass=TwoParameter):
    pass


print(Silent.seed)
