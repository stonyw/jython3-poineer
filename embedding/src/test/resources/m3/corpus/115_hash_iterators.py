"""The five rows each of the seven cursors over a hash table files for itself.

Seven types, two method tables: {@code dictiter_methods} is the {@code tp_methods} of all six cursors a
{@code dict} hands out — three reading the table forwards and three from its last entry back — and {@code
setiter_methods} is the table of the one cursor {@code set} and {@code frozenset} share. Both tables are
two rows, the estimate and the pickle, and neither has the third row the sequence cursors file: a position
in a hash table is nothing to restart from, because the table may have been rebuilt since. Beside them sit
the two slot copies, so each book has five names in one order and three of the five are not ones {@code
object} already files.

What the seven disagree about is only the walk. A dictionary or a set is the one source that can change
under a cursor, so each step compares the count the table had when the cursor was made against the count it
has now, and parts company for good when the two stop agreeing — the refusal is kept, and no later change
that puts the count back talks it out. The pickle is a call of {@code iter} on a list of what is still to
come, made by driving a copy of the cursor, so the cursor a program holds is not consumed by the question
and not marked by the refusal the question may bring back.

Nothing here prints a cursor whole: both halves of a repr carry an address. A set is walked one element at
a time wherever the order of the walk would show, since a set's entries are in hash order and only a
dictionary's are in the order they were written.

"""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


def reduced(it):
    payload = it.__reduce__()
    return (payload[0] is iter,) + tuple(payload[1:])


def ladder(kind, steps):
    seen = [kind.__length_hint__()]
    for _ in range(steps):
        said(lambda: kind.__next__())
        seen.append(kind.__length_hint__())
    return seen


ROWS = ["__iter__", "__next__", "__length_hint__", "__reduce__", "__doc__"]
D = {"a": 1, "b": 2, "c": 3}

print("== which cursor a call hands out")
HANDS = [
    ("iter({})", lambda: iter({})),
    ("iter(d)", lambda: iter(D)),
    ("iter(d.keys())", lambda: iter(D.keys())),
    ("iter(d.values())", lambda: iter(D.values())),
    ("iter(d.items())", lambda: iter(D.items())),
    ("reversed(d)", lambda: reversed(D)),
    ("reversed(d.keys())", lambda: reversed(D.keys())),
    ("reversed(d.values())", lambda: reversed(D.values())),
    ("reversed(d.items())", lambda: reversed(D.items())),
    ("d.__reversed__()", lambda: D.__reversed__()),
    ("iter({1})", lambda: iter({1})),
    ("iter(frozenset)", lambda: iter(frozenset({1}))),
    ("iter(C.__dict__)", lambda: iter(int.__dict__)),
    ("reversed(C.__dict__)", lambda: reversed(int.__dict__)),
    ("C.__dict__.__reversed__()", lambda: int.__dict__.__reversed__()),
    ("reversed(set())", lambda: reversed(set())),
    ("reversed(frozenset())", lambda: reversed(frozenset())),
]
for label, make in HANDS:
    print("  %-24s %s" % (label, said(lambda m=make: type(m()).__name__)))

print("== the five rows each book files")
SEVEN = [
    iter(D),
    iter(D.values()),
    iter(D.items()),
    reversed(D),
    reversed(D.values()),
    reversed(D.items()),
    iter({1}),
]
for one in SEVEN:
    T = type(one)
    print(T.__name__)
    print("  keys             ", said(lambda: list(vars(T)) == ROWS))
    print("  count, dir       ", said(lambda: (len(vars(T)), len(dir(T)))))
    print("  gained over obj  ", said(lambda: sorted(set(dir(T)) - set(dir(object)))))
    print("  value same dir   ", said(lambda: sorted(dir(one)) == sorted(dir(T))))
    print("  order            ", said(lambda: [c.__name__ for c in T.__mro__]))
    print("  subclass of obj  ", said(lambda: T.__base__ is object))
    print("  names            ", said(lambda: (T.__name__, T.__qualname__, T.__module__)))
    print("  type doc         ", said(lambda: T.__doc__))
    for name in ROWS[:-1]:
        row = vars(T)[name]
        print("    %-17s %-22s %r" % (name, type(row).__name__, getattr(row, "__doc__", "<none>")))
    print("    doc row          %-22s %r" % (type(vars(T)["__doc__"]).__name__, vars(T)["__doc__"]))
    print("    doc row is None ", said(lambda: vars(T)["__doc__"] is None))

print("== the tables the rows carry")
for one in (iter(D), iter(D.items()), iter({1})):
    T = type(one)
    print(T.__name__)
    for name in ROWS:
        row = vars(T)[name]
        print("    %-17s %-22s sig=%r" % (name, type(row).__name__,
                                         getattr(row, "__text_signature__", "-")))

print("== walking each of the seven")
for one in SEVEN:
    print("  %-27s" % type(one).__name__,
          said(lambda it=one: (it.__iter__() is it, iter(it) is it, list(it))))
walked = iter(D)
print("  three steps      ", said(lambda: [walked.__next__() for _ in range(3)]))
print("  the end          ", said(lambda: walked.__next__()))
print("  again            ", said(lambda: walked.__next__()))
print("  next builtin     ", said(lambda: next(iter(D))))
print("  next of spent    ", said(lambda: next(walked)))
print("  next with default", said(lambda: next(walked, "gone")))
print("  len of a cursor  ", said(lambda: len(walked)))
print("  vars of a cursor ", said(lambda: vars(walked)))
print("  dict of a cursor ", said(lambda: walked.__dict__))
print("  repr shape       ", said(lambda: repr(iter(D)).split(" at ")[0]))
print("  repr of a set one", said(lambda: repr(iter({1})).split(" at ")[0]))
print("  empty dict walk  ", said(lambda: list(iter({}))))
print("  empty reversed   ", said(lambda: list(reversed({}))))
print("  for over items   ", said(lambda: [pair for pair in iter(D.items())]))
print("  sum of a walk    ", said(lambda: sum(iter(D.values()))))
print("  membership       ", said(lambda: "a" in iter(D)))

print("== the hint, and what it counts")
for source in (D, D.values(), D.items(), frozenset(D)):
    it = iter(source)
    print("  %-27s %r" % (type(it).__name__, ladder(it, 4)))
for source in (D, D.values(), D.items()):
    it = reversed(source)
    print("  %-27s %r" % (type(it).__name__, ladder(it, 4)))
print("  a hint is an int ", said(lambda: type(iter(D).__length_hint__()).__name__))
print("  hint of empty    ", said(lambda: iter({}).__length_hint__()))
print("  hint of spent    ", said(lambda: walked.__length_hint__()))
print("  hint with an arg ", said(lambda: iter(D).__length_hint__(1)))
print("  hint named       ", said(lambda: iter(D).__length_hint__(x=1)))

print("== a table that changes under a cursor")
d = {"a": 1, "b": 2}
it = iter(d)
d["c"] = 3
print("  grew   hint      ", said(lambda: it.__length_hint__()))
print("  grew   step      ", said(lambda: it.__next__()))
print("  after refuse     ", said(lambda: it.__length_hint__()))
del d["c"]
print("  size back  hint  ", said(lambda: it.__length_hint__()))
print("  size back  step  ", said(lambda: it.__next__()))
print("  size back  list  ", said(lambda: list(it)))
print("  size back reduce ", said(lambda: it.__reduce__()))

print("== the same refusal over a set")
s = {"a", "b"}
si = iter(s)
s.add("c")
print("  grew   hint      ", said(lambda: si.__length_hint__()))
print("  grew   step      ", said(lambda: si.__next__()))
s.discard("c")
print("  size back  hint  ", said(lambda: si.__length_hint__()))
print("  size back  step  ", said(lambda: si.__next__()))
print("  size back reduce ", said(lambda: si.__reduce__()))

print("== shrinking is the same complaint")
d = {"a": 1, "b": 2, "c": 3}
it = iter(d)
del d["b"]
print("  hint             ", said(lambda: it.__length_hint__()))
print("  step             ", said(lambda: it.__next__()))
print("  reduce           ", said(lambda: it.__reduce__()))

print("== a change put back before the next step is no change")
d = {"a": 1, "b": 2}
it = iter(d)
print("  first            ", said(lambda: it.__next__()))
d["c"] = 3
del d["c"]
d["a"] = 9
print("  rest             ", said(lambda: list(it)))
print("  pairs stay       ", said(lambda: list(iter({"a": 1}.items()))))

print("== the state a cursor reports")
for source in (D, D.values(), D.items(), {1}):
    it = iter(source)
    print("  %-27s %-6s %s" % (type(it).__name__, "fresh", said(lambda v=it: reduced(v))))
    said(lambda v=it: v.__next__())
    print("  %-27s %-6s %s" % (type(it).__name__, "one", said(lambda v=it: reduced(v))))
    said(lambda v=it: list(v))
    print("  %-27s %-6s %s" % (type(it).__name__, "done", said(lambda v=it: reduced(v))))
    print("  %-27s %-6s %s" % (type(it).__name__, "again", said(lambda v=it: reduced(v))))
for source in (D, D.values(), D.items()):
    it = reversed(source)
    print("  %-27s %-6s %s" % (type(it).__name__, "fresh", said(lambda v=it: reduced(v))))
    said(lambda v=it: v.__next__())
    print("  %-27s %-6s %s" % (type(it).__name__, "one", said(lambda v=it: reduced(v))))
print("  empty dict       ", said(lambda: reduced(iter({}))))
print("  empty set        ", said(lambda: reduced(iter(set()))))
print("  an item pair     ", said(lambda: reduced(iter({"a": 1}.items()))))

print("== the question does not spend the cursor")
it = iter(D)
print("  twice the same   ", said(lambda: [reduced(it), reduced(it)]))
print("  still walking    ", said(lambda: list(it)))

print("== what a payload restarts into")
it = iter(D.items())
said(lambda: it.__next__())
payload = it.__reduce__()
back = payload[0](payload[1][0])
print("  type of restart  ", said(lambda: type(back).__name__))
print("  the rest         ", said(lambda: list(back)))
print("  original intact  ", said(lambda: it.__length_hint__()))

print("== a pickle of a changed table refuses, and leaves the cursor alone")
d = {"a": 1, "b": 2}
it = iter(d)
d["c"] = 3
print("  reduce           ", said(lambda: it.__reduce__()))
print("  hint             ", said(lambda: it.__length_hint__()))
del d["c"]
print("  step after       ", said(lambda: it.__next__()))
print("  rest             ", said(lambda: list(it)))

print("== no cursor of the seven can be made")
for one in SEVEN:
    T = type(one)
    print("  %-27s %-8s %s" % (T.__name__, "call", said(lambda k=T: k())))
    print("  %-27s %-8s %s" % (T.__name__, "kw", said(lambda k=T: k(x=1))))
print("  from object      ", said(lambda: object.__new__(type(iter(D)))))

print("== the two rows refuse another cursor")
KEYS = type(iter(D))
SET = type(iter({1}))
OTHERS = [iter(D), iter(D.values()), iter(D.items()), reversed(D), reversed(D.values()),
          reversed(D.items()), iter({1})]
for T in (KEYS, SET):
    print(T.__name__)
    for name in ("__length_hint__", "__reduce__"):
        row = getattr(T, name)
        print("    %-16s unbound %s" % (name, said(lambda r=row: r())))
        print("    %-16s named   %s" % (name, said(lambda r=row: r(**{}))))
        print("    %-16s int     %s" % (name, said(lambda r=row: r(1))))
        for other in OTHERS:
            print("    %-16s %-27s %s" % (name, type(other).__name__,
                                          said(lambda r=row, v=other: r(v))))

print("== a row read from a value runs on that value")
it = iter(D)
said(lambda: it.__next__())
print("  bound hint       ", said(lambda: type(iter(D)).__length_hint__.__get__(it)()))
print("  bound reduce     ", said(lambda: type(iter(D)).__reduce__.__get__(it)()))
print("  via getattr      ", said(lambda: it.__length_hint__()))
