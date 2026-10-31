"""What {@code range} files for itself, and which of its three parameters each reader asks.

{@code range} is the sequence type that keeps its values as arithmetic rather than as elements, and until
now it kept no dictionary of its own either: {@code len(vars(range))} was one row, so {@code count},
{@code index}, its three fields and the four other slots it declares were all missing, and {@code
dir(range)} was eleven names short. The rows are measured below in the order {@code vars(range)} shows
them.

The arithmetic is the interesting half. A length is read as a C {@code Py_ssize_t} and a subscript is not,
so {@code len(range(2 ** 70))} overflows where {@code range(10)[2 ** 70]} is merely out of range; a slice
of a range is folded against that length in arbitrary precision and answers another range, whose step is
the product of the two steps; and an integer is asked for by the arithmetic while anything else is walked
for with {@code ==}, which is why {@code 3.0 in range(1, 9, 2)} is true and {@code 1.5 in range(3)} is not.

Every class here is used through an instance, and nothing prints a bound method or an iterator, whose
{@code repr} carries an address.
"""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


class Half:
    def __eq__(self, other):
        return other == 2

    def __ne__(self, other):
        return other != 2


class Later:
    """Equal to everything, which the walk finds only where it looks."""

    def __eq__(self, other):
        return True

    def __ne__(self, other):
        return False

    __hash__ = None


class Index:
    def __index__(self):
        return 2


class Raised:
    def __index__(self):
        raise ValueError("boom")


class Nothing:
    pass


ROW_NAMES = ["__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
             "__ge__", "__iter__", "__bool__", "__len__", "__getitem__", "__contains__",
             "__reversed__", "__reduce__", "count", "index", "start", "stop", "step", "__doc__"]

r = range(1, 9, 2)
ONE = range(1)
WIDE = range(0, 3, 2 ** 70)

print("== the rows the dictionary files")
print("keys             ", said(lambda: list(vars(range)) == ROW_NAMES))
print("count, dir       ", said(lambda: (len(vars(range)), len(dir(range)))))
print("value has same   ", said(lambda: sorted(dir(r)) == sorted(dir(range))))
for name in ROW_NAMES:
    row = vars(range)[name]
    print("  %-12s %-27s %-14s %r" % (name, type(row).__name__,
                                      getattr(getattr(row, "__objclass__", None), "__name__", "-"),
                                      getattr(row, "__doc__", "-")))

print("== the tables the rows carry")
for name in ("__new__", "__len__", "__getitem__", "__contains__", "__reversed__", "__reduce__",
             "count", "index", "start"):
    row = vars(range)[name]
    print("  %-12s sig=%r obj=%r" % (
        name, getattr(row, "__text_signature__", "-"),
        getattr(getattr(row, "__objclass__", None), "__name__", "-")))
print("type doc is whole", said(lambda: range.__doc__))
print("type signature   ", said(lambda: range.__text_signature__))
print("own doc of value ", said(lambda: r.__doc__))
print("row reprs        ", said(lambda: [repr(vars(range)[n]) for n in
                                         ("__len__", "__getitem__", "count", "index",
                                          "start", "step", "__reversed__", "__reduce__")]))
print("bound kinds      ", said(lambda: (type(r.count).__name__, r.count.__name__, r.count.__qualname__)))
print("bound receiver   ", said(lambda: (r.count.__self__ is r, r.index.__self__ is r)))
print("method of class  ", said(lambda: range.count is vars(range)["count"]))

print("== the three parameters")
print("fields           ", said(lambda: (r.start, r.stop, r.step)))
print("one argument     ", said(lambda: (range(4).start, range(4).stop, range(4).step)))
print("identity         ", said(lambda: (r.start is r.start, WIDE.stop is WIDE.stop,
                                         WIDE.step is WIDE.step)))
print("wide step read   ", said(lambda: WIDE.step))
print("write value      ", said(lambda: setattr(r, "start", 0)))
print("delete value     ", said(lambda: delattr(r, "step")))
print("write class      ", said(lambda: setattr(range, "start", 0)))
print("read foreign     ", said(lambda: vars(range)["start"].__get__(3)))
print("read unbound     ", said(lambda: vars(range)["stop"].__get__(None, range)))
print("set through descr", said(lambda: vars(range)["start"].__set__(r, 0)))
print("value dictionary ", said(lambda: r.__dict__))
print("vars             ", said(lambda: vars(r)))

print("== building one")
print("range()          ", said(lambda: range()))
print("range(1, 2, 3, 4)", said(lambda: range(1, 2, 3, 4)))
print("range(stop=3)    ", said(lambda: range(stop=3)))
print("range(0, 5, 0)   ", said(lambda: range(0, 5, 0)))
print("range(1.5)       ", said(lambda: range(1.5)))
print("range(Index())   ", said(lambda: range(Index())))
print("range(Raised())  ", said(lambda: range(Raised())))
print("new with nothing ", said(lambda: range.__new__(range)))
print("new three        ", said(lambda: range.__new__(range, 1, 9, 2)))
print("new keeps fields ", said(lambda: range.__new__(range, 3).stop))
print("new foreign class", said(lambda: range.__new__(list, 3)))
print("new not a class  ", said(lambda: range.__new__(3)))
print("new no receiver  ", said(lambda: range.__new__()))
print("reprs            ", said(lambda: (repr(r), repr(range(4)), repr(range(0)), repr(WIDE))))

print("== subscripts")
print("r[2], r[-1]      ", said(lambda: (r[2], r[-1])))
print("first, last      ", said(lambda: (range(4)[0], range(4)[-1])))
print("past the end     ", said(lambda: r[9]))
print("before the start ", said(lambda: r[-5]))
print("float key        ", said(lambda: r[1.0]))
print("text key         ", said(lambda: r["1"]))
print("index key        ", said(lambda: r[Index()]))
print("raised key       ", said(lambda: r[Raised()]))
print("nothing key      ", said(lambda: r[Nothing()]))
print("wide key         ", said(lambda: range(10)[2 ** 70]))
print("wide through index", said(lambda: range(10)[Index()]))
print("unbound get      ", said(lambda: vars(range)["__getitem__"](r, 2)))
print("unbound wrong type", said(lambda: vars(range)["__getitem__"](3, 0)))
print("unbound too few  ", said(lambda: vars(range)["__getitem__"](r)))

print("== slices of a range")
print("range(10)[2:5]   ", said(lambda: range(10)[2:5]))
print("range(10)[::3]   ", said(lambda: range(10)[::3]))
print("range(10)[::-1]  ", said(lambda: range(10)[::-1]))
print("range(0,20,3)[::2]", said(lambda: range(0, 20, 3)[::2]))
print("empty slice      ", said(lambda: range(10)[5:5]))
print("reversed empty   ", said(lambda: range(10)[5:2:-1]))
print("clipped          ", said(lambda: range(10)[3:100]))
print("folded negative  ", said(lambda: range(10)[-3:]))
print("wide start       ", said(lambda: range(10)[2 ** 70:]))
print("wide stop        ", said(lambda: range(10)[:2 ** 70]))
print("wide negative    ", said(lambda: range(10)[-(2 ** 70):]))
print("wide step        ", said(lambda: range(10)[::2 ** 70]))
print("slice object     ", said(lambda: range(10)[slice(1, None, 3)]))
print("one valued       ", said(lambda: range(10)[3:4]))
print("float bound      ", said(lambda: range(10)[1.5:]))
print("zero step        ", said(lambda: range(10)[::0]))
print("list of the slice", said(lambda: list(range(10)[2:8:2])))
print("slice of a slice ", said(lambda: range(10)[1:9:2][::2]))
print("kind kept        ", said(lambda: type(range(10)[2:5]) is range))

print("== membership, count and index")
TARGETS = [("3", 3), ("5", 5), ("1", 1), ("0", 0), ("8", 8), ("9", 9), ("True", True),
           ("3.0", 3.0), ("1.5", 1.5), ("'3'", "3"), ("(3,)", (3,)), ("complex", 3 + 0j),
           ("Half()", Half()), ("Later()", Later()), ("Index()", Index()), ("Nothing()", Nothing())]
for label, target in TARGETS:
    print("  %-10s in %s" % (label, said(lambda t=target: t in r)))
COUNTS = [("3", 3), ("3.0", 3.0), ("'3'", "3"), ("Half()", Half()), ("Later()", Later()),
          ("True", True), ("1.5", 1.5)]
for label, target in COUNTS:
    print("  count(%-8s) %s" % (label, said(lambda t=target: r.count(t))))
INDEXES = [("5", 5), ("1", 1), ("2.0", 2.0), ("True", True), ("'3'", "3"), ("Half()", Half()),
           ("Nothing()", Nothing()), ("Later()", Later())]
for label, target in INDEXES:
    print("  index(%-7s) %s" % (label, said(lambda t=target: r.index(t))))
print("count of range   ", said(lambda: range(1, 9, 2).count(7)))
print("index in empty   ", said(lambda: range(0).index(0)))
print("count in empty   ", said(lambda: range(0).count(0)))
print("index wide       ", said(lambda: range(0, 2 ** 100, 2 ** 99).index(2 ** 99)))
print("no argument      ", said(lambda: r.count()))
print("two arguments    ", said(lambda: r.count(1, 2)))
print("named argument   ", said(lambda: r.count(value=1)))
print("index named      ", said(lambda: r.index(value=1)))
print("method form      ", said(lambda: (range.count(r, 3), range.index(r, 5))))
print("unbound foreign  ", said(lambda: range.count([1], 2)))
print("unbound empty    ", said(lambda: range.__reversed__()))
print("reversed with arg", said(lambda: r.__reversed__(1)))
print("in through row   ", said(lambda: vars(range)["__contains__"](r, 3)))
print("membership refused", said(lambda: vars(range)["__contains__"](r)))

print("== length, truth and walking")
print("len              ", said(lambda: (len(r), len(range(4)), len(range(0)), len(range(1)))))
print("len of wide      ", said(lambda: len(range(2 ** 70))))
print("len through row  ", said(lambda: vars(range)["__len__"](r)))
print("len refused      ", said(lambda: vars(range)["__len__"](3)))
print("len with nothing ", said(lambda: vars(range)["__len__"]()))
print("len with name    ", said(lambda: vars(range)["__len__"](r, x=1)))
print("bool             ", said(lambda: [bool(range(n)) for n in (0, 1, 2, 5, -1)]))
print("bool through row ", said(lambda: vars(range)["__bool__"](r)))
print("values walked    ", said(lambda: [v for v in r]))
print("walked backwards ", said(lambda: [v for v in range(5, 0, -2)]))
print("reversed method  ", said(lambda: list(r.__reversed__())))
print("reversed builtin ", said(lambda: list(reversed(r))))
print("reversed empty   ", said(lambda: list(reversed(range(0)))))
print("reversed one     ", said(lambda: list(reversed(range(3, 8)))))
print("reversed wide    ", said(lambda: list(reversed(range(0, 2 ** 70, 2 ** 69)))))
print("reversed refused ", said(lambda: reversed(3)))
print("sum              ", said(lambda: sum(range(100))))
print("enumerate        ", said(lambda: list(enumerate(r))))
print("list of slice    ", said(lambda: list(range(10)[::-1])[:4]))

print("== the two equalities and the four that decline")
print("same             ", said(lambda: range(1, 9, 2) == r))
print("empty equals empty", said(lambda: range(0) == range(5, 0)))
print("one spares step  ", said(lambda: range(1) == range(0, 1, 5)))
print("one needs start  ", said(lambda: range(1) == range(1, 2, 5)))
print("step matters     ", said(lambda: range(0, 6, 2) == range(0, 6, 3)))
print("not equal        ", said(lambda: range(3) != range(4)))
print("against a list   ", said(lambda: range(3) == [0, 1, 2]))
print("against an int   ", said(lambda: range(3) == 3))
print("against a tuple  ", said(lambda: (range(3),) == (range(3),)))
for op in ("__lt__", "__le__", "__gt__", "__ge__"):
    print("  %-6s two ranges %s" % (op, said(lambda m=op: getattr(range(3), m)(range(4)))))
    print("  %-6s one value  %s" % (op, said(lambda m=op: getattr(r, m)(3))))
print("ordering operator", said(lambda: range(3) < range(4)))
print("mixed ordering   ", said(lambda: range(3) < [0, 1, 2]))
print("equality through ", said(lambda: vars(range)["__eq__"](r, range(1, 9, 2))))
print("declined by row  ", said(lambda: vars(range)["__eq__"](r, (1, 9, 2))))
print("ne of itself     ", said(lambda: vars(range)["__ne__"](r, r)))
print("eq needs a second", said(lambda: vars(range)["__eq__"](r)))

print("== hashes and the state a range reduces to")
print("hash of five     ", said(lambda: hash(range(5))))
print("hash of step two ", said(lambda: hash(range(0, 5, 2))))
print("hash equal ranges", said(lambda: hash(ONE) == hash(range(0, 1, 5))))
print("hash of empties  ", said(lambda: hash(range(0)) == hash(range(5, 0))))
print("empty in a set   ", said(lambda: len({range(0), range(5, 0), range(1, 1)})))
print("set of three     ", said(lambda: len({range(5), range(0, 5, 1), range(0, 5, 2)})))
print("as dictionary key", said(lambda: {range(3): "v"}[range(3)]))
print("reduce           ", said(lambda: r.__reduce__()))
print("reduce of one    ", said(lambda: ONE.__reduce__()))
print("reduce keeps kinds", said(lambda: r.__reduce__()[1]))
print("no getnewargs    ", said(lambda: hasattr(r, "__getnewargs__")))
print("reduce with arg  ", said(lambda: r.__reduce__(1)))
print("reduce named     ", said(lambda: r.__reduce__(x=1)))
print("wide step kept   ", said(lambda: WIDE.__reduce__()[1][2]))
print("str of a range   ", said(lambda: str(r)))
print("format empty     ", said(lambda: format(r, "")))

print("== the readers that reach the three fields anyway")
DATA = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
print("bounds as slice  ", said(lambda: DATA[range(1, 8, 2).start:range(1, 8, 2).stop:range(1, 8, 2).step]))
print("length from step ", said(lambda: len(range(r.start, r.stop, r.step))))
print("count through get", said(lambda: DATA[::r.step][:2]))
print("index from field ", said(lambda: (0, 1, 2).index(range(3).stop - 1)))
print("sliced by itself ", said(lambda: range(r.start, r.stop, r.step)[r.start:]))
print("nested ranges    ", said(lambda: [len(range(n)) for n in range(4)]))
print("slice object kept", said(lambda: range(10)[slice(r.start, r.stop, r.step)]))
