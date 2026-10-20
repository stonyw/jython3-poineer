"""super the way a method writes it: the attribute form the compiler builds from the stack.

`10_super.py` names `super` in module-level code — `proxy = super(Right, bottom)` is enough — and that
one reference moves every implicit `super().name` in the whole module onto `LOAD_GLOBAL super` + `CALL
0` + `LOAD_ATTR name`, the path that asks the running frame for both halves. A module that leaves
`super` to its builtin self gets the other path instead: `LOAD_SUPER_ATTR`, which is handed the class
and the value on the value stack and answers the attribute itself. Everything below is that module, so
a name read out of a method runs through that instruction. Three forms still take the call even here: a
name write, a one-argument `super(C).name`, and a `super()` whose result is kept in a variable rather
than read straight through — `KeptAsValue` is where this file asks the frame.
"""


def describe(label, thunk):
    try:
        return label + " -> " + str(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


class Speak:
    def __init__(self):
        self.built = "Speak"

    def sound(self):
        return "generic"

    def count(self, value):
        return "Speak.count " + str(value)

    @classmethod
    def kind(cls):
        return "kind of " + cls.__name__

    @property
    def volume(self):
        return "at " + type(self).__name__

    @volume.setter
    def volume(self, value):
        self.built = "set " + str(value)


class Loud(Speak):
    def heard(self):
        return super().sound()

    def reached(self):
        return super().sound

    def shape(self):
        return type(super().sound).__name__

    def counted(self):
        return super().count(3)

    def kinded(self):
        return super().kind()

    def read(self):
        return super().volume

    def write(self, value):
        super().volume = value
        return self.built

    def named(self):
        return super(Speak).kind

    def from_loud(self):
        return super(Loud, self).sound()


class Left(Speak):
    def sound(self):
        return "left"


class Right(Speak):
    def sound(self):
        return "right"


class Bottom(Left, Right):
    def heard(self):
        return super().sound()

    def from_right(self):
        return super(Right, self).sound()

    def past_all(self):
        return super(Speak, self).sound()


class Deleted(Loud):
    """The attribute form reads the value off the stack, so a deleted `self` is an ordinary local read."""

    def attribute(self):
        del self
        return super().sound

    def called(self):
        del self
        return super().sound()


class KeptAsValue(Loud):
    """Where this module still asks the frame: `super()` with no attribute attached to it."""

    def held(self):
        proxy = super()
        return type(proxy).__name__ + " " + repr(proxy)

    def through_that(self):
        proxy = super()
        return proxy.sound()

    def written_through_that(self):
        proxy = super()
        proxy.built = "written"
        return self.built


class Shadowed(Loud):
    """Reads a name no class in the order holds, so only a `super` that is not the builtin answers."""

    def implicit_name(self):
        return super().upper()

    def explicit_name(self):
        return super(Loud, self).upper()


def build_a_proxy(value):
    """A function, not a method: the two-argument form read as an attribute, with no cell to ask."""

    return super(Left, value).count


loud = Loud()
bottom = Bottom()
deleted = Deleted()
kept = KeptAsValue()
shadowed = Shadowed()

print(loud.heard(), "|", bottom.heard(), "|", loud.counted(), "|", loud.kinded())
print(type(loud.reached()).__name__, loud.reached().__self__.__class__.__name__, loud.reached()())
print(loud.shape(), "|", loud.read(), "|", loud.from_loud())
print(describe("super(Speak).kind", loud.named))
print(describe("super().volume = 9", lambda: loud.write(9)), "|", loud.built)
print(bottom.from_right(), "|", describe("super(Speak, self).sound", bottom.past_all))

print(describe("super().sound after del self", deleted.attribute))
print(describe("super().sound() after del self", deleted.called))
print(kept.held())
print(describe("super() as a value, then read", kept.through_that))
print(describe("super() as a value, then written", kept.written_through_that))

print("a function's two-argument super:", build_a_proxy(bottom)(7))

# The fast path still has to look at what it loaded: `super` is only the special name while it is the
# builtin, and a module that replaces the builtin gets a call of whatever else is there instead — with
# no arguments for the implicit form and two for the explicit one, which is what these answers count.
import builtins

the_real_super = builtins.super


def pretend_to_be_super(*args):
    return "called with " + str(len(args)) + " arguments"


builtins.super = pretend_to_be_super
print(shadowed.implicit_name(), "|", shadowed.explicit_name())
builtins.super = Speak
print(describe("a class in super's place", shadowed.implicit_name))
builtins.super = the_real_super
print(loud.heard(), "|", type(loud.reached()).__name__, "|", describe("the same read for real", shadowed.explicit_name))
