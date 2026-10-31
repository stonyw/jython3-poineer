"""What {@code slice} files for itself, and which of its three bounds {@code indices} folds first.

{@code slice} is the one built-in type whose values the compiler makes without asking a constructor: every
{@code a[1:2:3]} builds one. Until now the type kept no dictionary of its own, so {@code slice.indices}, its
three fields and its six comparison rows were all missing, and {@code dir(slice)} was four names short. The
rows are measured below in the order {@code vars(slice)} shows them.

{@code S.indices(len)} is the interesting half. It is {@code _PySlice_GetLongIndices} — the arbitrary
precision cousin of the machine-word arithmetic a subscript uses — so a bound of {@code 2 ** 100} is clipped
to the length rather than saturated, and a step of {@code -(2 ** 70) - 5} is reported whole instead of being
folded into a size.

Every class here is used through an instance, and nothing prints a bound method, whose {@code repr} carries
an address.
"""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


class Bad:
    def __index__(self):
        return 1.5


class Index:
    def __index__(self):
        return 3


class Raised:
    def __index__(self):
        raise ValueError("boom")


class Nothing:
    pass


class NoneHolder:
    __index__ = None


class Texty:
    def __str__(self):
        return "2"


class Step:
    def __index__(self):
        return -4


ROW_NAMES = ["__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__",
             "__gt__", "__ge__", "indices", "__reduce__", "start", "stop", "step", "__doc__"]

print("== the rows the dictionary files")
print("keys             ", said(lambda: list(vars(slice)) == ROW_NAMES))
print("count, dir       ", said(lambda: (len(vars(slice)), len(dir(slice)))))
for name in ROW_NAMES:
    row = vars(slice)[name]
    print("  %-10s %-22s %-16s %r" % (name, type(row).__name__,
                                      getattr(row, "__qualname__", "-"),
                                      getattr(row, "__doc__", "-")))
print("== the tables the rows carry")
for name in ("__new__", "indices", "__reduce__", "__lt__", "start"):
    row = vars(slice)[name]
    print("  %-10s sig=%r objclass=%r" % (
        name, getattr(row, "__text_signature__", "-"),
        getattr(getattr(row, "__objclass__", None), "__name__", "-")))
print("unbound is filed ", said(lambda: slice.__dict__["indices"] is vars(slice)["indices"]))
print("descriptor reprs ", said(lambda: [repr(vars(slice)[n]) for n in ("indices", "__reduce__", "start")]))
print("slice names      ", said(lambda: (slice.__module__, slice.__qualname__, type(slice).__name__)))
print("own doc          ", said(lambda: slice.__doc__))
print("text signature   ", said(lambda: slice.__text_signature__))
print("mro              ", said(lambda: [t.__name__ for t in slice.__mro__]))

print("== the three bounds")
s = slice(1, 2, 3)
print("fields           ", said(lambda: (s.start, s.stop, s.step)))
print("one argument     ", said(lambda: (slice(5).start, slice(5).stop, slice(5).step)))
print("through class    ", said(lambda: (repr(slice.start), slice.stop(1, 2, 3))))
print("write value      ", said(lambda: setattr(s, "start", 9)))
print("delete value     ", said(lambda: delattr(s, "start")))
print("write class      ", said(lambda: setattr(slice, "start", 9)))
print("delete class     ", said(lambda: delattr(slice, "step")))
print("read foreign     ", said(lambda: vars(slice)["start"].__get__(3)))
print("get with nothing ", said(lambda: vars(slice)["start"].__get__()))
print("set through descr", said(lambda: vars(slice)["start"].__set__(s, 9)))
print("del through descr", said(lambda: vars(slice)["start"].__delete__(s)))
print("read unbound     ", said(lambda: vars(slice)["start"].__get__(s, slice)))
print("names it adds    ", said(lambda: sorted(n for n in vars(slice) if not n.startswith("__"))))

print("== building one")
print("slice()          ", said(lambda: slice()))
print("slice(1, 2, 3, 4)", said(lambda: slice(1, 2, 3, 4)))
print("slice(1, 2, 3, 4, 5)", said(lambda: slice(1, 2, 3, 4, 5)))
print("slice(stop=1)    ", said(lambda: slice(stop=1)))
print("new with nothing ", said(lambda: slice.__new__(slice)))
print("new too many     ", said(lambda: slice.__new__(slice, 1, 2, 3, 4)))
print("new with name    ", said(lambda: slice.__new__(slice, 1, stop=2)))
print("new foreign class", said(lambda: slice.__new__(int)))
print("new keeps fields ", said(lambda: slice.__new__(slice, 1, 2, 3).start))
print("init says nothing", said(lambda: slice.__init__(s, 1, 2)))
print("three reprs      ", said(lambda: (repr(slice(1, 2, 3)), repr(slice(None)), repr(slice(5)))))

print("== S.indices(len)")
SHAPES = [(slice(1, 5, 2), "slice(1, 5, 2)"), (slice(1, 5), "slice(1, 5)"),
          (slice(5), "slice(5)"), (slice(None), "slice(None)"),
          (slice(None, None, -1), "slice(None, None, -1)"),
          (slice(2, None, -1), "slice(2, None, -1)"),
          (slice(-3, None, 2), "slice(-3, None, 2)"), (slice(10, 20), "slice(10, 20)"),
          (slice(-100, 100, 7), "slice(-100, 100, 7)"), (slice(3, 3), "slice(3, 3)"),
          (slice(0, 0, 3), "slice(0, 0, 3)"), (slice(True, False), "slice(True, False)"),
          (slice(None, 3, None), "slice(None, 3, None)")]
for shape, label in SHAPES:
    for length in (0, 1, 10):
        print("  %-22s .indices(%-3s) %s" % (label, length, said(lambda v=shape, n=length: v.indices(n))))
print("method form      ", said(lambda: slice.indices(s, 4)))
print("no argument      ", said(lambda: s.indices()))
print("two arguments    ", said(lambda: s.indices(1, 2)))
print("named length     ", said(lambda: s.indices(length=1)))
print("unbound empty    ", said(lambda: slice.indices(s)))

print("== indices over the widths")
print("2 ** 70          ", said(lambda: slice(2 ** 70, 2 ** 70).indices(10)))
print("huge stop        ", said(lambda: slice(2, 2 ** 100).indices(5)))
print("huge negative    ", said(lambda: slice(-(2 ** 100), None).indices(5)))
print("wide step        ", said(lambda: slice(None, None, -(2 ** 70) - 5).indices(10)))
print("step 2 ** 70     ", said(lambda: slice(None, None, 2 ** 70).indices(10)))
print("step from class  ", said(lambda: slice(0, 5, Step()).indices(10)))
print("negative wide step", said(lambda: slice(None, None, Step()).indices(10)))
print("length is True   ", said(lambda: s.indices(True)))
print("length from index", said(lambda: s.indices(Index())))
print("2 ** 100 length  ", said(lambda: slice(1, 2).indices(2 ** 100)))

print("== what indices refuses, and in whose words")
for name in ("Bad", "Index", "Raised", "Nothing", "NoneHolder", "Texty"):
    value = globals()[name]()
    print("  length %-11s   %s" % (name, said(lambda v=value: slice(1, 2).indices(v))))
    print("  bound  %-11s   %s" % (name, said(lambda v=value: slice(v, 2).indices(4))))
print("negative length  ", said(lambda: slice(1, 2).indices(-1)))
print("length -2 ** 70  ", said(lambda: slice(1, 2).indices(-(2 ** 70))))
print("zero step        ", said(lambda: slice(1, 2, 0).indices(4)))
print("bad step         ", said(lambda: slice(1, 2, Bad()).indices(4)))
print("bad step and bound", said(lambda: slice(Bad(), 2, Bad()).indices(4)))
print("step before stop ", said(lambda: slice(1, Bad(), Bad()).indices(4)))
print("start before stop", said(lambda: slice(Bad(), Bad(), 1).indices(4)))
print("none is allowed  ", said(lambda: slice(None, None, None).indices(4)))
print("float bound      ", said(lambda: slice(1.5, 2).indices(4)))
print("float length     ", said(lambda: slice(1, 2).indices(4.5)))

print("== the six orderings")
ONE = slice(1, 2, 3)
SAME = slice(1, 2, 3)
print("eq, ne           ", said(lambda: (ONE == SAME, ONE != SAME)))
print("eq, identity     ", said(lambda: (ONE == ONE, ONE < ONE, ONE <= ONE, ONE >= ONE)))
print("different step   ", said(lambda: ONE == slice(1, 2, 4)))
print("none versus zero ", said(lambda: slice(None) == slice(0, None, None)))
print("against tuple    ", said(lambda: ONE == (1, 2, 3)))
print("tuple against it ", said(lambda: (1, 2, 3) == ONE))
print("less than tuple  ", said(lambda: ONE < (1, 2, 3)))
print("ordering         ", said(lambda: [slice(1, 2) < b for b in (slice(1, 3), slice(1, 1), slice(1, 2))]))
print("length decides   ", said(lambda: slice(1, 2) < slice(1, 2, 4)))
print("mixed fields     ", said(lambda: slice(1, "a") < slice(1, 2)))
print("mixed the other  ", said(lambda: slice(1, 2) < slice(1, "a")))
print("nan fields       ", said(lambda: (lambda u: (slice(u) == slice(u), slice(u) != slice(u),
                                                    slice(u) < slice(u), slice(u) <= slice(u)))(Unfair())))
print("two nans, folded ", said(lambda: (lambda u: slice(u, 2) < slice(u, 3))(Unfair())))
print("rows in the tower", said(lambda: (slice.__eq__(ONE, SAME), slice.__lt__(ONE, SAME))))
print("declined by row  ", said(lambda: slice.__eq__(ONE, (1, 2, 3))))
print("bool of a slice  ", said(lambda: bool(ONE)))

print("== hashes, reprs, pickling")
print("hash equal       ", said(lambda: hash(ONE) == hash(SAME)))
print("hash of none     ", said(lambda: hash(slice(None)) == hash(slice(None, None, None))))
print("repr             ", said(lambda: repr(ONE)))
print("reduce           ", said(lambda: ONE.__reduce__()))
print("reduce of one    ", said(lambda: slice(1).__reduce__()))
print("no getnewargs    ", said(lambda: hasattr(ONE, "__getnewargs__")))
print("unbound reduce   ", said(lambda: slice.__reduce__(ONE)))
print("reduce with arg  ", said(lambda: ONE.__reduce__(1)))
print("reduce named     ", said(lambda: ONE.__reduce__(x=1)))
print("reduce no receiver", said(lambda: slice.__reduce__()))
print("format declines  ", said(lambda: format(ONE, "d")))
print("format empty     ", said(lambda: format(ONE, "")))

print("== the readers that reach the fields anyway")
DATA = [0, 1, 2, 3, 4]
print("list slice       ", said(lambda: DATA[ONE]))
print("string slice     ", said(lambda: "abcde"[slice(1, 4)]))
print("slice of slice   ", said(lambda: DATA[::2][::2]))
print("subscript keeps  ", said(lambda: type(DATA[1:3]) is list))
print("dict rejects     ", said(lambda: {1: 2}[ONE]))
print("set of slices    ", said(lambda: len({slice(1), slice(1), slice(2)})))
