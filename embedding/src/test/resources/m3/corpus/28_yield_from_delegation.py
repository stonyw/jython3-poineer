"""yield from: the delegation a generator hands its own steps to.

The instruction puts an iterator on the frame's value stack and leaves it there: the values the caller
takes are the delegate's, the value the caller sends arrives in the delegate, and what the delegate
returns is the value of the expression. The generator doing the delegating is still the one the caller
holds, and `gi_yieldfrom` is how it names what it is waiting on."""


def describe(label, thunk):
    try:
        return label + " -> " + repr(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


print("--- the items are the delegate's and its return is the expression")


def counted(times):
    for index in range(times):
        yield "item " + str(index)
    return "returned after " + str(times)


def relay(source):
    got = yield from source
    yield "the expression was " + repr(got)


print("  the whole run:", list(relay(counted(2))))
print("  nothing to delegate:", list(relay(counted(0))))
print("  a value nobody asked for:", list(counted(1)))

print("--- the ordinary iterables answer the same way")


def spread(source):
    yield from source


print("  list:", list(spread([1, 2])))
print("  tuple:", list(spread((3, 4))))
print("  string:", list(spread("abc")))
print("  range:", list(spread(range(3, 6))))
print("  dict:", list(spread({"k1": 1, "k2": 2})))
print("  set:", sorted(spread({7, 8, 9})))
print("  empty:", list(spread(())))
print("  enumerate:", list(spread(enumerate("xy"))))
print("  a generator expression:", list(spread(x * 10 for x in [1, 2])))


def twice():
    yield from [1, 2]
    yield from (3, 4)


print("  twice in one body:", list(twice()))

print("--- a delegation of a delegation, and the value that travels")


def leaf():
    yield "leaf"
    return "leaf value"


def middle(source):
    got = yield from source
    return "middle(" + got + ")"


def top(source):
    got = yield from source
    yield "top saw " + got


print("  three levels:", list(top(middle(leaf()))))

three = top(middle(leaf()))
print("  the first step:", repr(next(three)))
print("  and the rest:", describe("end", lambda: next(three)))


def pass_through(source):
    return (yield from source)


across = pass_through(leaf())
print("  return (yield from):", repr(next(across)))
print("  what it hands back:", describe("stop", lambda: next(across)))
print("  the loop never sees it:", describe("list", lambda: list(pass_through(leaf()))))

print("--- what the generator says about the delegation it is in")

source = counted(2)
waiting = relay(source)
print("  before it starts: yieldfrom", waiting.gi_yieldfrom is None,
      "suspended", waiting.gi_suspended, "running", waiting.gi_running)
print("  first step:", repr(next(waiting)))
print("  while it waits: yieldfrom is the delegate:", waiting.gi_yieldfrom is source,
      "|", type(waiting.gi_yieldfrom).__name__)
print("  the delegate's own state:", waiting.gi_yieldfrom.gi_suspended,
      waiting.gi_yieldfrom.gi_running, waiting.gi_yieldfrom.gi_yieldfrom is None)
print("  second step:", repr(next(waiting)))
print("  still the same delegate:", waiting.gi_yieldfrom is source)
print("  third step:", repr(next(waiting)))
print("  the delegation is over:", waiting.gi_yieldfrom is None, waiting.gi_suspended)
print("  past the end:", describe("end", lambda: next(waiting)))
print("  afterwards:", waiting.gi_yieldfrom, waiting.gi_frame)

print("--- what the generator says while the delegate is the one running")

read_while_running = []
held = []


def asked_to_report():
    yield 1
    delegator = held[0]
    read_while_running.append(
        (delegator.gi_yieldfrom is None, delegator.gi_suspended, delegator.gi_running))
    yield 2


def delegating():
    yield from asked_to_report()


held.append(delegating())
delegator = held[0]
print("  ", repr(next(delegator)))
print("  the second step runs the delegate's body:", repr(next(delegator)))
print("  read from inside it:", read_while_running)
print("  read again now that it is parked:", type(delegator.gi_yieldfrom).__name__,
      delegator.gi_yieldfrom is None, delegator.gi_suspended, delegator.gi_running)

print("--- a delegate that has already started")

started = counted(3)
print("  one step of its own:", repr(next(started)))
print("  then through a delegation:", list(relay(started)))

print("--- names the body kept are still there after the delegation")


def remembered(source):
    total = 0
    for item in source:
        total += item
    got = yield from counted(1)
    yield "summed " + str(total) + ", delegated " + repr(got)


print("  ", list(remembered([1, 2, 3])))

print("--- the delegation is not a for loop")


def compare(source):
    for item in source:
        yield "loop " + repr(item)
    yield from source
    yield "done"


print("  ", list(compare([5])))
