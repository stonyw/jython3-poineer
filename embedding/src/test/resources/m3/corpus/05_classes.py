"""A class, its attributes, the instances it makes and what each of them holds."""


def describe(label, thunk):
    try:
        return label + " -> " + str(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


class Point:
    """A place on the plane."""

    kind = "point"

    def __init__(self, x, y=0):
        self.x = x
        self.y = y

    def moved(self, dx, dy=0):
        return Point(self.x + dx, self.y + dy)

    def as_text(self):
        return "(" + str(self.x) + ", " + str(self.y) + ")"

    def __repr__(self):
        return "Point" + self.as_text()


p = Point(1, 2)
print(p, "|", str(p), "|", p.as_text())
print(Point(3), "|", Point(3, 4).as_text())
print(p.x, p.y, p.moved(1, 1), p.moved(2))

print("names:", Point.__name__, "|", Point.__qualname__, "|", Point.__doc__)
print("bases:", [t.__name__ for t in Point.__bases__], "| base:", Point.__base__.__name__)
print("mro:", [t.__name__ for t in Point.__mro__], "|", [t.__name__ for t in Point.mro()])
print("types:", type(p).__name__, "|", type(Point).__name__, "|", p.__class__ is Point)
print("checks:", isinstance(p, Point), isinstance(p, object), issubclass(Point, object))
print("in dict:", "kind" in p.__dict__, "kind" in Point.__dict__, hasattr(p, "kind"))


def delete_nope(value):
    del value.nope


print(describe("arity", lambda: Point(1, 2, 3)))
print(describe("keyword", lambda: Point(x=1, z=2)))
print(describe("missing", lambda: Point()))
print(describe("no such attribute", lambda: p.nope))
print(describe("no such class attribute", lambda: Point.nope))
print(describe("delete it anyway", lambda: delete_nope(p)))
print(describe("call the class as a function", lambda: Point(1)(2)))


class Counter:
    total = 0

    def __init__(self):
        Counter.total = Counter.total + 1

    def __repr__(self):
        return "Counter#" + str(Counter.total)


first = Counter()
second = Counter()
print(first, second, Counter.total)


class Sparse:
    pass


held = Sparse()
print("instance dict starts empty:", held.__dict__ == {})
held.label = "kept"
held.value = [1, 2]
print(sorted(held.__dict__), "|", held.label, held.value)
del held.label
print(sorted(held.__dict__), "|", hasattr(held, "label"))
print(describe("class does not hold it", lambda: Sparse.label))
Sparse.shared = "shared"
print(held.shared, Sparse.shared, "shared" in held.__dict__)
held.shared = "shadowed"
print(held.shared, Sparse.shared)
del held.shared
print(held.shared)

bound = p.as_text
print("bound:", bound.__name__, "|", bound.__self__ is p, "|", bound() == p.as_text())
plain = Point.as_text
print("plain:", type(plain).__name__, "|", plain.__name__, "|", plain(p))
print("same method twice:", p.as_text == p.as_text, "|", p.as_text == plain)


def nested():
    class Inside:
        def name(self):
            return type(self).__qualname__

    return Inside().name()


print("nested:", nested())
