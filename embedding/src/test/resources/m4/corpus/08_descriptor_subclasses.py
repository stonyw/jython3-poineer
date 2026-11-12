# Subclassing the three descriptor builtins: `classmethod`, `staticmethod` and `property` are
# constructible types in CPython, so a class statement may derive from them — which is what
# `abc.abstractclassmethod` and its two siblings are. What a subclass of a wrapper is asked here
# is that it can be built, stored in a class body, and still act as the descriptor it inherited.

class MyClassMethod(classmethod):
    pass

class MyStaticMethod(staticmethod):
    pass

class MyProperty(property):
    pass

print("bases", issubclass(MyClassMethod, classmethod),
      issubclass(MyStaticMethod, staticmethod),
      issubclass(MyProperty, property))


class Point:
    def __init__(self, x, y):
        self._x = x
        self.y = y

    @MyClassMethod
    def unit(cls):
        return cls(1, 1)

    @MyStaticMethod
    def origin():
        return "origin"

    name = MyProperty(lambda self: "point", lambda self, value: setattr(self, "_name", value))


print("filed", type(Point.__dict__["unit"]).__name__,
      type(Point.__dict__["origin"]).__name__,
      type(Point.__dict__["name"]).__name__)
print("described", isinstance(Point.__dict__["unit"], MyClassMethod),
      isinstance(Point.__dict__["origin"], MyStaticMethod),
      isinstance(Point.__dict__["name"], MyProperty))

point = Point.unit()
print("classmethod", type(point) is Point, point._x, point.y)
print("staticmethod", Point.origin(), point.origin())
print("property", point.name)
point.name = "renamed"
print("setter", point._name)

print("metatype", type(MyClassMethod).__name__, MyClassMethod.__mro__[1].__name__)
print("documented", MyClassMethod.__doc__ is classmethod.__doc__,
      MyStaticMethod.__doc__ is staticmethod.__doc__)
