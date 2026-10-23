"""yield from: the protocols a delegate is asked to answer, and the ones it is allowed to refuse.

Once a delegation starts, the generator that was written stops being the one that is driven. A `send`
becomes a call on the delegate, a `throw` becomes a call on the delegate, a `close` closes the delegate
first. What the delegate refuses — no `send`, an `__iter__` that hands back something that cannot be
iterated, a `close` that raises — is the answer the caller gets, with the delegation still under it."""


def describe(label, thunk):
    try:
        return label + " -> " + repr(thunk())
    except BaseException as error:
        return (label + " -> " + type(error).__name__ + ": " + str(error)
                + " | args " + repr(error.args)
                + " | cause " + type(error.__cause__).__name__)


class Tracked:
    def __init__(self, *values):
        self.left = list(values)
        self.calls = []

    def __iter__(self):
        self.calls.append("iter")
        return self

    def __next__(self):
        if not self.left:
            raise StopIteration("spent")
        return self.left.pop(0)

    def send(self, value):
        self.calls.append("send " + repr(value))
        return "answered " + repr(value)

    def throw(self, *args):
        self.calls.append("throw " + repr(args))
        raise args[0]

    def close(self):
        self.calls.append("close")


class OneArgument:
    def __init__(self, *values):
        self.left = list(values)

    def __iter__(self):
        return self

    def __next__(self):
        if not self.left:
            raise StopIteration()
        return self.left.pop(0)

    def throw(self, error):
        raise error


class RefusesClose:
    def __iter__(self):
        return self

    def __next__(self):
        return "endless"

    def close(self):
        raise IndexError("refused at close")


class Closing:
    def __init__(self):
        self.closed = False

    def __iter__(self):
        return self

    def __next__(self):
        if self.closed:
            raise StopIteration("closed")
        return "open"

    def close(self):
        self.closed = True


def delegating(source):
    got = yield from source
    yield "after " + repr(got)


print("--- what the delegate is called with")

tracked = Tracked("a", "b")
outer = delegating(tracked)
print("  ", describe("next", lambda: next(outer)))
print("  ", describe("send", lambda: outer.send("handed over")))
print("  ", describe("throw", lambda: outer.throw(ValueError("thrown in"))))
print("  the delegate recorded:", tracked.calls)
print("  and the outer keeps:", outer.gi_yieldfrom is None, outer.gi_suspended, outer.gi_frame is None)

print("--- a delegate with no send to give")


class Plain:
    def __init__(self, *values):
        self.left = list(values)

    def __iter__(self):
        return self

    def __next__(self):
        if not self.left:
            raise StopIteration()
        return self.left.pop(0)


asked = delegating(Plain("x", "y"))
print("  ", describe("next", lambda: next(asked)))
print("  ", describe("send", lambda: asked.send("value")))
print("  the outer after the refusal:", describe("next", lambda: next(asked)))

print("--- a throw that the delegate answers with its own argument")

single = delegating(OneArgument("p", "q"))
print("  ", describe("next", lambda: next(single)))
print("  ", describe("throw", lambda: single.throw(TypeError("one argument"))))

print("--- a throw whose delegate has no throw at all")

bare = delegating(Plain("m", "n"))
print("  ", describe("next", lambda: next(bare)))
print("  ", describe("throw", lambda: bare.throw(KeyError("no throw"))))

print("--- an iterable that will not become an iterator")


class NotAnIterator:
    def __iter__(self):
        return "a string is not an iterator"


class RefusesAtIter:
    def __iter__(self):
        raise KeyError("refused at __iter__")


print("  ", describe("a string", lambda: list(delegating("ab"))))
print("  ", describe("an int", lambda: list(delegating(3))))
print("  ", describe("None", lambda: list(delegating(None))))
print("  ", describe("a dict", lambda: list(delegating({"only": 1}))))
print("  ", describe("a generator", lambda: list(delegating(i for i in "z"))))
print("  ", describe("__iter__ returns a str", lambda: list(delegating(NotAnIterator()))))
print("  ", describe("__iter__ raises", lambda: list(delegating(RefusesAtIter()))))

print("--- the refusal is answered by the step that starts the delegation")

late = delegating(3)
print("  ", describe("first", lambda: next(late)))
print("  ", describe("again", lambda: next(late)))
print("  the outer keeps:", late.gi_yieldfrom is None, late.gi_suspended, late.gi_frame is None)

print("--- a close that belongs to the delegate")

will_close = delegating(Closing())
print("  ", describe("next", lambda: next(will_close)))
print("  ", describe("close", lambda: will_close.close()))
print("  the delegate was told:", will_close.gi_yieldfrom is None, describe("next", lambda: next(will_close)))

refused = delegating(RefusesClose())
print("  ", describe("next", lambda: next(refused)))
print("  ", describe("close refused", lambda: refused.close()))
print("  the outer keeps:", describe("next", lambda: next(refused)))

print("--- a step asked of a generator that is already running")

driven = []


class WhileRunning:
    def __iter__(self):
        return self

    def __next__(self):
        print("    ", describe("next", lambda: next(driven[0])))
        print("    ", describe("send", lambda: driven[0].send("in")))
        print("    ", describe("close", lambda: driven[0].close()))
        print("    ", describe("throw", lambda: driven[0].throw(ValueError("again"))))
        raise StopIteration("finished")


def runs():
    yield from WhileRunning()
    yield "the tail"


driven.append(runs())
print("  the whole run:", describe("end", lambda: list(driven[0])))

print("--- the same delegate handed over twice")


def twice(source):
    yield from source
    yield from source
    yield "the tail"


shared = Plain("p", "q")
print("  ", describe("twice", lambda: list(twice(shared))))
print("  ", describe("spent", lambda: list(twice(shared))))
print("  the delegate ran out in the middle:", describe("once", lambda: list(twice(Plain()))))

print("--- which delegate the outer is standing on")


def middle(source):
    yield from source


def top(source):
    yield from middle(source)


class Marker:
    def __init__(self, *values):
        self.left = list(values)

    def __iter__(self):
        return self

    def __next__(self):
        if not self.left:
            raise StopIteration()
        return self.left.pop(0)


marker = Marker("one", "two")
walking = top(marker)
print("  ", repr(next(walking)), "| top stands on a generator:", type(walking.gi_yieldfrom).__name__)
print("  ", repr(next(walking)), "| and the middle stands on the marker:",
      walking.gi_yieldfrom.gi_yieldfrom is marker)
print("  ", describe("end", lambda: next(walking)), "| nothing under it now:", walking.gi_yieldfrom)
