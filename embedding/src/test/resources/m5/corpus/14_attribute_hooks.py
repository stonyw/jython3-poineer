"""The four attribute hooks: `__getattribute__`, `__getattr__`, `__setattr__`, `__delattr__`.

A class that declares `__getattribute__` owns every read: the name is handed to it first, and the
generic walk only happens where the hook asks for it — either through `object.__getattribute__` or
through `super().__getattribute__`.  `__getattr__` is asked only after that generic half has
refused with an `AttributeError`, so a hook that raises anything else has answered already.
`__setattr__` and `__delattr__` replace the generic write and delete, and the last blocks put all
four hooks over `__slots__` (where there is no `__dict__` to fall back on) and over a data
descriptor, and check that `object.__getattribute__` called directly still skips the hook.
"""


def refusal(label, action):
    try:
        action()
    except Exception as exc:
        print(label, type(exc).__name__, str(exc))
    else:
        print(label, "accepted")


class Hooked:
    def __init__(self):
        self.x = 1

    def __getattribute__(self, name):
        print("get", name)
        if name == "magic":
            return 42
        return object.__getattribute__(self, name)

    def __getattr__(self, name):
        print("missing", name)
        return "hooked:" + name


h = Hooked()
print("read", h.x, h.magic, h.absent)
print("class", h.__class__.__name__)
print("generic", object.__getattribute__(h, "x"))
refusal("generic miss", lambda: object.__getattribute__(h, "absent"))
refusal("generic magic", lambda: object.__getattribute__(h, "magic"))


class Fallback:
    def __getattribute__(self, name):
        print("fallback get", name)
        if name == "denied":
            raise AttributeError("denied")
        return super().__getattribute__(name)

    def __getattr__(self, name):
        print("fallback missing", name)
        return "fallback:" + name


f = Fallback()
print("denied", f.denied)
print("fallback class", f.__class__.__name__)


class Explode:
    def __getattribute__(self, name):
        raise KeyError(name)

    def __getattr__(self, name):
        return "never"


refusal("other error", lambda: Explode().anything)


class Strict:
    def __getattribute__(self, name):
        return object.__getattribute__(self, name)


refusal("strict miss", lambda: Strict().nope)


class OnlyGetattr:
    def __getattr__(self, name):
        return "only:" + name


print("only", OnlyGetattr().any)
refusal("only explicit", lambda: object.__getattribute__(OnlyGetattr(), "any"))


class Guarded:
    def __init__(self):
        object.__setattr__(self, "value", 0)

    def __setattr__(self, name, value):
        if name != "value":
            print("blocked", name)
            raise AttributeError(name)
        print("set value", value)
        object.__setattr__(self, name, value)

    def __delattr__(self, name):
        print("delete", name)
        object.__delattr__(self, name)


g = Guarded()
g.value = 5
print("guard", g.value)
refusal("guard set", lambda: setattr(g, "other", 1))
del g.value
print("guard gone", hasattr(g, "value"))
refusal("guard del", lambda: delattr(g, "value"))


class Slotted:
    __slots__ = ("a", "b")

    def __getattribute__(self, name):
        print("slot get", name)
        return object.__getattribute__(self, name)

    def __setattr__(self, name, value):
        print("slot set", name, value)
        object.__setattr__(self, name, value)

    def __delattr__(self, name):
        print("slot del", name)
        object.__delattr__(self, name)


s = Slotted()
s.a = 3
print("slots", s.a, hasattr(s, "__dict__"))
del s.a
print("emptied", hasattr(s, "a"))
refusal("slot read", lambda: s.a)
refusal("slot undeclared", lambda: setattr(s, "c", 1))


class Descriptor:
    def __init__(self):
        self.reads = 0

    def __get__(self, obj, owner=None):
        if obj is None:
            return self
        self.reads += 1
        return "descriptor"

    def __set__(self, obj, value):
        print("descriptor set", value)


class WithDescriptor:
    field = Descriptor()

    def __getattribute__(self, name):
        print("descriptor get", name)
        return object.__getattribute__(self, name)

    def __setattr__(self, name, value):
        print("descriptor setattr", name)
        object.__setattr__(self, name, value)


w = WithDescriptor()
w.field = 9
print("descriptor", w.field, WithDescriptor.__dict__["field"].reads)
w.other = 4
print("other", w.other)


class Plain:
    def __init__(self):
        self.v = 1


p = Plain()
print("explicit", object.__getattribute__(p, "v"))
print("explicit class", object.__getattribute__(p, "__class__") is Plain)
refusal("explicit no name", lambda: object.__getattribute__(p, "nope"))


class Late:
    pass


def late_hook(self, name):
    print("late get", name)
    return object.__getattribute__(self, name)


def late_set(self, name, value):
    print("late set", name)
    object.__setattr__(self, name, value)


late = Late()
Late.__getattribute__ = late_hook
Late.__setattr__ = late_set
Late.extra = 2
print("late", late.extra)
