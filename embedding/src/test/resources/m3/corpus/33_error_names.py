"""The two members a failed read leaves behind.

An attribute that is not there and a name that is not there both carry the thing they were looking for:
``AttributeError`` keeps the object as well as the name, ``NameError`` keeps the name alone. The members
belong to those two classes and to what derives from them, and the runtime fills them in where CPython's
``PyObject_GetAttr`` augments a failed read — which is why the error about a local variable the frame never
reached says nothing, and the error about an empty cell of an enclosing scope does.
"""


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


print("--- which classes carry the members")

print(describe("a made-up NameError", lambda: NameError("x").name))
print(describe("a made-up AttributeError", lambda: AttributeError("x").name))
print(describe("and its object", lambda: AttributeError("x").obj))
print(describe("a ValueError", lambda: ValueError("x").name))
print(describe("an UnboundLocalError", lambda: UnboundLocalError("x").name))


class Loud(NameError):
    pass


print(describe("a class that derives from NameError", lambda: Loud("x").name))

print("--- what a real failure fills in")


class Thing:
    value = 1


thing = Thing()

try:
    thing.valu
except AttributeError as error:
    print(describe("the name of a failed read", lambda: error.name))
    print(describe("the object it was read off", lambda: type(error.obj).__name__))
    print(describe("and the same object", lambda: error.obj is thing))

try:
    missing_name
except NameError as error:
    print(describe("the name of a missing global", lambda: error.name))
    print(describe("and no object with it", lambda: error.obj))

try:
    (1).bit_lenght
except AttributeError as error:
    print(describe("a built-in type's member", lambda: error.name))
    print(describe("read off an int", lambda: type(error.obj).__name__))


def unbound(flag):
    if flag:
        carried = 1
    return carried


try:
    unbound(False)
except UnboundLocalError as error:
    print(describe("a local the frame never reached", lambda: error.name))


def enclosing():
    held = 0

    def reads():
        return held

    del held
    return reads


try:
    enclosing()()
except NameError as error:
    print(describe("an empty cell still names itself", lambda: error.name))

print("--- the members answer to being written")

kept = NameError("x")
kept.name = "y"
print(describe("written", lambda: kept.name))
print(describe("and the arguments are untouched", lambda: kept.args))
del kept.name
print(describe("deleted", lambda: kept.name))
print(describe("and deleted again", lambda: delattr(kept, "name")))

made = AttributeError("m", 2)
made.obj = thing
print(describe("an object written on a made-up error", lambda: made.obj is thing))
print(describe("the repr says nothing of either", lambda: repr(NameError("m"))))
print(describe("and of a raised one", lambda: repr(made)))
