"""The four names that answer for a scope: ``locals``, ``globals``, ``vars`` and ``dir``.

Two of them are about a frame and two about a value, and the frame ones are not the same shape of answer.
``globals()`` is the module's own dictionary, so a name written into it is a name the module has.
``locals()`` is that same dictionary at module level and inside a class body — which is why ``locals() is
globals()`` holds at module level and ``vars() is locals()`` holds in a body — but a plain snapshot of a
function's slots in 3.14, so a name written into *that* is a name nobody else sees. A cell that holds
nothing is left out of the snapshot and a free variable is in it, because a frame's slots make no
difference between the two.

``dir()`` with no argument sorts the names of the running scope, which is the frame's half of the same
question; with an argument it asks the value, and that is the ``__dir__`` slice. ``vars(value)`` is the
``__dict__`` read and nothing more, so a value with no dictionary of its own is what it refuses."""


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


print("--- the module's own scope")

first = 1
second = [2]

print("locals is globals:", locals() is globals())
print("vars is locals:", vars() is locals())
print("the names so far:", sorted(dir()))
print("dir is the sorted scope:", dir() == sorted(locals()))

globals()["written_through_globals"] = 3
print("a name the dictionary gave:", written_through_globals)
del globals()["second"]
print("a name taken away:", "second" in locals(), "second" in globals())


print("--- a function frame, and the short calls that fill its defaults")


def frame(one, two=2, *rest, **keywords):
    return (one, two, rest, keywords, sorted(locals()))


print(describe("frame(1)", lambda: frame(1)))
print(describe("frame(1, 2)", lambda: frame(1, 2)))
print(describe("frame(1, 2, 3, four=4)", lambda: frame(1, 2, 3, four=4)))
print(describe("frame(two=9, one=1)", lambda: frame(two=9, one=1)))
print(describe("frame()", lambda: frame()))


print("--- a snapshot that writes back to nothing")


def snapshot():
    held = locals()
    held["added"] = 1
    return "added" in locals(), held is locals(), held == locals(), sorted(locals())


print(snapshot())


print("--- the cells a scope shares")


def enclosing(given):
    if given:
        shared = 1

    def reader():
        return sorted(locals())

    return reader(), sorted(locals())


print("with the cell filled:", enclosing(True)[1], enclosing(True)[0])
print("with the cell empty:", enclosing(False)[1], enclosing(False)[0])


print("--- the frames a comprehension and a generator run in")


def scopes():
    inside = [sorted(locals()) for step in range(2)]
    given = 1
    yield inside
    yield sorted(locals())
    del given
    yield sorted(locals())


print(list(scopes()))
