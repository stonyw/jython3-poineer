"""Attributes that answer for themselves: __get__, __set__, __delete__, and the built-ins that are
nothing but that protocol."""


def describe(label, thunk):
    try:
        return label + " -> " + str(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


def del1(target, name):
    delattr(target, name)


class Doubled:
    """A plain descriptor: the class it was reached through arrives as the second argument."""

    def __init__(self, value):
        self.value = value

    def __get__(self, owner, klass):
        return "of " + klass.__name__ + " -> " + str(self.value * 2)


class Kept:
    """A data descriptor: assignment goes to it rather than into the instance dictionary."""

    def __init__(self):
        self.stored = {}

    def __set_name__(self, owner, name):
        self.name = name

    def __get__(self, owner, klass):
        return "read " + self.name + " " + str(self.stored.get(owner, "nothing"))

    def __set__(self, owner, value):
        self.stored[owner] = "wrote " + str(value)


class Silent:
    """A descriptor that only reads, so a write to the same name shadows it."""

    def __get__(self, owner, klass):
        return "silent"

    def __set_name__(self, owner, name):
        print("named", name, "on", owner.__name__)


class Shadow:
    read = Doubled(21)
    keep = Kept()
    quiet = Silent()


thing = Shadow()
print(thing.read, Shadow.read)
print(thing.keep, "|", Shadow.keep)
thing.keep = 7
print(thing.keep, "keep" in thing.__dict__)
print(Shadow.__dict__["keep"].stored.get(thing, "gone"))
thing.quiet = "mine"
print(thing.quiet, Shadow.quiet)
del thing.quiet
print(thing.quiet)


class Named:
    first = Silent()

    def __init__(self):
        self.rest = Silent()

    second = Silent()


print(Named().second, hasattr(Named(), "first"), hasattr(Named(), "rest"))


class Measured:
    def __init__(self, start):
        self._start = start

    @property
    def span(self):
        return "span " + str(self._start)

    @span.setter
    def span(self, value):
        self._start = value * 2

    @span.deleter
    def span(self):
        self._start = -1

    @property
    def fixed(self):
        return "fixed"

    @property
    def documented(self):
        "the docstring is the property's"
        return 1


m = Measured(3)
print(m.span, type(Measured.span).__name__, m._start)
m.span = 5
print(m.span, m._start)
del m.span
print(m.span, m._start)
print(Measured.span.fget(m), Measured.span.fset is not None, Measured.span.fdel is not None)
print(Measured.fixed.fget(m), Measured.documented.__doc__)
print(Measured.fixed.fset, Measured.fixed.fdel, Measured.span.fdel is not None)
print(describe("no setter", lambda: setattr(m, "fixed", 1)))
print(describe("no deleter", lambda: del1(m, "fixed")))


class Scores:
    @classmethod
    def named(cls, label):
        return label + " of " + cls.__name__

    @staticmethod
    def weight(at):
        return at * 10

    def both(self):
        return self.named("bound") + " " + str(self.weight(2))


print(Scores.named("direct"), Scores().named("through value"), Scores().both())
print(Scores.__dict__["named"].__get__(None, Scores)("via __get__"))
print(callable(Scores.__dict__["weight"]), Scores.__dict__["weight"].__func__(9))
print(Scores.__dict__["named"].__func__(Scores, "raw"))


class Loud(Scores):
    pass


print(Loud.named("inherited"), Loud().named("as well"))
print(Scores.named.__func__ is Loud.named.__func__, type(Scores.named), type(Scores().named))


class Base:
    @property
    def value(self):
        return "from base"


class Child(Base):
    pass


print(Child().value, type(Child.value).__name__)


class Getter:
    def __init__(self, label):
        self.label = label

    def __get__(self, owner, klass):
        return self.label


class Both:
    field = Getter("found")


one, two = Both(), Both()
print(one.field, Both.field, two.field)
Both.field = Getter("replaced")
print(one.field)
del Both.field
print(describe("gone from the class", lambda: one.field))
print(describe("no owner at all", lambda: Getter("x").__get__(None, None)))
print(describe("a slot descriptor", lambda: Getter("y").__set__(one, 1)))
print(describe("int method as descriptor", lambda: int.bit_length.__get__(3)()))


class SetOnly:
    """A descriptor with a writing half but no deleting one, so deleting is refused by the slot."""

    def __get__(self, owner, klass):
        return "set only"

    def __set__(self, owner, value):
        print("stored", value)


class DeleteOnly:
    """The same the other way round, which still outranks the instance dictionary on a write."""

    def __get__(self, owner, klass):
        return "delete only"

    def __delete__(self, owner):
        print("removed")


class Half:
    writes = SetOnly()
    reads = DeleteOnly()


pair = Half()
pair.writes = 1
print(describe("no deleting half", lambda: del1(pair, "writes")))
print(describe("no writing half", lambda: setattr(pair, "reads", 2)))
print(pair.writes, pair.reads, "writes" in pair.__dict__)


class Topped:
    attr = Doubled(1)


print(Topped.attr)
Topped.attr = 9
print(Topped.attr, Topped.__dict__["attr"])
del Topped.attr
print(describe("gone from the top", lambda: Topped.attr))


class Holder:
    field = Getter("held")


print(Holder.__dict__["field"].__get__(None, Holder), Holder.field)
