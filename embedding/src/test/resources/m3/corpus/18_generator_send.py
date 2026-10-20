"""send: the value a yield expression answers with, and the rules about who may hand one over.

A generator that has not started has nothing to receive, so its first step only takes None; a generator
that has started parks on a yield, and the next `send` becomes the value of that expression. `next` is
the same step with nothing to hand over, which is why it leaves None behind."""


def describe(label, thunk):
    try:
        return label + " -> " + repr(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + repr(error.args)


print("--- the first step only takes None")


def echo(label):
    got = yield label
    yield "received " + repr(got)


fresh = echo("parked")
print("  send a value:", describe("value", lambda: fresh.send("early")))
print("  send an int:", describe("int", lambda: fresh.send(1)))
print("  send None:", describe("none", lambda: fresh.send(None)))
print("  next after that:", describe("next", lambda: next(fresh)))
print("  send a value now:", describe("value", lambda: fresh.send("late")))

other = echo("again")
print("  next first:", describe("next", lambda: next(other)))
print("  the yield answered:", describe("second", lambda: next(other)))

print("--- a generator that keeps what it is given")


def running(total=0):
    while True:
        given = yield total
        if given is None:
            break
        total += given


steps = running()
print("  from None:", describe("first", lambda: next(steps)))
print("  three adds:", describe("adds", lambda: [steps.send(2), steps.send(3), steps.send(4)]))
print("  then nothing:", describe("break", lambda: steps.send(None)))
print("  exhausted:", describe("past the end", lambda: next(steps)))
print("  send past the end:", describe("send", lambda: steps.send(1)))
print("  next with a default:", describe("default", lambda: next(steps, "gone")))
print("  throw past the end:", describe("throw", lambda: steps.throw(ValueError("late"))))
print("  close past the end:", describe("close", lambda: steps.close()))

print("--- a caller that hands values in a loop")


def labeled():
    for index in range(3):
        received = yield "step " + str(index)
        print("    the generator got", repr(received))


for handed in labeled():
    print("  loop sees", handed)

print("  and with send:")
mixed = labeled()
print("   ", repr(next(mixed)))
print("   ", repr(mixed.send("by hand")))
print("   ", repr(mixed.send(7)))
print("   ", describe("end", lambda: mixed.send(None)))

print("--- the value of the expression is read where it is written")


def written():
    first = yield 1
    second = (yield first), (yield 2)
    yield (first, second)


wrote = written()
print("  shape:", describe("three", lambda: [next(wrote), wrote.send("a"), wrote.send("b")]))

print("--- a generator asked to run itself")
target = None


class Watcher:
    def __eq__(self, other):
        print("    ", describe("next() while running", lambda: next(target)))
        print("    ", describe("send() while running", lambda: target.send(1)))
        print("    running:", target.gi_running, "suspended:", target.gi_suspended)
        return False


def reentering():
    yield Watcher() == 1
    yield "after the attempt"


target = reentering()
print("  the whole run:", list(target))

print("--- a name written and a name read across a yield")


def remembered():
    stored = "nothing yet"
    yield stored
    stored = "written between"
    yield stored
    yield stored + " and read"


print("  ", list(remembered()))

print("--- send into a generator that is a method")


class Accumulator:
    def __init__(self):
        self.kept = []

    def gather(self):
        while True:
            given = yield len(self.kept)
            if given is None:
                return tuple(self.kept)
            self.kept.append(given)


bag = Accumulator().gather()
print("  ", describe("four", lambda: [next(bag), bag.send("a"), bag.send("b"), bag.send(None)]))

print("--- a StopIteration answered by a hand-over")


def counted(times):
    for index in range(times):
        yield index
    return "done after " + str(times)


try:
    result = None
    for value in counted(3):
        result = value
except StopIteration as stop:
    print("  the loop never sees it:", repr(stop.value))
print("  returned through next:", describe("end", lambda: [next(counted(1)), next(counted(1))]))
