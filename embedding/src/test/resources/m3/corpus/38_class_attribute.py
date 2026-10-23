"""The one name every value answers, and the swap a write of it makes.

``__class__`` is filed once, on ``object``, and read as the type the value reports: a value that carries
nothing under the name is answered after its own type refuses, which is why an ``int``, a module and a
generator all reply, and why ``x.__class__ is type(x)`` holds for them. A class that files the name in its
own body owns it instead, so its instances read what it filed and a write of theirs lands in the instance
dictionary like any other name. ``dir()`` lists the name for every value whose type declares no ``__dir__``
of its own — a module is the one built-in type that does, and what it answers is only the names in its own
dictionary, so no question about ``dir()`` is asked of a module here.

The write is a fixed order of questions, and only the answer to the first one that refuses is ever heard.
Deleting refuses outright. A value that is not a class refuses by naming what was handed over. Then the
two classes involved are asked whether either is immutable — unless both are modules or subclasses of one —
because an interned ``1`` swapped to a written class would repaint every ``1`` in the program. Last comes
the layout: the class the value would inherit from has to be the one it already does, so a swap between two
unrelated written classes refuses while a swap to a subclass of the same shape is carried out, and the
refusal names the new class first.

A type object is itself a value, but a write on one is met by ``type.__setattr__`` before any of that: a
built-in type refuses every name it is given with the sentence that names the type, and a class written at
Python level is the only kind whose own type may be replaced."""
import sys

sys_module = sys


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


def assign(target, value):
    target.__class__ = value
    return type(target).__name__


def drop(target):
    del target.__class__
    return "gone"


def report(value):
    return [
        value.__class__.__name__,
        value.__class__ is type(value),
        getattr(value, "__class__") is type(value),
        hasattr(value, "__class__"),
        "__class__" in dir(value),
    ]


def stream():
    yield 1


class Plain:
    pass


class Meta(type):
    pass


class Sub(Meta):
    pass


class WithMeta(metaclass=Meta):
    pass


class Shadow:
    __class__ = int


class Animal:
    def speak(self):
        return "animal"


class Dog(Animal):
    def speak(self):
        up = super()
        return [
            type(up).__name__,
            up.__thisclass__.__name__,
            up.__self__.__class__.__name__,
            up.__self_class__.__name__,
        ]


class Left(Exception):
    pass


class Right(Exception):
    pass


class Point:
    def __init__(self, x, y):
        self.x = x
        self.y = y


def keyword(value):
    match value:
        case Point(__class__=kind):
            return "matched " + kind.__name__
        case _:
            return "no match"


print("--- the read, and the three other routes to the same answer")
for sample in [
    0,
    1.5,
    "text",
    b"bytes",
    (1,),
    [1],
    {1: 2},
    {1},
    slice(1, 2),
    range(3),
    None,
    True,
    describe,
    stream(),
    Plain,
    Plain(),
    Shadow(),
    Animal().speak,
    ValueError("held"),
]:
    print(describe("a value", lambda sample=sample: report(sample)))


print(describe(
    "a module",
    lambda: [
        sys_module.__class__.__name__,
        sys_module.__class__ is type(sys_module),
        getattr(sys_module, "__class__") is type(sys_module),
        hasattr(sys_module, "__class__"),
    ],
))


print("--- what the types themselves report")
print(describe("int", lambda: int.__class__.__name__))
print(describe("Plain", lambda: Plain.__class__.__name__))
print(describe("a class with a metaclass", lambda: WithMeta.__class__.__name__))
print(describe("object", lambda: object.__class__.__name__))
print(describe("every type is a type", lambda: [Plain.__class__ is type, int.__class__ is type]))


print("--- the write, in the order the questions are asked")
print(describe("delete of a value", lambda: drop(1)))
print(describe("delete of a written instance", lambda: drop(Plain())))
print(describe("delete of a built-in type", lambda: drop(int)))
print(describe("a class that is not a class", lambda: assign(1, "text")))
print(describe("an immutable type of a mutable one", lambda: assign(1, str)))
print(describe("a string to a list", lambda: assign("text", list)))
print(describe("a built-in type to a metaclass", lambda: assign(int, Meta)))
print(describe("a written class to a metaclass", lambda: assign(Plain, Meta)))
print(describe("a module to its own class", lambda: assign(sys_module, sys_module.__class__)))
print(describe("a module to a written class", lambda: assign(sys_module, Plain)))
print(describe("a metatype swap", lambda: assign(WithMeta, Sub)))
print(describe("after that swap", lambda: WithMeta.__class__.__name__))
print(describe("its own bases", lambda: [name.__name__ for name in WithMeta.__bases__]))


print("--- the layout question, and a swap it agrees to")
class Base:
    pass


class Derived(Base):
    pass


class Other:
    pass


print(describe("an unrelated swap of the same shape", lambda: assign(Base(), Other)))
print(describe("a swap to a subclass", lambda: assign(Base(), Derived)))
print(describe("its own class again", lambda: assign(Base(), Base)))
print(describe("a plain class to an exception class", lambda: assign(Base(), Left)))
print(describe("an exception class to a plain one", lambda: assign(Left(), Base)))

kept = Base()
kept.tag = "carried"
kept.other = 2
print(describe("before", lambda: [type(kept).__name__, sorted(vars(kept))]))
print(describe("swap", lambda: assign(kept, Derived)))
print(describe("after", lambda: [type(kept).__name__, isinstance(kept, Derived),
                                 isinstance(kept, Base), sorted(vars(kept))]))
print(describe("back", lambda: assign(kept, Base)))
print(describe("now", lambda: [type(kept).__name__, isinstance(kept, Derived), sorted(vars(kept))]))

held = Left("spam")
print(describe("held before", lambda: [type(held).__name__, held.args, str(held)]))
print(describe("held swap", lambda: assign(held, Right)))
print(describe("held after", lambda: [type(held).__name__, held.args, str(held)]))
try:
    raise held
except Right as caught:
    print(describe("caught as", lambda: [type(caught).__name__, caught.args, caught is held]))
except Left:
    print("caught as Left")
print(describe("still an exception", lambda: [isinstance(held, Left), isinstance(held, Right),
                                              isinstance(held, Exception)]))
print(describe("a built-in exception of a written one", lambda: assign(ValueError("one"), Left)))


print("--- a class that files the name owns it")
print(describe("read", lambda: Shadow().__class__.__name__))
print(describe("isinstance of what it files", lambda: isinstance(Shadow(), int)))
print(describe("of the class itself", lambda: Shadow.__class__.__name__))
shelter = Shadow()
print(describe("write of an instance that files", lambda: assign(shelter, str)))
print(describe("its dictionary", lambda: sorted(vars(shelter))))
print(describe("read again", lambda: shelter.__class__.__name__))
print(describe("delete of it", lambda: drop(shelter)))


print("--- the name reached by a pattern and by a zero-argument super")
print(describe("a keyword pattern", lambda: keyword(Point(1, 2))))
print(describe("a positional pattern", lambda: keyword(Plain())))
print(describe("a super made without arguments", lambda: Dog().speak()))
