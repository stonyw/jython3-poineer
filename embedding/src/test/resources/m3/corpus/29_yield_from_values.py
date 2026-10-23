"""yield from: what goes into a delegation and what comes out of one.

A delegation is where `send`, `throw` and `close` stop belonging to the generator that was asked and
become the delegate's business. What ends it is a `StopIteration`, and the value it carries is the value of
the expression — which is why a delegate's `return` is readable, and why a `StopIteration` the delegate
raises by hand is a `RuntimeError` before it is ever a value."""


def describe(label, thunk):
    try:
        return label + " -> " + repr(thunk())
    except BaseException as error:
        return (label + " -> " + type(error).__name__ + ": " + str(error)
                + " | args " + repr(error.args)
                + " | cause " + type(error.__cause__).__name__)


print("--- a value the caller sends arrives in the delegate")


def listening():
    first = yield "waiting"
    second = yield "waiting"
    return "heard " + repr(first) + " then " + repr(second)


def relaying(source):
    got = yield from source
    yield "the delegation said " + got


talk = relaying(listening())
print("  first:", repr(next(talk)))
print("  second:", repr(next(talk)))
print("  send a value:", repr(talk.send("by send")))
print("  describe it:", describe("end", lambda: talk.send(19)))

quiet = relaying(listening())
print("  next hands None:", repr(next(quiet)), repr(next(quiet)), repr(next(quiet)))

print("--- the delegate does not have to be a generator")


class Items:
    def __init__(self, *values):
        self.left = list(values)
        self.heard = []

    def __iter__(self):
        return self

    def __next__(self):
        if not self.left:
            raise StopIteration("the list is empty")
        return self.left.pop(0)


things = Items("a", "b")


def over(source):
    got = yield from source
    yield "ended with " + repr(got)


print("  ", list(over(things)))
print("  a second time, with nothing left:", list(over(Items())))

print("--- the value the delegation ends with, four ways")


def returns(value):
    yield "one item"
    return value


def raises_by_hand(value):
    yield "one item"
    raise StopIteration(value)


def raises_empty():
    yield "one item"
    raise StopIteration


class HandStop:
    def __iter__(self):
        return self

    def __next__(self):
        raise StopIteration("from __next__")


print("  a return:", list(over(returns("the value"))))
print("  a StopIteration raised in a generator:", describe("pep 479", lambda: list(over(raises_by_hand("x")))))
print("  a bare StopIteration raised in a generator:", describe("pep 479", lambda: list(over(raises_empty()))))
print("  a StopIteration raised by an iterator:", list(over(HandStop())))

print("--- a throw goes to the delegate that is catching")


def guarded(source):
    try:
        got = yield from source
        yield "the delegation said " + got
    except ValueError as error:
        yield "the outer caught " + str(error)
    yield "and then the tail"


def catching():
    try:
        yield "in the delegate"
        yield "again"
    except ValueError as error:
        yield "the delegate caught " + str(error)
    return "delegate finished"


one = guarded(catching())
print("  ", repr(next(one)))
print("  ", repr(next(one)))
print("  throw:", repr(one.throw(ValueError("thrown"))))
print("  ", repr(next(one)))
print("  ", describe("end", lambda: next(one)))

two = guarded(Items("x"))
print("  throw with nothing under it:", describe("before the first step", lambda: two.throw(ValueError("no delegate handler"))))
print("  state:", two.gi_yieldfrom is None, two.gi_suspended, two.gi_frame is None)

print("--- a thrown StopIteration ends the delegation with its value")


def keeps(source):
    got = yield from source
    yield "expression was " + repr(got)


three = keeps(Items("p", "q"))
print("  ", repr(next(three)))
print("  throw StopIteration:", repr(three.throw(StopIteration("handed in"))))
print("  ", describe("end", lambda: next(three)))

four = keeps(Items("p"))
print("  ", repr(next(four)))
print("  a bare one:", repr(four.throw(StopIteration())))
print("  ", describe("end", lambda: next(four)))

print("--- the two finallys a broken delegation has to run")

marks = []


def body_with_finally():
    try:
        yield "first"
        yield "second"
    finally:
        marks.append("delegate finally")


def guard_with_finally(source):
    try:
        yield from source
        marks.append("outer after the delegation")
    finally:
        marks.append("outer finally")


normal = guard_with_finally(body_with_finally())
print("  the whole run:", describe("end", lambda: list(normal)), "| marks", marks)

marks.clear()
broken = guard_with_finally(body_with_finally())
print("  first step:", repr(next(broken)))
print("  throw:", describe("throw", lambda: broken.throw(ValueError("breaks it"))))
print("  marks:", marks)
print("  state:", broken.gi_yieldfrom is None, broken.gi_suspended)

marks.clear()
closed = guard_with_finally(body_with_finally())
print("  first step:", repr(next(closed)))
print("  close:", repr(closed.close()))
print("  marks:", marks)
print("  next after close:", describe("end", lambda: next(closed)))

marks.clear()
plain = guard_with_finally(Items("i", "j"))
print("  first step:", repr(next(plain)))
print("  close:", repr(plain.close()))
print("  marks:", marks)

print("--- a finally around the delegation, run by an exception made inside the delegate")

traveled = []


def fails_inside():
    yield "one"
    raise IndexError("made in the delegate")


def reports_from_finally(source):
    try:
        yield from source
    finally:
        traveled.append("the outer's finally")


attempt = reports_from_finally(fails_inside())
print("  ", repr(next(attempt)))
print("  ", describe("second", lambda: next(attempt)))
print("  marks:", traveled, "| state:", attempt.gi_yieldfrom is None, attempt.gi_suspended)
