"""What ``all`` and ``any`` read, and the item that makes them stop reading.

Both are one-argument builtins — ``builtin_all`` and ``builtin_any`` in ``Python/bltinmodule.c``, ``METH_O`` with a clinic sentence each — and the shape shows it: the type is
``builtin_function_or_method``, ``__name__`` and ``__qualname__`` are the bare name, the repr
names the module the builtin was compiled into, no keyword is accepted at all, and the second argument is
refused by count rather than by name. A builtin function keeps no dictionary of its own, so ``__dict__`` is not a name it answers with even
though ``__doc__`` is.

The walk is ``PyObject_GetIter`` and then ``tp_iternext``, one item at a time, and every item is
asked ``PyObject_IsTrue`` — which is why the refusals an item raises are the same three sentences
``bool()`` raises and why a non-iterable argument is refused for the object it is rather than for the
call. What the two differ in is only which answer ends the walk: ``all`` returns false at the first item
that is not true and ``any`` returns true at the first item that is, so a generator that would raise two
items later never does. The empty walk is the same difference seen the other way: ``all`` has found
nothing to refuse and ``any`` nothing to accept, which is why they answer with the two singletons and not
with a fresh ``bool``.

Truth is ``__bool__``, then ``__len__``, then true, and 3.14 splits the first two of those into
three cases apiece. A ``__bool__`` that answers with anything but a ``bool`` is refused by the slot
wrapper with the returned type's name in the sentence; a ``__len__`` that answers with a negative number
is a ``ValueError`` rather than a truth value, because the same ``slot_sq_length`` runs whichever
question asked it, and one too wide for a machine word is an ``OverflowError``. Filing ``None`` under
either name is not the same as filing nothing: ``slot_nb_bool`` asks ``maybe_call_special_no_args`` with
somewhere to record that the name was found and was ``None``, and
refuses by naming the class — ``'NoneBool' cannot be interpreted as a boolean`` is a sentence no other
path says — while the ``__len__`` asked on the way down has nowhere to record it, so ``None`` is
called like any other answer would be and the refusal is the call's own. Both questions are asked of the
class the value reports, which is why a class derived from an exception refuses in the same words as a class
that inherits from nothing: the answer arrives through a different Java value class, and the namespace is the
same one.
"""


import builtins


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))

print("== shape")
show("types", lambda: (type(all).__name__, type(any).__name__))
show("docs", lambda: all.__doc__)
show("any doc", lambda: any.__doc__)
show("names", lambda: (all.__name__, all.__qualname__, any.__name__, any.__qualname__))
show("reprs", lambda: (repr(all), repr(any)))
show("no dict", lambda: [hasattr(f, "__dict__") for f in (all, any)])
show("filed names", lambda: [n in vars(builtins) for n in ("all", "any")])
show("same object", lambda: (builtins.all is all, builtins.any is any))
show("callable", lambda: [callable(f) for f in (all, any)])

print("== empty walks")
show("all empty", lambda: [all(x) for x in ([], (), "", {}, set(), range(0), b"", frozenset())])
show("any empty", lambda: [any(x) for x in ([], (), "", {}, set(), range(0), b"", frozenset())])
show("answers", lambda: (all([]) is True, any([]) is False))
show("answer types", lambda: (type(all([])).__name__, type(any([])).__name__))

print("== which answer ends the walk")
show("all of two", lambda: all([1, 2]))
show("all of one zero", lambda: all([1, 0, 1]))
show("all leading zero", lambda: all([0, 1, 1]))
show("any of two zeros", lambda: any([0, 0, 1]))
show("any leading one", lambda: any([1, 0, 0]))
show("any of zeros", lambda: any([0, 0]))
show("all of empties", lambda: all([(), [], "", 0, 0.0, 0j, False, None]))
show("any of empties", lambda: any([(), [], "", 0, 0.0, 0j, False, None]))
show("all of one empty", lambda: any([{}, {"a": 1}]))
show("all of str", lambda: (all("ab"), any("a\0"), all("\0")))
show("all of bytes", lambda: (all(b"\x01\x00"), any(b"\x00"), any(b"\x01")))
show("all of dict keys", lambda: (all({"": 1, "x": 2}), any({"": 1, "x": 2})))
show("all of range", lambda: (all(range(3)), any(range(1)), any(range(0, 1))))


def raiser(value):
    yield value
    raise RuntimeError("walked too far")


show("all stops at a falsy", lambda: all(raiser(0)))
show("all walks to a truthy", lambda: all(raiser(1)))
show("any stops at a truthy", lambda: any(raiser(1)))
show("any walks to a falsy", lambda: any(raiser(0)))


class Loud:
    def __init__(self, value):
        self.count = 0
        self.value = value

    def __bool__(self):
        self.count += 1
        return bool(self.value)


def loud(*values):
    items = [Loud(value) for value in values]
    return items


def read(items):
    return [item.count for item in items]


ITEMS = loud(1, 0, 1)
show("all reads until the zero", lambda: (all(ITEMS), read(ITEMS)))
ITEMS = loud(0, 1, 1)
show("any reads until the one", lambda: (any(ITEMS), read(ITEMS)))
ITEMS = loud(1, 1, 1)
show("all reads every item", lambda: (all(ITEMS), read(ITEMS)))
ITEMS = loud(0, 0, 0)
show("any reads every item", lambda: (any(ITEMS), read(ITEMS)))

print("== the walk being asked for")
show("all of iterator", lambda: all(iter([1, 0])))
show("any of iterator", lambda: any(iter([0, 1])))
show("all of reversed", lambda: all(reversed([0, 1])))
show("all of map", lambda: all(map(bool, [1, 1])))
show("any of filter", lambda: any(filter(None, [0, 1])))
show("any of enumerate", lambda: any(enumerate([0])))
show("all of zip", lambda: all(zip([1], [2])))
show("all of dict view", lambda: all({1: 2}.keys()))


class Walked:
    def __init__(self, values):
        self.values = values

    def __iter__(self):
        return iter(self.values)


show("all of a declared iter", lambda: all(Walked([1, 0])))
show("any of a declared iter", lambda: any(Walked([0, 1])))


class Stepped:
    def __init__(self, values):
        self.values = values
        self.at = 0

    def __iter__(self):
        return self

    def __next__(self):
        if self.at >= len(self.values):
            raise StopIteration
        self.at += 1
        return self.values[self.at - 1]


show("all of a stepped walk", lambda: all(Stepped([1, 1, 0])))
show("any of a stepped walk", lambda: any(Stepped([0, 1])))


class EndedWithValue:
    def __iter__(self):
        return self

    def __next__(self):
        raise StopIteration(5)


show("all of a valued end", lambda: all(EndedWithValue()))
show("any of a valued end", lambda: any(EndedWithValue()))


class NotAStepper:
    def __iter__(self):
        return [1, 2]


show("iter answers a list", lambda: all(NotAStepper()))


class NoWalk:
    def __iter__(self):
        raise ValueError("no walk here")


show("iter refuses", lambda: all(NoWalk()))


class Old:
    def __init__(self, values):
        self.values = values

    def __getitem__(self, index):
        if index >= len(self.values):
            raise IndexError(index)
        return self.values[index]


show("all of an old sequence", lambda: all(Old([1, 0])))
show("any of an old sequence", lambda: any(Old([0, 1])))


class Counted:
    def __init__(self, values):
        self.values = values

    def __len__(self):
        return len(self.values)

    def __getitem__(self, index):
        return self.values[index]


show("all of a counted old sequence", lambda: all(Counted([1, 1])))


def endsWithValue():
    yield 1
    return 2


show("all of a returning generator", lambda: all(endsWithValue()))
show("any of a returning generator", lambda: any(endsWithValue()))

print("== how an item answers the truth question")


class ByLen:
    def __init__(self, size):
        self.size = size

    def __len__(self):
        return self.size


class ByBool:
    def __init__(self, value):
        self.value = value

    def __bool__(self):
        return self.value


class BothWays:
    def __bool__(self):
        return False

    def __len__(self):
        return 5


class ByIndex:
    def __index__(self):
        return 0


class NoneBool:
    __bool__ = None


class NoneLen:
    __len__ = None


class RaisedBool:
    def __bool__(self):
        raise KeyError("no answer")


show("len zero", lambda: all([ByLen(0)]))
show("len two", lambda: all([ByLen(2)]))
show("len negative", lambda: all([ByLen(-1)]))
show("len huge", lambda: all([ByLen(2**70)]))
show("len float", lambda: all([ByLen(1.5)]))
show("len text", lambda: all([ByLen("two")]))
show("bool false", lambda: all([ByBool(False)]))
show("bool true", lambda: all([ByBool(True)]))
show("bool int", lambda: all([ByBool(3)]))
show("bool none", lambda: all([ByBool(None)]))
show("bool text", lambda: all([ByBool("x")]))
show("bool list", lambda: all([ByBool([1])]))
show("bool beats len", lambda: all([BothWays()]))
show("index is not truth", lambda: all([ByIndex()]))
show("bool filed as none", lambda: all([NoneBool()]))
show("len filed as none", lambda: all([NoneLen()]))
show("bool raises", lambda: any([RaisedBool()]))
show("the same three from bool", lambda: [bool(ByBool(3))])


class FromException(Exception):
    __bool__ = None


class FromKeyError(KeyError):
    __bool__ = None


class FromBaseMeasured(BaseException):
    __len__ = None


show("exception subclass", lambda: bool(FromException("x")))
show("walked exception subclass", lambda: all([FromException("x"), 1]))
show("keyerror subclass", lambda: any([FromKeyError("x")]))
show("exception len filed as none", lambda: len(FromBaseMeasured("x")))
show("exception walk of lengths", lambda: all([FromBaseMeasured("x")]))
show("a class asked itself", lambda: all([NoneBool]))



show("two walks of lengths", lambda: (all([ByLen(1), ByLen(0)]), any([ByLen(0), ByLen(1)])))

print("== the calls themselves")
show("all of an int", lambda: all(1))
show("any of none", lambda: any(None))
show("all of a float", lambda: any(2.5))
show("all of a type", lambda: all(int))
show("all of a class", lambda: any(NoneBool))
show("all of two", lambda: all([1], [2]))
show("all of none as second", lambda: all([1], None))
show("all of nothing", lambda: all())
show("any of nothing", lambda: any())
show("all by keyword", lambda: all(iterable=[1]))
show("any by keyword", lambda: any(x=[1]))
