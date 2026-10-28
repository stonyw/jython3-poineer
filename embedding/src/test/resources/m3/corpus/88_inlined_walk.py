"""What ``all(x for x in y)`` compiles to, and the identity check that decides whether it ran.

3.14 inlines three calls of a generator expression argument — ``all``, ``any`` and ``tuple`` — into the
frame that wrote them. The shape ``Python/codegen.c`` emits is ``COPY`` of the callable,
``LOAD_COMMON_CONSTANT`` of the builtin it has to be, ``IS_OP`` between the two, and a jump over the inlined
loop when they are not the same object; then ``MAKE_FUNCTION``/``GET_ITER``/``CALL`` for the generator
itself and a ``FOR_ITER`` loop in the caller, whose body is ``TO_BOOL`` and a conditional jump for the two
truth builtins and ``LIST_APPEND`` for the tuple. So the generator's own frame still exists — an exception
raised in the body is reported from ``<genexpr>`` either way — but the walk, the truth question and the
answer do not: they are the caller's instructions, and the builtin is never called.

That is why the five entries of ``_common_constants`` are a 3.14 face of their own. 3.13's three were
``AssertionError``, ``NotImplementedError`` and ``SystemError``; 3.14 keeps the first two and replaces the
third with ``tuple``, ``all`` and ``any``, which is what the compiler asks for by number. The check is an
``is`` against the object the interpreter froze at startup, so a program that rebinds the name is refused the
inlining and gets an ordinary call with the generator as its one argument — measured four ways here: a local
``all``, a global one, a freshly built function under the same name, and ``builtins.all`` itself replaced.
Only the last of those leaves the *frozen* object in the table and the rebound one in the namespace, and it
still falls back, which is the proof that the entry is not read out of the namespace at all. ``tuple``
behaves the same way, and the fallback answer is a call of whatever the name holds.

The three answers and the two refusals inside the inlined loop are the same as the ones the call gives: a
non-``bool`` from a ``__bool__`` names what it returned, a ``__bool__`` filed as ``None`` names the class,
and an empty walk answers with the singleton that says nothing was refused.
"""

import builtins


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def frames(error):
    names = []
    traceback = error.__traceback__
    while traceback is not None:
        names.append(traceback.tb_frame.f_code.co_name)
        traceback = traceback.tb_next
    return names


def caught(thunk):
    try:
        return ("answer", thunk())
    except BaseException as error:
        return ("frames", frames(error), type(error).__name__, str(error))


print("== the inlined answers")
show("all of a walk", lambda: all(x for x in [1, 2]))
show("all meeting a zero", lambda: all(x for x in [1, 0, 1]))
show("any meeting a one", lambda: any(x for x in [0, 0, 1]))
show("any of zeros", lambda: any(x for x in [0, 0]))
show("all of nothing", lambda: all(x for x in []))
show("any of nothing", lambda: any(x for x in []))
show("answers singletons", lambda: (all(x for x in []) is True, any(x for x in []) is False))
show("tuple of a walk", lambda: tuple(x for x in [1, 2]))
show("tuple of nothing", lambda: tuple(x for x in []))
show("tuple answer type", lambda: type(tuple(x for x in [1])).__name__)


def raiser(value):
    yield value
    raise RuntimeError("walked too far")


show("all stops early", lambda: all(x for x in raiser(0)))
show("all walks on", lambda: all(x for x in raiser(1)))
show("any stops early", lambda: any(x for x in raiser(1)))
show("any walks on", lambda: any(x for x in raiser(0)))
show("tuple keeps walking", lambda: tuple(x for x in raiser(0)))

print("== whose frame the body runs in")
show("all inlined", lambda: caught(lambda: all(x / 0 for x in [1])))
show("any inlined", lambda: caught(lambda: any(x / 0 for x in [1])))
show("tuple inlined", lambda: caught(lambda: tuple(x / 0 for x in [1])))
show("all with the brackets", lambda: caught(lambda: all((x / 0 for x in [1]))))
show("sum of a walk", lambda: caught(lambda: sum(x / 0 for x in [1])))
show("all of an unwalkable", lambda: caught(lambda: all(x for x in 1)))
show("bracketed unwalkable", lambda: caught(lambda: all((x for x in 1))))

print("== the identity check")
ORIGINAL = all


def shadowed(value):
    all = value
    return all(x for x in [1])


def rebound_global():
    record = []

    def counted(*args):
        record.append(len(args))
        return "called"

    builtins_globals = globals()
    builtins_globals["all"] = counted
    answer = all(x for x in [1])
    builtins_globals.pop("all")
    return (answer, record, all(x for x in [1]))


def rebound_builtins():
    keep = builtins.all
    try:
        builtins.all = lambda *args: ("called", len(args))
        answer = all(x for x in [1])
    finally:
        builtins.all = keep
    return (answer, all(x for x in [1]), builtins.all is ORIGINAL)


def rebound_builtins_tuple():
    keep = builtins.tuple
    try:
        builtins.tuple = lambda *args: ("called", len(args))
        answer = tuple(x for x in [1])
    finally:
        builtins.tuple = keep
    return (answer, tuple(x for x in [1]), type(tuple(x for x in [1])).__name__)


def fresh_under_the_name():
    def all(*args):
        return ("rebuilt", len(args))

    return (all(x for x in [1]), builtins.all(x for x in [1]))


show("local shadow", lambda: shadowed(lambda *a: ("called", len(a), type(a[0]).__name__)))
show("global rebound", rebound_global)
show("builtins rebound", rebound_builtins)
show("builtins tuple rebound", rebound_builtins_tuple)
show("name in a function", fresh_under_the_name)
show("the frozen pair", lambda: (ORIGINAL is all, all is all, builtins.all is all))

print("== truth asked by the caller")


class ByBool:
    def __init__(self, value):
        self.value = value

    def __bool__(self):
        return self.value


class NoneBool:
    __bool__ = None


class NoneLen:
    __len__ = None


show("non bool inlined", lambda: all(ByBool(3) for x in [0]))
show("any non bool inlined", lambda: any(ByBool("x") for x in [0]))
show("bool filed as none inlined", lambda: all(NoneBool() for x in [0]))
show("len filed as none inlined", lambda: any(NoneLen() for x in [0]))
show("empty dicts", lambda: all(x for x in [{}, {"a": 1}]))
show("range of nothing", lambda: any(x for x in range(0)))
show("nested inlined", lambda: all(all(y for y in [x]) for x in [1, 2]))
show("inlined in a comp", lambda: [all(x for x in [i]) for i in [1, 0]])
show("tuple of tuples", lambda: tuple(tuple(x for x in [i]) for i in [1, 2]))
show("counted walk", lambda: [n for n in range(3) if all(x for x in [n])])
