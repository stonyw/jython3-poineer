# M6-a slice 3: `weakref.ref` as a base class. A class written over it is made by the reference's
# own creator, so the object it refers to and the callback both arrive through `__new__`; the rows the
# base keeps in its dictionary are what `super().__call__()` and `__hash__ = ref.__hash__` reach.
#
# Nothing here lets a referent die for the same reason the M4 weakref corpus does not: when the JVM
# collects an object is not a thing a differential test can hold still. What is asked is everything
# a live reference says.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import weakref


class Thing:
    def __init__(self, n):
        self.n = n


class Reference(weakref.ref):
    pass


thing = Thing(7)
reference = Reference(thing)
print("instance", type(reference).__name__, isinstance(reference, weakref.ref))
print("call", reference() is thing, reference.__callback__)
print("mro", [base.__name__ for base in Reference.__mro__])
print("same creator", Reference.__new__ is weakref.ref.__new__)


def callback(ref):
    return ref


with_callback = Reference(thing, callback)
print("callback", with_callback.__callback__ is callback, with_callback() is thing)
print("callback row", type(weakref.ref.__dict__["__callback__"]).__name__)
try:
    reference.__callback__ = callback
except AttributeError as error:
    print("callback readonly", error)
print("subclass eq", reference == with_callback, reference == weakref.ref(thing))
print("subclass hash", hash(reference) == hash(weakref.ref(thing)))

made = weakref.ref.__new__(Reference, thing, callback)
print("base new", type(made).__name__, made.__callback__ is callback, made() is thing)

# A reference does not insist its callback be callable; it only asks for it at most once.
print("odd callback", Reference(thing, 1).__callback__)


class Method:
    def __init__(self):
        self.value = 41

    def answer(self):
        return self.value + 1


receiver = Method()
held = weakref.WeakMethod(receiver.answer)
print("weak method", held()(), type(held).__name__)
print("weak method eq", held == weakref.WeakMethod(receiver.answer))
print("weak method hash", isinstance(hash(held), int))

print("weak value", weakref.WeakValueDictionary({"k": thing})["k"] is thing)
print("weak key", weakref.WeakKeyDictionary({thing: "v"})[thing])
print("weak set", list(weakref.WeakSet([thing]))[0] is thing)

for row, action in [
    ("no arguments", lambda: Reference()),
    ("too many", lambda: Reference(thing, callback, 3)),
    ("keyword", lambda: Reference(thing, callback=callback)),
    ("not a subtype", lambda: weakref.ref.__new__(Thing, thing)),
    ("call with arguments", lambda: reference(1)),
]:
    try:
        action()
    except Exception as error:
        print(row, type(error).__name__, error)
