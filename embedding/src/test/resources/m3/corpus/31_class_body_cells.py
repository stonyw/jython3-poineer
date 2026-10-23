"""A class body's own names, the cells around it, and the two questions it asks.

A class body nested in a function reads an enclosing local through `LOAD_FROM_DICT_OR_DEREF`: the body's own
namespace is asked first — which is what a `__prepare__` that hands back a dictionary already written fills
in — and the cell answers for every name nobody has written yet. A name the body itself assigns is not read
this way at all: the compiler sends that to `LOAD_NAME`, which is why the same spelling can name two
different objects in one body. A cell that holds nothing is refused with a sentence of its own — and it is a
`NameError`, not the `UnboundLocalError` a frame's own empty slot gets."""


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


print("--- the body reads the cell around it")


def enclosing(handed):
    shared = "the enclosing local"

    class Body:
        read = shared
        listed = [shared for _ in range(1)]
        from_a_method = staticmethod(lambda: shared)
        handed_in = handed

    return Body


built = enclosing("an argument")
print("   a name the body did not take:", describe("read", lambda: built.read))
print("   a comprehension reading it:", describe("listed", lambda: built.listed))
print("   a method reading it:", describe("lambda", lambda: built.from_a_method()))
print("   the body's own name:", describe("handed_in", lambda: built.handed_in))


print("--- the body takes the name over")


def shadowed():
    shared = "the enclosing local"

    class Body:
        shared = "the class's own"
        after = shared
        listed = [shared for _ in range(1)]

        def reads():
            return shared

    return Body


taken = shadowed()
print("   the body's own read:", describe("after", lambda: taken.after))
print("   a comprehension's:", describe("listed", lambda: taken.listed))
print("   a method's:", describe("reads", lambda: taken.reads()))


print("--- a name only the body has")


def its_own():
    class Body:
        own = "the class's own"

        def reads():
            return own

    return Body


print("   a method reading it:", describe("a nested body", lambda: its_own().reads()))


def its_own_comprehension():
    class Body:
        own = "the class's own"
        listed = [own for _ in range(1)]

    return Body


print("   a comprehension reading it:", describe("a body built", its_own_comprehension))


def its_own_generator():
    class Body:
        own = "the class's own"
        gen = (own for _ in range(1))

    return Body


made = its_own_generator()
print("   a generator asked later:", describe("drained", lambda: list(made.gen)))


print("--- a cell that holds nothing")


def not_yet():
    class Body:
        read = assigned_later

    assigned_later = "assigned after the body ran"
    return Body


print("   ", describe("the body runs first", not_yet))


def emptied_during():
    class Body:
        read = held

    held = "a value"
    return Body


print("   ", describe("the cell is filled after", emptied_during))


def emptied_after():
    held = "a value"

    class Body:
        def reads():
            return held

    del held
    return Body


cleared = emptied_after()
print("   ", describe("a method asked after the del", lambda: cleared.reads()))


print("--- the names a body is given")


def with_builtins():
    class Body:
        listed = [name for name in ("a", "b")]
        kind = int
        counted = len("four")

    return Body


given = with_builtins()
print("   the body's own comprehension:", describe("listed", lambda: given.listed))
print("   a builtin:", describe("kind", lambda: given.kind))
print("   a builtin called:", describe("counted", lambda: given.counted))


print("--- the dictionary the body is writing into")


def read_by_a_method():
    class Body:
        def reads(self):
            return sorted(k for k in __classdict__ if not k.startswith("__"))

        done = reads(None)
        later = 1

    return Body


built_late = read_by_a_method()
print("   read while the body was still running:", describe("done", lambda: built_late.done))
print("   the same read once it is a class:", describe("now", lambda: built_late().reads()))
built_late.named_after = "set on the class"
print("   a name set on the class afterwards:", describe("added", lambda: built_late().reads()))


print("--- a namespace the metaclass wrote before the body ran")


class Seeded(type):
    @staticmethod
    def __prepare__(name, bases, **kwds):
        return {"carried": "written before the body ran"}


def seeded():
    carried = "the enclosing local"

    class Body(metaclass=Seeded):
        read = carried

    return Body


from_the_seed = seeded()
print("   which of the two answers:", describe("read", lambda: from_the_seed.read))
print("   the name the metaclass gave:", describe("carried", lambda: "carried" in from_the_seed.__dict__))
