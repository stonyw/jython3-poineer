"""The seven rows the one cursor a builtin call makes for itself files.

`enumobject.c` writes a type and a constructor in one file, and the constructor is the builtin: `reversed`
in `builtins` is a type rather than the function `map` and `zip` are, because `reversed_vectorcall` (`:423`)
is the type's own `tp_vectorcall`, so `reversed(x)` and `reversed.__new__(reversed, x)` are two spellings of
one call. It is the one cursor this runtime hands out that a program can make for itself — `tp_new` is null
in the fifteen that walk a list, a tuple, a string, a range, a dict, a view or a set, every one of which
answers its own name with `cannot create '%s' instances` — and the one that reports a sentence of
its own, since `tp_doc` is `reversed_new__doc__`, the constructor's. Seven names in the book rather than the
six a sequence cursor files or the five a hash cursor does: `__new__` above the two slot copies, and the
four names `object` does not already file are the same four a sequence cursor gains.

What the constructor asks, in order: the `__reversed__` of the value's type, which a list, a range, a dict,
a view and a window each answer with a cursor of their own and which a `None` in that position refuses as
firmly as an absence; then `PySequence_Check`, the protocol rather than a try at it, so a class that counts
but never subscripts is refused whatever it may hold; then `PySequence_Size`, whose refusal a class that
subscripts but never counts reaches. Only then is the cursor made — holding the sequence, not a copy of it,
which is the whole of the difference between this walk and the seven that cross a hash table: every step
reads the item again, so a source cut down during the walk is noticed by the read rather than by a count,
and the complaint it brings back is the source's own.

`__setstate__` is the row with the strangest guard in the family: a cursor that has run out carries `-1`,
and the restart reads that and does nothing at all, which makes an empty source unrestartable for as long as
it lives — `n - 1` for a source of nought *is* that marker. Nothing here prints a cursor whole: both halves
of a repr carry an address.

"""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


def masked(action):
    """The repr a value answers, with the address the reference writes beside it taken back out."""
    try:
        made = action()
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)
    text = made if isinstance(made, str) else repr(made)
    before, at, after = text.rpartition(" at ")
    return repr(before + ">") if at else repr(text)


def reduced(it):
    """The pickle, with the type it names replaced by whether that type is `reversed` itself."""
    payload = it.__reduce__()
    return (payload[0] is reversed,) + tuple(payload[1:])


def rebuilt(payload):
    """`reversed(*args)` then `__setstate__(state)` — the two halves of the pickle put back together."""
    it = reversed(*payload[1])
    if len(payload) > 2:
        it.__setstate__(payload[2])
    return it


def walked(steps, source):
    it = reversed(source)
    for _ in range(steps):
        next(it, None)
    return it


def jumped(state, source=(10, 20, 30, 40)):
    it = reversed(source)
    said(lambda: it.__setstate__(state))
    return (it.__length_hint__(), list(it))


SRC = (10, 20, 30, 40)

D = {1: 2}

# The fifteen cursors that answer `__reversed__` or `__iter__` with a type of their own, none of which a
# program can make by naming that type: `list` hands back two, `dict` and its views five, and the rest one
# apiece from `iter`.
CURSORS = [
    iter([1, 2]),
    list([1, 2]).__reversed__(),
    iter((1, 2)),
    iter(b"ab"),
    iter("ab"),
    iter("aé"),
    iter(range(3)),
    iter(range(2**80)),
    iter(D),
    iter(D.values()),
    iter(D.items()),
    D.__reversed__(),
    D.values().__reversed__(),
    D.items().__reversed__(),
    iter({1}),
]


class Backed:
    def __init__(self, items):
        self.items = items

    def __len__(self):
        return len(self.items)

    def __getitem__(self, position):
        return self.items[position]


class Counted:
    def __len__(self):
        return 3


class Indexed:
    def __getitem__(self, position):
        return position


class Answers:
    def __reversed__(self):
        return ["not", "a", "cursor"]


class Refuses:
    __reversed__ = None


class Written:
    def __init__(self):
        self.items = [1, 2, 3]

    def __len__(self):
        return len(self.items)

    def __getitem__(self, position):
        return self.items[position]


ON_SELF = Written()
ON_SELF.__reversed__ = lambda: ["asked"]


class Fails:
    def __reversed__(self):
        raise ValueError("not now")


class Steps:
    def __init__(self, stops, failure):
        self.stops = stops
        self.failure = failure

    def __len__(self):
        return 3

    def __getitem__(self, position):
        if position >= self.stops:
            raise self.failure
        return position


class Index:
    def __index__(self):
        return 1


ROWS = ["__new__", "__iter__", "__next__", "__length_hint__", "__reduce__", "__setstate__", "__doc__"]

print("== the builtin is the type the call makes")
print("  of its own kind   ", said(lambda: type(reversed).__name__))
print("  names             ", said(lambda: (reversed.__name__, reversed.__qualname__, reversed.__module__)))
print("  rows              ", said(lambda: list(vars(reversed))))
print("  in that order     ", said(lambda: list(vars(reversed)) == ROWS))
print("  counts, dir       ", said(lambda: (len(vars(reversed)), len(dir(reversed)))))
print("  gained over object", said(lambda: sorted(set(dir(reversed)) - set(dir(object)))))
print("  order             ", said(lambda: [c.__name__ for c in reversed.__mro__]))
print("  subclass of object", said(lambda: reversed.__base__ is object))
print("  kind doc          ", said(lambda: reversed.__doc__))
print("  value doc is one  ", said(lambda: reversed(SRC).__doc__ == reversed.__doc__))
print("  signature         ", said(lambda: reversed.__text_signature__))
print("  list hands over   ", said(lambda: (type(reversed([])).__name__, reversed([]).__doc__)))

print("== the seven rows and the sentences they carry")
for name in ROWS:
    row = vars(reversed)[name]
    print("  ", name.ljust(16), said(lambda r=row: type(r).__name__), "|",
          said(lambda r=row: getattr(r, "__qualname__", "no name")), "|",
          said(lambda r=row, n=name: r == reversed.__doc__ if n == "__doc__" else r.__doc__))
print("  the two copies    ", said(lambda: type(vars(reversed)["__iter__"]) is
                                   type(vars(reversed)["__next__"])))
print("  iter answers self ", said(lambda: (lambda it: it.__iter__() is it)(reversed(SRC))))
print("  value has no dict ", said(lambda: vars(reversed(SRC))))
print("  objclass is self  ", said(lambda: vars(reversed)["__next__"].__objclass__ is reversed))

print("== what the constructor asks, in its order")
for label, action in [
    ("reversed([1,2,3])", lambda: list(reversed([1, 2, 3]))),
    ("reversed(SRC)", lambda: list(reversed(SRC))),
    ("reversed('abc')", lambda: list(reversed("abc"))),
    ("reversed(b'ab')", lambda: list(reversed(b"ab"))),
    ("reversed(Backed)", lambda: list(reversed(Backed([1, 2, 3])))),
    ("kind of tuple", lambda: type(reversed(SRC)).__name__),
    ("kind of list", lambda: type(reversed([1, 2])).__name__),
    ("kind of range", lambda: type(reversed(range(2))).__name__),
    ("kind of dict", lambda: type(reversed({1: 2})).__name__),
    ("kind of view", lambda: type(reversed({1: 2}.keys())).__name__),
    ("kind of window", lambda: type(reversed(int.__dict__)).__name__),
    ("kind of answer", lambda: type(reversed(Answers())).__name__),
    ("hands back", lambda: reversed(Answers())),
    ("writes it on itself", lambda: (type(reversed(ON_SELF)).__name__, list(reversed(ON_SELF)))),
]:
    print("  ", label.ljust(16), said(action))

print("== the fifteen cursors that answer the special instead, none of them made from Python")
for cursor in CURSORS:
    print("  ", type(cursor).__name__.ljust(26), said(lambda c=cursor: type(c)()))
print("  distinct kinds    ", said(lambda: len({type(c).__name__ for c in CURSORS})))
print("  this kind is called", masked(lambda: type(reversed(SRC))(SRC)))

print("== the two questions a value can fail")
for label, action in [
    ("an int", lambda: reversed(3)),
    ("nothing", lambda: reversed(None)),
    ("a set", lambda: reversed({1, 2})),
    ("a generator", lambda: reversed(iter([1, 2]))),
    ("a refusal", lambda: reversed(Refuses())),
    ("counts only", lambda: reversed(Counted())),
    ("subscripts only", lambda: reversed(Indexed())),
    ("reversed twice", lambda: reversed(reversed(SRC))),
    ("a special fails", lambda: reversed(Fails())),
    ("a class dict", lambda: type(reversed(type.__dict__)).__name__),
]:
    print("  ", label.ljust(16), said(action))

print("== the walk reads the source at every step")
print("  ladder            ", said(lambda: (lambda it: [it.__length_hint__(), next(it),
                                                        it.__length_hint__(), next(it)])(reversed(SRC))))
print("  spent             ", said(lambda: (lambda it: [next(it, "gone"), it.__length_hint__(),
                                                         next(it, "gone")])(walked(9, SRC))))
source = Backed([1, 2, 3, 4])
cutting = reversed(source)
next(cutting)
source.items.remove(4)
source.items.remove(3)
print("  cut down          ", said(lambda: list(cutting)))
print("  hint after a cut  ", said(lambda: walked(1, source).__length_hint__()))
print("  indexerror ends   ", said(lambda: list(reversed(Steps(1, IndexError("stop"))))))
print("  stopiter ends     ", said(lambda: list(reversed(Steps(1, StopIteration())))))
print("  keyerror travels  ", said(lambda: list(reversed(Steps(1, KeyError("nope"))))))
print("  a slice read      ", said(lambda: list(reversed(Backed([1, 2, 3])))))
print("  empty source      ", said(lambda: list(reversed(Backed([])))))

print("== the pickle names its own type, and the restart clamps")
print("  fresh             ", said(lambda: reduced(reversed(SRC))))
print("  one step in       ", said(lambda: reduced(walked(1, SRC))))
print("  spent             ", said(lambda: reduced(walked(9, SRC))))
print("  at nought         ", said(lambda: reduced(reversed(Backed([])))))
print("  a list one        ", said(lambda: reduced(walked(9, []))))
print("  of a backed one   ", said(lambda: (lambda p: (p[0] is reversed,
                                                        type(p[1][0]).__name__, p[2]))(
                                                            reversed(Backed([1, 2, 3])).__reduce__())))
print("  found again       ", said(lambda: (lambda p: (list(rebuilt(p)), p[2]))(reversed(SRC).__reduce__())))
print("  round trip mid    ", said(lambda: reduced(rebuilt(walked(1, SRC).__reduce__()))))
print("  jump to 1         ", said(lambda: jumped(1)))
print("  jump to 9         ", said(lambda: jumped(9)))
print("  jump to -1        ", said(lambda: jumped(-1)))
print("  jump to -5        ", said(lambda: jumped(-5)))
print("  jump to True      ", said(lambda: jumped(True)))
print("  jump to index     ", said(lambda: jumped(Index())))
print("  jump to text      ", said(lambda: jumped("x")))
print("  jump to nothing   ", said(lambda: jumped(None)))
print("  spent ignores it  ", said(lambda: (lambda it: (it.__setstate__(2), it.__length_hint__(),
                                                        list(it)))(walked(9, SRC))))
print("  empty never back  ", said(lambda: jumped(0, Backed([]))))
print("  a spent source    ", said(lambda: jumped(1, walked(9, SRC))))

print("== the call's own refusals")
print("  no argument       ", said(lambda: reversed()))
print("  two arguments     ", said(lambda: reversed([1], [2])))
print("  keyword           ", said(lambda: reversed(sequence=[1, 2])))
print("  new with none     ", said(lambda: reversed.__new__(reversed)))
print("  new too many      ", said(lambda: reversed.__new__(reversed, [1], [2])))
print("  new wrong class   ", said(lambda: reversed.__new__(list, [1, 2])))
print("  new unbound       ", said(lambda: type(reversed.__new__).__name__))
print("  the repr          ", masked(lambda: repr(reversed(SRC))))
print("  made by hand      ", said(lambda: object.__new__(reversed)))
print("  a row's own words ", said(lambda: vars(reversed)["__length_hint__"](iter([]))))
print("  and the step      ", said(lambda: vars(reversed)["__next__"](iter([]))))
print("  new over a value  ", said(lambda: type(reversed(SRC).__new__(reversed, SRC)).__name__))
