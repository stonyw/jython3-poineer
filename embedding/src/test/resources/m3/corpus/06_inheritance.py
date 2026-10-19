"""Deriving a class from another, and what each of them sees when a name is asked of an instance."""


def describe(label, thunk):
    try:
        return label + " -> " + str(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


class Animal:
    legs = 4

    def __init__(self, name):
        self.name = name

    def speak(self):
        return self.name + " says nothing"

    def describe(self):
        return type(self).__name__ + ": " + self.speak()


class Dog(Animal):
    def speak(self):
        return self.name + " woofs"


class Puppy(Dog):
    def speak(self):
        return self.name + " yips"


rex = Dog("Rex")
bud = Puppy("Bud")
print(rex.describe(), "|", bud.describe())
print(Animal.describe(rex), "|", Animal.describe(bud))
print(rex.name, rex.legs, Dog.legs, Animal.legs)
print([t.__name__ for t in Puppy.__mro__])
print([t.__name__ for t in Dog.__bases__], "|", Puppy.__base__.__name__)
print("checks:", isinstance(bud, Animal), isinstance(bud, Dog), isinstance(rex, Puppy))
print("subclass:", issubclass(Puppy, Animal), issubclass(Animal, Puppy))
print("tuple:", isinstance(rex, (Puppy, int)), issubclass(Dog, (int, Animal)))
print("of a class:", type(Dog).__name__, isinstance(Dog, type))


class Counted(Animal):
    def __init__(self, name, tricks):
        Animal.__init__(self, name)
        self.tricks = tricks

    def speak(self):
        return self.name + " knows " + str(self.tricks)


printed = Counted("Rex", 3)
print(printed.name, printed.tricks, printed.speak())
print(describe("inherited init still checks its own shape", lambda: Counted("no tricks")))


class Left:
    label = "left"

    def side(self):
        return self.label + " side"


class Right:
    label = "right"

    def right_only(self):
        return "only on the right"


class Both(Left, Right):
    pass


joined = Both()
print(joined.label, "|", joined.side(), "|", joined.right_only())
print([t.__name__ for t in Both.__mro__])
Both.label = "both"
print(joined.label, Left.label, Right.label)
del Both.label
print(joined.label)


class Base:
    def show(self):
        return "base"


class Middle(Base):
    pass


class Leaf(Middle):
    def show(self):
        return "leaf over " + Middle.show(self)


print(Leaf().show(), [t.__name__ for t in Leaf.__mro__])
print("same class twice:", describe("twice", lambda: type("Twice", (Base, Base), {})))
print("order:", describe(
    "base after its own subclass", lambda: type("Bad", (Base, Middle), {})))
print("empty class body:", describe(
    "made by type()", lambda: [t.__name__ for t in type("Empty", (), {}).__mro__]))


class Deep(Animal):
    legs = 2


print(Deep("hen").describe(), "|", Deep.legs, Animal.legs, Deep("hen").legs)
