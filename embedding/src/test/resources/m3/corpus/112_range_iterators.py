"""What the two cursors a range hands out file for themselves, and which of the two a range answers.

A range is a value made of arithmetic, and so is the thing that walks it. CPython keeps two of those
cursors in the same file: {@code range_iterator} counts in C {@code long}s and {@code
longrange_iterator} counts in Python integers, and {@code range_iter} picks between them by trying to
convert every number the walk needs — the three bounds, the count and the one step the cursor takes
after the last value. Both file the same six names in the same order, and their bodies disagree in
exactly two places a program can reach: the hint is a machine word or whatever width the count needs,
and {@code __setstate__} converts its index or demands an exact {@code int}.

Nothing here prints a cursor, a bound method or a {@code range_iterator} repr, all three of which carry
an address.

"""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


class Index:
    def __index__(self):
        return 2


class Raised:
    def __index__(self):
        raise ValueError("boom")


ROWS = ["__iter__", "__next__", "__length_hint__", "__reduce__", "__setstate__", "__doc__"]

WALKED = range(1, 9, 2)
FAR = range(0, 2 ** 70, 2 ** 69)
FAST = iter(WALKED)
WIDE = iter(FAR)

print("== the rows the two dictionaries file")
for kind in (FAST, WIDE):
    T = type(kind)
    print(T.__name__)
    print("  keys             ", said(lambda: list(vars(T)) == ROWS))
    print("  count, dir       ", said(lambda: (len(vars(T)), len(dir(T)))))
    print("  value same dir   ", said(lambda: sorted(dir(kind)) == sorted(dir(T))))
    print("  gained over obj  ", said(lambda: sorted(set(dir(T)) - set(dir(object)))))
    for name in ROWS[:-1]:
        row = vars(T)[name]
        print("    %-16s %-21s %-16s %r" % (
            name, type(row).__name__,
            getattr(getattr(row, "__objclass__", None), "__name__", "-"),
            getattr(row, "__doc__", "-")))
    print("    doc row is None  ", said(lambda: vars(T)["__doc__"] is None))
    print("    type doc         ", said(lambda: T.__doc__))
    print("    names            ", said(lambda: (T.__name__, T.__qualname__, T.__module__)))
    print("    order            ", said(lambda: [c.__name__ for c in T.__mro__]))
    print("    subclass of obj  ", said(lambda: T.__base__ is object))

print("== the tables the four method rows carry")
for kind in (FAST, WIDE):
    T = type(kind)
    print(T.__name__)
    for name in ROWS[:-1]:
        row = vars(T)[name]
        print("    %-16s sig=%r" % (name, getattr(row, "__text_signature__", "-")))

print("== which cursor a range hands out")
CASES = [range(0, 10), range(0), range(1), range(-5, 5), range(0, 10, -1), range(2 ** 63 - 1, 2 ** 63),
         range(0, 2 ** 63), range(-(2 ** 63), 0), range(0, 2 ** 64, 2), range(0, 2 ** 63, 2 ** 62),
         FAR, range(2 ** 70), range(0, 2 ** 100, 3), range(2 ** 63 - 2, 2 ** 63)]
for one in CASES:
    print("  %-30s %s" % (repr(one), said(lambda r=one: type(iter(r)).__name__)))

print("== walking one")
fresh = iter(WALKED)
print("  values           ", said(lambda: [fresh.__next__() for _ in range(5)]))
print("  then the end     ", said(lambda: fresh.__next__()))
print("  again            ", said(lambda: fresh.__next__()))
print("  next builtin     ", said(lambda: next(iter(WALKED))))
print("  next of finished ", said(lambda: next(fresh)))
print("  next with default", said(lambda: next(fresh, "gone")))
print("  iter is it       ", said(lambda: iter(fresh) is fresh))
print("  __iter__ is it   ", said(lambda: fresh.__iter__() is fresh))
print("  fresh list       ", said(lambda: list(iter(WALKED))))
print("  for loop         ", said(lambda: [v for v in iter(range(4))]))
print("  sum              ", said(lambda: sum(iter(range(1, 100)))))
print("  membership       ", said(lambda: 5 in iter(WALKED)))
print("  tuple of wide    ", said(lambda: tuple(iter(FAR))))
print("  empty walk       ", said(lambda: list(iter(range(0)))))
print("  len of a cursor  ", said(lambda: len(FAST)))
print("  hint is not len  ", said(lambda: hasattr(type(FAST), "__len__")))

print("== the hint, and what it counts")
counting = iter(range(5))
seen = []
for _ in range(6):
    seen.append(counting.__length_hint__())
    said(lambda: counting.__next__())
print("  as it walks      ", repr(seen))
print("  wide hint        ", said(lambda: iter(range(2 ** 70)).__length_hint__()))
print("  wide hint type   ", said(lambda: type(iter(range(2 ** 70)).__length_hint__()).__name__))
print("  empty hint       ", said(lambda: iter(range(0)).__length_hint__()))
print("  one hint         ", said(lambda: iter(range(1)).__length_hint__()))
print("  hint of exhausted", said(lambda: counting.__length_hint__()))
print("  hint with an arg ", said(lambda: FAST.__length_hint__(1)))
print("  hint named       ", said(lambda: FAST.__length_hint__(x=1)))

print("== the state a cursor reduces to")
print("  fresh            ", said(lambda: iter(WALKED).__reduce__()))
stepped = iter(WALKED)
said(lambda: stepped.__next__())
print("  after one step   ", said(lambda: stepped.__reduce__()))
ran_out = iter(WALKED)
for _ in range(10):
    try:
        ran_out.__next__()
    except StopIteration:
        break
print("  exhausted        ", said(lambda: ran_out.__reduce__()))
print("  kinds            ", said(lambda: [type(v).__name__ for v in iter(WALKED).__reduce__()]))
print("  inner range      ", said(lambda: type(iter(WALKED).__reduce__()[1][0]).__name__))
wide_stepped = iter(FAR)
print("  wide fresh       ", said(lambda: wide_stepped.__reduce__()))
said(lambda: wide_stepped.__next__())
print("  wide after one   ", said(lambda: wide_stepped.__reduce__()))
wide_out = iter(FAR)
wide_out.__next__()
wide_out.__next__()
print("  wide exhausted   ", said(lambda: wide_out.__reduce__()))
print("  reduce with arg  ", said(lambda: FAST.__reduce__(1)))
print("  reduce named     ", said(lambda: FAST.__reduce__(x=1)))

print("== jumping ahead with __setstate__")
jumping = iter(range(10))
print("  to three         ", said(lambda: jumping.__setstate__(3)))
print("  the walk then    ", said(lambda: [jumping.__next__() for _ in range(3)]))
print("  negative clips   ", said(lambda: (lambda it: (it.__setstate__(-4), it.__next__()))(iter(range(10)))))
print("  past end clips   ", said(lambda: (lambda it: (it.__setstate__(400), it.__length_hint__()))(iter(range(10)))))
print("  zero             ", said(lambda: (lambda it: (it.__setstate__(0), it.__next__()))(iter(range(10)))))
print("  a bool           ", said(lambda: (lambda it: (it.__setstate__(True), it.__next__()))(iter(range(10)))))
print("  widest that fits ", said(lambda: (lambda it: (it.__setstate__(2 ** 63 - 1),
                                                       it.__length_hint__()))(iter(range(10)))))
print("  an index         ", said(lambda: (lambda it: (it.__setstate__(Index()), it.__length_hint__()))
                                 (iter(range(10)))))
print("  index raises     ", said(lambda: iter(range(10)).__setstate__(Raised())))
print("  text             ", said(lambda: iter(range(10)).__setstate__("2")))
print("  real             ", said(lambda: iter(range(10)).__setstate__(2.0)))
print("  nothing          ", said(lambda: iter(range(10)).__setstate__()))
print("  two of them      ", said(lambda: iter(range(10)).__setstate__(1, 2)))
print("  named            ", said(lambda: iter(range(10)).__setstate__(state=1)))
print("  wide takes an int", said(lambda: (lambda it: (it.__setstate__(2), it.__next__()))(iter(FAR))))
print("  wide negative    ", said(lambda: (lambda it: (it.__setstate__(-4), it.__length_hint__()))(iter(FAR))))
print("  wide past end    ", said(lambda: (lambda it: (it.__setstate__(400), it.__length_hint__()))(iter(FAR))))
print("  wide huge        ", said(lambda: (lambda it: (it.__setstate__(2 ** 70), it.__length_hint__()))(iter(FAR))))
print("  wide a bool      ", said(lambda: iter(FAR).__setstate__(True)))
print("  wide an index    ", said(lambda: iter(FAR).__setstate__(Index())))
print("  wide nothing     ", said(lambda: iter(FAR).__setstate__(None)))
print("  wide text        ", said(lambda: iter(FAR).__setstate__("2")))
print("  fast huge        ", said(lambda: iter(range(10)).__setstate__(2 ** 70)))

print("== building one is refused")
for kind in (FAST, WIDE):
    T = type(kind)
    print(T.__name__)
    print("  call             ", said(lambda: T()))
    print("  call with an arg ", said(lambda: T(1)))
    print("  object new       ", said(lambda: object.__new__(T)))
    print("  no new row       ", said(lambda: "__new__" in vars(T)))
    print("  new inherited    ", said(lambda: (type(T.__new__).__name__, T.__new__ is object.__new__)))
    print("  vars of a value  ", said(lambda: vars(kind)))
    print("  dict of a value  ", said(lambda: kind.__dict__))
    print("  write a name     ", said(lambda: setattr(kind, "start", 1)))
    print("  delete a name    ", said(lambda: delattr(kind, "start")))
    print("  set in the dict  ", said(lambda: vars(T).__setitem__("extra", 1)))
    print("  weakref          ", said(lambda: hasattr(kind, "__weakref__")))
    print("  slots            ", said(lambda: T.__slots__))

print("== reading the rows the way the type answers them")
print("  hint unbound     ", said(lambda: vars(type(FAST))["__length_hint__"](FAST)))
print("  next unbound     ", said(lambda: vars(type(FAST))["__next__"](iter(WALKED))))
print("  wrong receiver   ", said(lambda: vars(type(FAST))["__next__"](3)))
print("  wrong hint type  ", said(lambda: vars(type(WIDE))["__length_hint__"]("x")))
print("  setstate unbound ", said(lambda: vars(type(FAST))["__setstate__"](iter(range(3)), 1)))
print("  next too few     ", said(lambda: vars(type(FAST))["__next__"]()))
print("  next too many    ", said(lambda: vars(type(FAST))["__next__"](FAST, 1)))
print("  named receiver   ", said(lambda: vars(type(FAST))["__iter__"](self=FAST)))
print("  get with no obj  ", said(lambda: vars(type(FAST))["__next__"].__get__(None, type(FAST))))
print("  bound kinds      ", said(lambda: [type(getattr(FAST, n)).__name__ for n in ROWS[:-1]]))
print("  bound names      ", said(lambda: [getattr(FAST, n).__name__ for n in
                                           ("__length_hint__", "__reduce__", "__setstate__")]))
print("  bound qualnames  ", said(lambda: [getattr(FAST, n).__qualname__ for n in
                                           ("__length_hint__", "__reduce__", "__setstate__")]))
print("  bound receivers  ", said(lambda: [getattr(FAST, n).__self__ is FAST for n in
                                           ("__iter__", "__next__", "__length_hint__")]))
print("  read twice       ", said(lambda: (FAST.__length_hint__ is FAST.__length_hint__,
                                           type(FAST).__length_hint__ is type(FAST).__length_hint__)))
print("  doc of bound     ", said(lambda: FAST.__setstate__.__doc__))
print("  missing name     ", said(lambda: FAST.send))
print("  of the class     ", said(lambda: type(FAST).__length_hint__.__objclass__ is type(FAST)))

print("== reversed hands the same pair out")
print("  row              ", said(lambda: WALKED.__reversed__().__reduce__()))
print("  values           ", said(lambda: list(WALKED.__reversed__())))
print("  builtin          ", said(lambda: [v for v in reversed(WALKED)]))
print("  kind             ", said(lambda: type(reversed(WALKED)).__name__))
print("  wide kind        ", said(lambda: type(reversed(FAR)).__name__))
print("  wide values      ", said(lambda: list(reversed(FAR))))
print("  empty kind       ", said(lambda: type(reversed(range(0))).__name__))
print("  step too wide    ", said(lambda: type(reversed(range(0, 10, -(2 ** 63)))).__name__))
print("  forward too wide ", said(lambda: type(iter(range(0, 10, -(2 ** 63)))).__name__))
print("  start too wide   ", said(lambda: type(reversed(range(-(2 ** 63), 0))).__name__))
print("  wide reversed step", said(lambda: list(reversed(range(2 ** 70, 0, -(2 ** 69))))))
print("  hint after one   ", said(lambda: (lambda it: (it.__next__(), it.__length_hint__()))
                                 (reversed(range(10)))))
print("  setstate reversed", said(lambda: (lambda it: (it.__setstate__(3), it.__next__()))
                                 (reversed(range(10)))))
print("  reversed twice   ", said(lambda: list(reversed(range(5)).__reduce__()[1][0])))
