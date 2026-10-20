"""throw and close: entering a parked frame with an exception already in flight.

`close` is `throw(GeneratorExit())` with the answers kept to itself: a generator that lets the exit pass
is done, one that returns hands its value back to the caller of `close`, and one that yields again is
refused. `throw` hands over whatever it is given and lets it propagate."""


def describe(label, thunk):
    try:
        return label + " -> " + repr(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + repr(error.args)


print("--- the arguments throw takes")


def parked():
    yield "parked"
    yield "parked again"


held = parked()
next(held)
print("  a class:", describe("class", lambda: parked().throw(ValueError)))
print("  an instance:", describe("instance", lambda: parked().throw(ValueError("given"))))
print("  a bare BaseException:", describe("bare", lambda: parked().throw(BaseException)))
print("  text:", describe("text", lambda: held.throw("not an exception")))
print("  a class that is not one:", describe("int", lambda: held.throw(int)))
print("  nothing:", describe("no argument", lambda: held.throw()))
print("  a keyword:", describe("keyword", lambda: held.throw(ValueError, x=1)))
print("  still parked through every refusal:", held.gi_suspended)
print("  ", describe("and now in", lambda: held.throw(ValueError())))
print("  ", "parked:", held.gi_suspended, describe("gone", lambda: next(held)))

print("--- a throw that the generator answers")


def answering():
    try:
        yield "first"
    except ValueError as caught:
        yield "caught " + str(caught)
    except IndexError:
        yield "caught an index"
    print("    out of the handlers")


a = answering()
print("  ", next(a))
print("  ", describe("value", lambda: a.throw(ValueError("thrown"))))
print("  ", describe("index", lambda: a.throw(IndexError("second"))))
b = answering()
print("  ", next(b))
print("  ", describe("other kind", lambda: b.throw(KeyError("no clause"))))

print("--- a throw that it does not")


def silent():
    yield "first"
    yield "second"


s = silent()
print("  ", next(s))
print("  ", describe("past every clause", lambda: s.throw(RuntimeError("nothing here"))))
print("  ", describe("the frame is gone", lambda: s.gi_frame))
print("  ", describe("next after an escape", lambda: next(s)))

print("--- a throw into a generator that has not started")


def never_ran():
    yield "never"
    yield "never"


n = never_ran()
print("  ", describe("into a fresh frame", lambda: n.throw(KeyError("fresh"))))
print("  ", "frame:", n.gi_frame, "suspended:", n.gi_suspended)
print("  ", describe("next after", lambda: next(n)))

print("--- close")
left = []


def watched():
    try:
        yield "one"
        yield "two"
    finally:
        left.append("finally")


created = watched()
print("  a created one:", describe("close", lambda: created.close()), left)
print("  next after:", describe("next", lambda: next(created)))
print("  its frame:", created.gi_frame)

started = watched()
print("  a started one:", describe("first", lambda: next(started)))
print("  ", describe("close", lambda: started.close()), left)
print("  next after:", describe("next", lambda: next(started)))
print("  close again:", describe("close", lambda: started.close()), left)

left = []
last = watched()
print("  ", next(last))
print("  ", next(last))
print("  ", describe("close on the last yield", lambda: last.close()), left)
print("  ", describe("the answer it did not give", lambda: next(last)), left)


def answers_the_exit():
    try:
        yield "one"
    except GeneratorExit:
        left.append("caught the exit")
    return "an answer while closing"


closing = answers_the_exit()
print("  ", next(closing))
print("  ", describe("a return while closing", lambda: closing.close()), left)


def yields_after_the_exit():
    try:
        yield "one"
    except GeneratorExit:
        yield "two anyway"


refusing = yields_after_the_exit()
print("  ", next(refusing))
print("  ", describe("a yield while closing", lambda: refusing.close()))
print("  ", describe("next after", lambda: next(refusing)))


def fails_while_closing():
    try:
        yield "one"
    finally:
        raise IndexError("out of a finally")


failing = fails_while_closing()
print("  ", next(failing))
print("  ", describe("close that fails", lambda: failing.close()))

print("--- close takes nothing")
print("  ", describe("close(1)", lambda: parked().close(1)))
print("  ", describe("close(1, 2)", lambda: parked().close(1, 2)))
print("  ", describe("close(x = 1)", lambda: parked().close(x=1)))

print("--- a throw that carries the state of the handler it came from")


def chained():
    try:
        yield "parked"
    except ValueError:
        yield "handling"
        raise KeyError("made while handling")


chain = chained()
print("  ", next(chain))
print("  ", describe("thrown", lambda: chain.throw(ValueError("thrown"))))
print("  ", describe("then", lambda: next(chain)))

print("--- GeneratorExit handed over by throw")


def catches_exit():
    try:
        yield "parked"
    except GeneratorExit:
        print("    caught an exit thrown by hand")


thrown = catches_exit()
print("  ", next(thrown))
print("  ", describe("throw the exit", lambda: thrown.throw(GeneratorExit())))

print("--- the members that are refused")


def send_argument_counts():
    yield "value"


counter = send_argument_counts()
print("  ", describe("send()", lambda: counter.send()))
print("  ", describe("send(1, 2)", lambda: counter.send(1, 2)))
print("  ", describe("send(x = 1)", lambda: counter.send(x=1)))
print("  ", describe("throw()", lambda: counter.throw()))
print("  ", describe("throw(1, 2, 3, 4)", lambda: counter.throw(ValueError, None, None, 4)))

print("  names a generator will not take:")
print("  ", describe("gi_frame =", lambda: setattr(counter, "gi_frame", 1)))
print("  ", describe("gi_code =", lambda: setattr(counter, "gi_code", 1)))
print("  ", describe("__name__ = 1", lambda: setattr(counter, "__name__", 1)))
print("  ", describe("del __name__", lambda: delattr(counter, "__name__")))
renamed = send_argument_counts()
renamed.__name__ = "renamed"
renamed.__qualname__ = "Renamed.renamed"
print("   a write of text:", renamed.__name__, renamed.__qualname__)
