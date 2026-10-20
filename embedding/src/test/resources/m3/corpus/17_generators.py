"""generators: the frame that hands back a value without finishing.

Nothing here prints a generator itself: its repr carries the address CPython prints, which is the one
thing the two interpreters can never agree on. Every name read here is a name Python asks — the state
through `gi_*`, the frame through `gi_frame`, the values through `next`, `send`, `for` and `list`."""

import sys


def describe(label, thunk):
    try:
        return label + " -> " + repr(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + repr(error.args)


print("--- nothing runs until the first step")
steps = []


def counted():
    steps.append("before the first yield")
    yield "one"
    steps.append("between the yields")
    yield "two"
    steps.append("after the last yield")


made = counted()
print("  made it, steps:", steps)
print("  next:", next(made), steps)
print("  next:", next(made), steps)
print("  next:", describe("third", lambda: next(made)), steps)
print("  gi_frame is gone:", made.gi_frame is None)

print("--- the ways to drive one, and what each leaves behind")


def three():
    yield 1
    yield 2
    yield 3


print("  list:", list(three()))
print("  tuple:", tuple(three()))
print("  sum:", sum(three()))
print("  for:", end=" ")
for seen in three():
    print(seen, end=" ")
print()
print("  unpacked:", [*three(), *three()])
print("  in a comprehension:", [doubled * 2 for doubled in three()])
print("  joined:", ",".join(str(item) for item in three()))
print("  zipped:", list(zip(three(), three())))
print("  enumerated:", list(enumerate(three())))
print("  mapped:", list(map(lambda v: v + 10, three())))
print("  filtered:", list(filter(lambda v: v % 2, three())))

print("--- a return value, and who gets to see it")


def tail(number):
    yield number
    return number * 100


def through_next():
    gen = tail(7)
    next(gen)
    try:
        next(gen)
    except StopIteration as stop:
        return [type(stop).__name__, stop.value, stop.args]


def through_send():
    gen = tail(7)
    next(gen)
    try:
        gen.send(None)
    except StopIteration as stop:
        return stop.value


def through_for():
    for value in tail(7):
        pass
    return "the loop saw only " + str(value)


print("  next:", through_next())
print("  send:", through_send())
print("  for:", through_for())
print("  list:", list(tail(7)))


def returns_tuple():
    yield "first"
    return (1, 2)


answering = returns_tuple()
next(answering)
try:
    next(answering)
except StopIteration as stop:
    print("  a tuple stays a tuple:", repr(stop.value), repr(stop.args))

print("--- what a generator is called")


def plain():
    """A docstring the generator does not keep."""
    yield "value"


class Held:
    def method(self):
        yield "method"

    @classmethod
    def many(cls):
        yield cls.__name__

    @staticmethod
    def quiet():
        yield "quiet"


def outer_function():
    def inner():
        yield "inner"

    return inner()


print("  type:", type(plain()).__name__)
print("  a def:", plain().__name__, plain().__qualname__)
print("  a method:", Held().method.__qualname__)
print("  a classmethod:", Held.many.__qualname__, list(Held.many()))
print("  a staticmethod:", Held.quiet.__qualname__, list(Held.quiet()))
print("  nested in a function:", outer_function().__qualname__)
print("  docstring of the function:", plain.__doc__)
print("  docstring of the generator:", repr(plain().__doc__))
print("  module name is refused:", describe("__module__", lambda: plain().__module__))
print("  dict is refused:", describe("__dict__", lambda: plain().__dict__))
print("  class:", plain().__class__.__name__, [kind.__name__ for kind in plain().__class__.__mro__])

print("--- the names that read the state")
parked = plain()
print("  before:", parked.gi_running, parked.gi_suspended, parked.gi_yieldfrom)
print("  code:", parked.gi_code.co_name, parked.gi_code is plain.__code__)
print("  frame:", type(parked.gi_frame).__name__, parked.gi_frame.f_code.co_name)
print("  started:", describe("first", lambda: next(parked)))
print("  while parked:", parked.gi_running, parked.gi_suspended)
print("  frame is its own:", parked.gi_frame is parked.gi_frame)
print("  frame of the caller:", parked.gi_frame.f_back)
print("  frame knows its generator:", parked.gi_frame.f_generator is parked)
print("  exhausted:", describe("second", lambda: next(parked)))
print("  after:", parked.gi_frame, parked.gi_running, parked.gi_suspended)

print("--- the frame as it moves")


def moving(first, second):
    joined = first + second
    yield joined
    joined = joined + "!"
    yield joined


moved = moving("a", "b")
print("  not started:", moved.gi_frame.f_lineno, sorted(moved.gi_frame.f_locals))
print("  lasti moved:", moved.gi_frame.f_lasti > 0)
next(moved)
print("  at the first yield:", moved.gi_frame.f_lineno, sorted(moved.gi_frame.f_locals.items()))
first_lasti = moved.gi_frame.f_lasti
next(moved)
print("  at the second:", moved.gi_frame.f_lineno, sorted(moved.gi_frame.f_locals.items()))
print("  the site moved forward:", moved.gi_frame.f_lasti > first_lasti)
print("  globals hold the caller:", "moved" in moved.gi_frame.f_globals)

print("--- a generator expression")
expression = (n * n for n in range(4))
print("  name:", expression.__name__, expression.__qualname__)
print("  first pass:", list(expression))
print("  second pass:", list(expression))


def holder(items):
    made = (entry * 3 for entry in items)
    return [made.__qualname__, list(made), list(made)]


print("  inside a function:", holder([1, 2]))
print("  a set of one:", sorted({entry for entry in "aba"}))
print("  a dict of one:", {key: value for key, value in (("a", 1), ("b", 2))})

print("--- what a generator closes over")


def outer(start):
    count = 0

    def inner_step():
        nonlocal count
        count += start
        yield count
        yield count

    return inner_step()


print("  a cell shared with a generator:", list(outer(5)))


class Named:
    def label(self):
        return "from the base"


class Also(Named):
    def labels(self):
        yield super().label()
        yield "written here"


print("  super() inside a generator:", list(Also().labels()))


class Counts:
    def __init__(self):
        self.total = 0

    def each(self):
        while self.total < 3:
            self.total += 1
            yield self.total


print("  a generator that writes an attribute:", list(Counts().each()))

print("--- a body that is guarded")


def guarded(answer):
    try:
        yield "before"
        raise ValueError("inside")
    except ValueError:
        yield "caught " + str(answer)
    finally:
        print("    finally of", answer)


print("  list:", list(guarded("list")))
second = guarded("next")
print("  two steps:", next(second), next(second), describe("third", lambda: next(second)))


def guarded_return():
    try:
        yield "before"
        return "from the try"
    finally:
        print("    finally on the way out")


print("  a return through a finally:", list(guarded_return()))

print("--- a yield inside a with")
openings = []


class Watched:
    def __enter__(self):
        openings.append("enter")
        return "held"

    def __exit__(self, exc_type, exc_value, traceback):
        openings.append("exit")
        return False


def within():
    with Watched() as held:
        yield held
        yield held + " again"
    openings.append("left the with")


print("  driven:", list(within()), openings)

print("--- recursion through a generator")


def down(count):
    if count:
        for value in down(count - 1):
            yield value
    yield count


print("  nested:", list(down(2)))
print("  lengths:", [len(list(down(edge))) for edge in range(4)])

print("--- sys.exc_info seen from inside")


def reporter():
    yield sys.exc_info()[0]
    try:
        raise TypeError("own")
    except TypeError:
        yield type(sys.exc_info()[1]).__name__
    yield str(sys.exc_info()[0])


print("  quiet:", list(reporter()))
try:
    raise IndexError("the caller is handling this")
except IndexError:
    print("  inside a handler of the caller:", list(reporter()))
