"""super: the proxy that answers from the class after the one it was given, and the bytecode that
builds it from a method's own frame."""


def describe(label, thunk):
    try:
        return label + " -> " + str(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


class Speak:
    """The root of the hierarchy: every member below is reached by starting after some class."""

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

    @volume.deleter
    def volume(self):
        self.built = "deleted"


class Loud(Speak):
    def sound(self):
        return "loud over " + super().sound()

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

    def drop(self):
        del super().volume
        return self.built

    def rebuild(self):
        super().__init__()
        return self.built


class Quiet(Loud):
    def sound(self):
        return "quiet over " + super().sound()

    def __init__(self):
        super().__init__()
        self.built = self.built + "+Quiet"


class Left(Speak):
    def sound(self):
        return "left"


class Right(Speak):
    def sound(self):
        return "right"


class Bottom(Left, Right):
    def sound(self):
        return super().sound()

    def from_right(self):
        return super(Right, self).sound()

    def from_speak(self):
        return super(Speak, self).sound()


class Nested(Loud):
    """Where a no-argument super() looks: the frame that called it, and that frame's first argument."""

    def one(self):
        def inner(unused):
            return super().sound()

        return inner("not a Loud")

    def zero(self):
        def inner():
            return super().sound()

        return inner()

    def lambda_form(self):
        return (lambda: super().sound())()

    def comprehension(self):
        return [super().sound() for _ in range(2)]


class Reported(Loud):
    @classmethod
    def report(cls):
        return cls.__name__ + " over " + super().kind()


class Deleted(Loud):
    """A method that has handed its own first argument back, which is a third way to find nothing."""

    def call_form(self):
        del self
        return super()

    def attribute_form(self):
        del self
        return super().sound

    def captured_form(self):
        def inner():
            return self

        del self
        return super()

    def none_argument(self):
        return super()


def outside_any_class(self):
    """The deleted argument is reported before the cell is read, and this frame has no cell to read."""

    del self
    return super()


loud = Loud()
quiet = Quiet()
bottom = Bottom()
nested = Nested()
deleted = Deleted()
proxy = super(Right, bottom)

print(loud.sound(), "|", quiet.sound(), "|", bottom.sound())
print(loud.counted(), "|", loud.kinded(), "|", loud.read())
print(describe("super().volume = 9", lambda: loud.write(9)))
print(describe("del super().volume", lambda: loud.drop()))
print(loud.rebuild(), "|", quiet.built)
print("order:", [kind.__name__ for kind in Bottom.__mro__])
print(bottom.from_right(), "|", bottom.sound(), "|", describe("past Speak", bottom.from_speak))
print(describe("an inner frame's first argument", nested.one))
print(nested.comprehension())
print(describe("a frame with nothing to hand over", nested.zero))
print(describe("a lambda's frame", nested.lambda_form))
print(describe("super() after del self", deleted.call_form))
print(describe("super().sound after del self", deleted.attribute_form))
print(describe("a captured self, deleted", deleted.captured_form))
print(describe("deleted with no cell to read", lambda: outside_any_class(1)))
print(describe("a None argument is no argument", lambda: repr(Deleted.none_argument(None))))
print(Reported.report(), "|", Reported().report())

reached = loud.reached()
print(type(reached).__name__, reached.__qualname__, reached.__self__.__class__.__name__, reached())
print(loud.shape())
print(proxy.sound(), "|", type(proxy.__self__).__name__, "|", loud.reached.__name__)

print("names:", describe("proxy.__self__ is bottom", lambda: proxy.__self__ is bottom))
print("names:", describe("proxy.__self_class__ is Bottom", lambda: proxy.__self_class__ is Bottom))
print("names:", describe("proxy.__thisclass__ is Right", lambda: proxy.__thisclass__ is Right))
print("names:", describe("type(proxy).__name__", lambda: type(proxy).__name__))
print("names:", describe("isinstance(proxy, super)", lambda: isinstance(proxy, super)))
print("names:", describe("proxy.__class__ is super", lambda: proxy.__class__ is super))

print(describe("repr of a class proxy", lambda: repr(super(Right, Bottom))))
print(describe("repr with no value", lambda: repr(super(Right))))
print(describe("repr from a None value", lambda: repr(super(Right, None))))
print(describe("attribute of a valueless proxy", lambda: super(Right).sound))
print(describe("a name already passed", lambda: super(Speak, loud).sound))
print(describe("a name nowhere", lambda: super(Speak, loud).missing))
print(describe("call the proxy", lambda: super(Speak, loud)()))
print(describe("iterate the proxy", lambda: iter(super(Speak, loud))))
print(describe("write a name the parent owns", lambda: setattr(super(Speak, loud), "count", 1)))
print(describe("write __self__", lambda: setattr(proxy, "__self__", loud)))
print(describe("delete __self__", lambda: delattr(proxy, "__self__")))

print(describe("super() with no frame to ask", lambda: super()))
print(describe("one non-type", lambda: super(1)))
print(describe("two arguments of which one", lambda: super(1, 2)))
print(describe("a value not of the class", lambda: super(Bottom, Speak())))
print(describe("a class not a subtype", lambda: super(Bottom, Speak)))
print(describe("too many arguments", lambda: super(Speak, loud, loud)))
print(describe("a keyword", lambda: super(type=Speak)))

stolen = Loud.sound
Loud.sound = Speak.sound
print(describe("a method read by another class", lambda: stolen(bottom)))
Loud.sound = stolen
print(loud.sound())

# A built-in type keeps its members in a table rather than a class dictionary, and `bool` is the only
# order in which the walk ever reaches one: it passes `bool` and takes what `int` declares.
print(super(bool, True).bit_length(), "|", describe("nothing past int", lambda: super(int, 3).bit_length))
