"""The exception state a generator keeps for itself, and the one StopIteration it must not hand over.

A generator frame carries its own topmost handled exception, so a handler the body is inside of survives
the yields around it and a body with no handler of its own falls through to whatever the caller is
handling. And `StopIteration` is the signal iteration is over, not an answer: a generator that lets one
escape instead of returning gets it rebuilt as a `RuntimeError`, with the original on both sides of the
chain. The two together are why the shape of `sys.exc_info()` is read as names rather than as objects.
"""

import sys


def shape():
    kind, value, traceback = sys.exc_info()
    return [
        kind.__name__ if kind else None,
        value.__class__.__name__ if value else None,
        traceback is not None,
    ]


def report(label, error):
    return " ".join(
        [
            label,
            type(error).__name__,
            repr(error.args),
            "cause=" + (error.__cause__.__class__.__name__ if error.__cause__ else "None"),
            "context="
            + (error.__context__.__class__.__name__ if error.__context__ else "None"),
            "same=" + str(error.__cause__ is not None and error.__cause__ is error.__context__),
        ]
    )


print("--- whose exception is the topmost")


def peek():
    yield shape()
    try:
        raise TypeError("own")
    except TypeError:
        yield shape()
    yield shape()


print("  quiet:", list(peek()))
print("  the caller itself:", shape())
try:
    raise IndexError("the caller is handling this")
except IndexError:
    print("  driven from a handler:", list(peek()))
    print("  still the caller's:", shape())
print("  after that handler:", shape())

print("--- a handler's state held across a yield")


def keeps():
    try:
        raise KeyError("first")
    except KeyError:
        for _ in range(2):
            yield shape()


print("  driven:", list(keeps()))

print("--- two handlers of the generator, one after the other")


def makes_a_context():
    try:
        yield "parked"
    except ValueError:
        try:
            raise TypeError("second")
        except TypeError:
            yield "topmost is " + shape()[0]


chain = makes_a_context()
next(chain)
print("  ", chain.throw(ValueError("first")))

print("--- an escape the caller catches")


def escapes():
    yield "parked"
    raise ValueError("out of the generator")


try:
    for entry in escapes():
        print("   saw", entry)
except ValueError as caught:
    print("  ", report("escaped", caught), "handler", shape())

print("--- a StopIteration is not an answer")


def raises_stop():
    raise StopIteration("from a generator")
    yield "never"


def helper():
    raise StopIteration("from a plain helper")


def calls_helper():
    yield helper()


def next_of_an_empty_iterator():
    yield next(iter(()))


def catches_it():
    try:
        yield helper()
    except StopIteration as stop:
        yield "caught " + repr(stop.args)


def an_empty_loop():
    for entry in ():
        yield entry
    yield "the loop ended on its own"


def not_a_stop():
    try:
        yield "parked"
        raise IndexError("an ordinary exception")
    except ValueError:
        yield "the wrong clause"


for label, thunk in [
    ("raises it itself", raises_stop),
    ("calls a helper that does", calls_helper),
    ("asks for the next of nothing", next_of_an_empty_iterator),
    ("catches it in the body", catches_it),
    ("iterates nothing", an_empty_loop),
    ("raises something else", not_a_stop),
]:
    try:
        print("  ", label, "->", list(thunk()))
    except BaseException as error:
        print("  ", label, "->", report("converted", error))

print("--- the same answer from a generator expression")

genexp = (helper() for entry in range(2))
try:
    list(genexp)
except BaseException as error:
    print("  ", report("genexp", error))

print("--- the conversion on the ways in")


def stops_while_handling():
    try:
        yield "parked"
    except ValueError:
        raise StopIteration("made while handling")


thrown = stops_while_handling()
next(thrown)
try:
    thrown.throw(ValueError("in"))
except BaseException as error:
    print("  ", report("throw", error))


def stops_on_exit():
    try:
        yield "parked"
    finally:
        raise StopIteration("out of a finally")


closing = stops_on_exit()
next(closing)
try:
    closing.close()
except BaseException as error:
    print("  ", report("close", error))

print("--- a conversion seen from a handler of the caller")

try:
    raise KeyError("the caller is handling this")
except KeyError:
    try:
        list(calls_helper())
    except BaseException as error:
        print("  ", report("converted", error), "handler", shape())

print("--- a return value, and who is allowed to see it")


def gives_back():
    yield "one"
    return "the answer"


print("  list:", list(gives_back()))
counted = 0
for entry in gives_back():
    counted += len(entry)
print("  loop counted:", counted)
driven = gives_back()
next(driven)
try:
    next(driven)
except StopIteration as stop:
    print("  next hands it over:", repr(stop.value), repr(stop.args))
