"""What ``dir(value)`` asks, and what ``vars(value)`` reads.

``dir()`` of a value is the ``__dir__`` protocol: the member is looked up on the *type*, so a name the
instance itself carries is never asked; what it returns is sorted as it arrived, duplicates and all, and a
refusal inside it escapes rather than leaving the list empty. Only a value whose type declares no ``__dir__``
at all is answered by the default walk, which merges the instance dictionary and the dictionaries of the
lookup order into one *set* of names — a name the class and ``object`` both declare is offered once, and a
value whose ``__dict__`` is not a dictionary contributes nothing.

``vars(value)`` is that same ``__dict__`` read, returned as it is rather than copied, and refused with a
sentence of its own when there is nothing to read."""


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


print("--- the class body asks the same questions as a module")


class Body:
    alpha = 1
    beta = alpha + 1
    scope = sorted(locals())
    same = vars() is locals()
    listed = dir() == sorted(locals())
    outside = sorted(globals())[:2]


print(Body.scope, Body.same, Body.listed, Body.outside)


print("--- what a written __dir__ answers with")


class Repeater:
    def __dir__(self):
        return ["zulu", "alpha", "alpha", 3]


class Ordered:
    def __dir__(self):
        return ("tuple", "items", "alpha")


class Generator:
    def __dir__(self):
        yield from ["yielded", "alpha"]


class Defer:
    __dir__ = Repeater().__dir__


print(describe("a list that repeats and counts", lambda: dir(Repeater())))
print(describe("a tuple", lambda: dir(Ordered())))
print(describe("a generator", lambda: dir(Generator())))
print(describe("a member of another class", lambda: dir(Defer())))


print("--- the four ways a __dir__ can refuse")


class NotCallable:
    __dir__ = 5


class Nothing:
    __dir__ = None


class ThroughAProperty:
    @property
    def __dir__(self):
        return ["already a list"]


class WantsAnArgument:
    def __dir__(self, extra):
        return []


class Explodes:
    def __dir__(self):
        raise RuntimeError("the dir itself failed")


for name, value in [
    ("not callable", NotCallable()),
    ("None", Nothing()),
    ("a property that answered a list", ThroughAProperty()),
    ("an arity that wants a receiver and more", WantsAnArgument()),
    ("a dir that raises", Explodes()),
]:
    print(describe(name, lambda value=value: dir(value)))


print("--- who is asked, and who is not")


class Base:
    def __dir__(self):
        return ["inherited"]


class Child(Base):
    pass


carried = Child()
carried.__dir__ = lambda: ["from the instance"]
carried.held = 1

print(describe("a class that inherits one", lambda: dir(carried)))


class Metaclass(type):
    def __dir__(cls):
        return ["from the metaclass"]


class Managed(metaclass=Metaclass):
    __slots__ = ()

    mine = 1


print(describe("a class with a metaclass that answers", lambda: dir(Managed)))
print(describe("and one of its values", lambda: dir(Managed())))


print("--- the names the default walk merges, and the two it does not")


class Declared:
    __slots__ = ()

    def __str__(self):
        return "declared"

    def twice(self):
        return 2


print("dir of the class:", dir(Declared))
print("dir of a value:", dir(Declared()))
print("the class and its value differ by:", sorted(set(dir(Declared())) - set(dir(Declared()))) or "nothing")


class Shadowing:
    __slots__ = ()

    def __str__(self):
        return "shadowed"


print("a name object declares too, once:", dir(Shadowing).count("__str__"))


class Carrying:
    def __init__(self):
        self.first = 1
        self.second = 2


kept = Carrying()
print("vars is the dictionary itself:", vars(kept) is kept.__dict__)
print("the names it holds:", sorted(vars(kept)))
print("and the values:", sorted(vars(kept).values()))
vars(kept)["third"] = 3
print("a name written through vars:", kept.third, "third" in dir(kept))


class DictIsAList:
    __slots__ = ()

    @property
    def __dict__(self):
        return ["not", "a", "dictionary"]


print(describe("a __dict__ that is not a dictionary", lambda: dir(DictIsAList()) == dir(DictIsAList)))
print(describe("vars of a value with no dictionary", lambda: vars(1)))
print(describe("vars of a class", lambda: sorted(vars(Declared))))
print(describe("the two builtins that take no argument", lambda: (locals(1), globals(2))))
print(describe("a keyword for locals", lambda: locals(none=None)))
print(describe("a keyword for dir", lambda: dir(first=True)))
print(describe("too many for vars", lambda: vars(1, 2)))
