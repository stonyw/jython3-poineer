# frame.f_locals as a live window on a generator's slots, and the two throw() refusals (M5 slice 36).
#
# The reference answers a function frame's f_locals with a FrameLocalsProxy since 3.13: writes go into
# the fast locals, cells included, and a proxy taken before an assignment sees it afterwards.  That is
# what lets a suspended generator be steered by rewriting the name it is looping over.

import sys

# The warning half below needs the reference's pure-Python `warnings` module; the loader has no
# standard library of its own, so the script points at the one that answers it (as corpus 37 does).
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)


def show(tag, *value):
    print(tag, *value)


# A generator expression keeps its iterator in the implicit `.0` local.
g = (x for x in range(3))
show("genexpr-varnames", g.gi_code.co_varnames)
show("genexpr-locals", sorted(g.gi_frame.f_locals))
g.gi_frame.f_locals[".0"] = iter([7, 8])
show("genexpr-steered", list(g))

# A non-iterator in `.0` is refused by the loop, which wants an iterator, not an iterable.
g = (x for x in range(3))
g.gi_frame.f_locals[".0"] = [1, 2]
try:
    list(g)
except TypeError as error:
    show("genexpr-not-iterator", str(error))

# A generator function's argument is a plain slot.
def over(it):
    for x in it:
        yield x

g = over(range(3))
g.gi_frame.f_locals["it"] = [9]
show("genfunc-steered", list(g))

# A cell is written through its box, which is where the closure reads it.
def outer():
    a = 10

    def inner():
        return a

    return inner, sys._getframe().f_locals

inner, live = outer()
live["a"] = 99
show("cell-written", inner())

# A proxy taken before an assignment sees it afterwards, unlike the snapshot locals() returns.
def late():
    captured = sys._getframe().f_locals
    a = 42
    return "a" in captured, captured["a"]

show("live-after-assignment", late())

# Names the code object has no slot for live beside the locals, and del refuses the ones it has.
def plain():
    a = 1
    return sys._getframe().f_locals

proxy = plain()
proxy["zz"] = "extra"
show("extra", "zz" in proxy, len(proxy), proxy["zz"], sorted(proxy))
del proxy["zz"]
show("extra-deleted", "zz" in proxy, len(proxy))
try:
    del proxy["a"]
except ValueError as error:
    show("del-local", str(error))
try:
    proxy["nope"]
except KeyError as error:
    show("missing", str(error))

# A generator is born with the function's names as they stand at the call, not the code object's.
def named():
    yield

named.__name__ = "renamed"
named.__qualname__ = "renamed_qualname"
made = named()
show("generator-name", made.__name__, made.__qualname__)

# The (type, exc, tb) signature of throw() is deprecated, and says so once per call.
import warnings


def body():
    yield 42

g = body()
with warnings.catch_warnings(record=True) as caught:
    warnings.simplefilter("always")
    try:
        g.throw(TypeError, TypeError(24), None)
    except TypeError as error:
        show("three-arg-throw", str(error))
    show("three-arg-warning", [(type(w.message).__name__, str(w.message)) for w in caught])


# A class whose __new__ answers itself has not made an exception: the failure enters the generator,
# which is therefore finished, and throw() reports it from there.
class NotAnException(Exception):
    def __new__(cls, *args, **kwargs):
        return cls


def boring():
    yield

g = boring()
try:
    g.throw(NotAnException)
except TypeError as error:
    show("bad-exception", str(error))
try:
    next(g)
except StopIteration:
    show("bad-exception-exhausted", True)
