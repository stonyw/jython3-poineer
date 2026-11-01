"""The six rows each of the six cursors the sequence types files for itself.

Six types, one dictionary written six times over: {@code list_iterator} and {@code
list_reverseiterator} come out of {@code listobject.c}, {@code tuple_iterator}, {@code bytes_iterator},
{@code str_iterator} and {@code str_ascii_iterator} out of the other three sequence files, and every one
of the six {@code tp_methods} lists is the same three {@code PyMethodDef} rows with the same three
sentences. Beside them sit the copies of {@code tp_iter} and {@code tp_iternext}, so each book has six
names in one order, and four of the six names are not ones {@code object} already files.

What the six disagree about is not the book but the walk. Only the list cursors read their size while
they are being read, so only a list can change under a cursor; the other five hold a reference to
something that cannot. Each cursor answers for itself in its own type name, and a spent one reduces to
an empty value — a new empty {@code list} for the two list cursors, and for the four that were never
going to change, the empty value of the sequence type it was made from.

Nothing here prints a cursor: both halves of their reprs carry an address, and the builtin the pickling
payload names is a type in CPython for the backwards cursor and a function here, so every payload is
written with that first item replaced by the answer to "is it the same callable".

"""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


def reduced(it, restart):
    payload = it.__reduce__()
    return (payload[0] is restart,) + tuple(payload[1:])


def jump(source, state):
    it = iter(source)
    it.__setstate__(state)
    return (it.__length_hint__(), list(it))


def jump_back(source, state):
    it = reversed(source)
    it.__setstate__(state)
    return (it.__length_hint__(), list(it))


class Index:
    def __index__(self):
        return 1


class Raised:
    def __index__(self):
        raise ValueError("boom")


ROWS = ["__iter__", "__next__", "__length_hint__", "__reduce__", "__setstate__", "__doc__"]
SRC = [1, 2, 3]

print("== which cursor a value hands out")
HANDS = ["", "a", "ab", "\u00e9", "\u00e9a", "\U0001f600", "aaa",
         b"", b"a", b"\xff", (), (1,), (None,), [], [1], [None]]
for one in HANDS:
    print("  ", repr(one), "->", said(lambda v=one: type(iter(v)).__name__))
print("  reversed([])     ", said(lambda: type(reversed([])).__name__))
print("  list.__reversed__", said(lambda: type([].__reversed__()).__name__))

print("== the six rows each book files")
for kind in (iter([]), reversed([]), iter(()), iter(b""), iter("\u00e9"), iter("a")):
    T = type(kind)
    print(T.__name__)
    print("  keys             ", said(lambda: list(vars(T)) == ROWS))
    print("  count, dir       ", said(lambda: (len(vars(T)), len(dir(T)))))
    print("  gained over obj  ", said(lambda: sorted(set(dir(T)) - set(dir(object)))))
    print("  value same dir   ", said(lambda: sorted(dir(kind)) == sorted(dir(T))))
    print("  order            ", said(lambda: [c.__name__ for c in T.__mro__]))
    print("  subclass of obj  ", said(lambda: T.__base__ is object))
    print("  names            ", said(lambda: (T.__name__, T.__qualname__, T.__module__)))
    print("  type doc         ", said(lambda: T.__doc__))
    for name in ROWS[:-1]:
        row = vars(T)[name]
        print("    %-17s %-20s %r" % (name, type(row).__name__, getattr(row, "__doc__", "<none>")))
    print("    doc row          %-20s %r" % (type(vars(T)["__doc__"]).__name__,
                                             vars(T)["__doc__"]))
    print("    doc row is None ", said(lambda: vars(T)["__doc__"] is None))

print("== the tables the six rows carry")
for kind in (iter([]), iter(()), iter("\u00e9")):
    T = type(kind)
    print(T.__name__)
    for name in ROWS:
        row = vars(T)[name]
        print("    %-17s %-20s sig=%r" % (name, type(row).__name__,
                                         getattr(row, "__text_signature__", "-")))

print("== walking one")
one = iter(SRC)
print("  three steps      ", said(lambda: [one.__next__(), one.__next__(), one.__next__()]))
print("  the end          ", said(lambda: one.__next__()))
print("  again            ", said(lambda: one.__next__()))
print("  next builtin     ", said(lambda: next(iter(SRC))))
print("  next of spent    ", said(lambda: next(one)))
print("  next with default", said(lambda: next(one, "gone")))
print("  iter is it       ", said(lambda: iter(one) is one))
print("  __iter__ is it   ", said(lambda: one.__iter__() is one))
print("  fresh list        ", said(lambda: list(iter(SRC))))
print("  for loop         ", said(lambda: [v for v in iter(SRC)]))
print("  sum              ", said(lambda: sum(iter(SRC))))
print("  membership       ", said(lambda: 2 in iter(SRC)))
print("  tuple of one     ", said(lambda: tuple(iter(SRC))))
print("  walked twice     ", said(lambda: (lambda it: [list(it), list(it)])(iter(SRC))))
print("  len of a cursor  ", said(lambda: len(one)))
print("  vars of a cursor ", said(lambda: vars(one)))
print("  dict of a cursor ", said(lambda: one.__dict__))
print("  repr shape       ", said(lambda: repr(iter(SRC)).split(" at ")[0]))
print("  walking a tuple  ", said(lambda: list(iter((1, "b", None)))))
print("  walking bytes    ", said(lambda: list(iter(b"ab"))))
print("  walking text     ", said(lambda: list(iter("abc"))))
print("  walking wide text", said(lambda: [hex(ord(c)) for c in iter("\u00e9\U0001f600")]))
print("  walking back     ", said(lambda: list(reversed(SRC))))
print("  empty walk       ", said(lambda: list(iter([]))))

print("== the hint, and what it counts")
for source in ([1, 2, 3], (1, 2, 3), "ab", b"ab"):
    walked = iter(source)
    seen = [walked.__length_hint__()]
    for _ in range(4):
        said(lambda: walked.__next__())
        seen.append(walked.__length_hint__())
    print("  %-17s %r" % (type(walked).__name__, seen))
backwards = reversed(SRC)
counted = [backwards.__length_hint__()]
for _ in range(4):
    said(lambda: backwards.__next__())
    counted.append(backwards.__length_hint__())
print("  %-17s %r" % (type(backwards).__name__, counted))
print("  a hint is an int ", said(lambda: type(iter(SRC).__length_hint__()).__name__))
print("  hint of empty    ", said(lambda: iter([]).__length_hint__()))
print("  hint of spent    ", said(lambda: one.__length_hint__()))
print("  hint with an arg ", said(lambda: iter(SRC).__length_hint__(1)))
print("  hint named       ", said(lambda: iter(SRC).__length_hint__(x=1)))

print("== the state a cursor reduces to")
for source in ([1, 2, 3], (1, 2, 3), "ab", b"ab"):
    it = iter(source)
    print("  %-17s %-6s %s" % (type(it).__name__, "fresh", said(lambda: reduced(it, iter))))
    said(lambda: it.__next__())
    print("  %-17s %-6s %s" % (type(it).__name__, "one", said(lambda: reduced(it, iter))))
    said(lambda: list(it))
    print("  %-17s %-6s %s" % (type(it).__name__, "done", said(lambda: reduced(it, iter))))
    print("  %-17s %-6s %s" % (type(it).__name__, "again", said(lambda: reduced(it, iter))))
it = reversed(SRC)
print("  reverse fresh    ", said(lambda: reduced(it, reversed)))
said(lambda: it.__next__())
print("  reverse one      ", said(lambda: reduced(it, reversed)))
said(lambda: list(it))
print("  reverse done     ", said(lambda: reduced(it, reversed)))
print("  kinds            ", said(lambda: [type(v).__name__ for v in iter(SRC).__reduce__()[1:]]))
print("  restart is iter  ", said(lambda: iter(SRC).__reduce__()[0] is iter))
print("  restart is rev   ", said(lambda: reversed(SRC).__reduce__()[0] is reversed))
print("  reduce with an arg", said(lambda: iter(SRC).__reduce__(1)))
print("  reduce named     ", said(lambda: iter(SRC).__reduce__(x=1)))

print("== jumping ahead with __setstate__")
for source in ([1, 2, 3], (1, 2, 3), "ab", b"ab"):
    for state in (0, 1, 2, 3, 4, -1, -2, True, 2.5, None, "x", [1]):
        print("  %-17s %-5s %s" % (type(iter(source)).__name__, repr(state),
                                   said(lambda s=source, v=state: jump(s, v))))
print("  list at the size ", said(lambda: (lambda it: (it.__setstate__(3), it.__length_hint__(),
                                                       reduced(it, iter)))(iter(SRC))))
print("  tuple at the size", said(lambda: (lambda it: (it.__setstate__(3), it.__length_hint__(),
                                                       reduced(it, iter)))(iter((1, 2, 3)))))
print("  widest that fits ", said(lambda: jump(SRC, 2 ** 63 - 1)))
print("  one too wide     ", said(lambda: jump(SRC, 2 ** 63)))
print("  much too wide    ", said(lambda: jump(SRC, 2 ** 70)))
print("  wide and under   ", said(lambda: jump(SRC, -(2 ** 70))))
print("  a bool           ", said(lambda: jump(SRC, True)))
print("  an index         ", said(lambda: jump(SRC, Index())))
print("  index raises     ", said(lambda: jump(SRC, Raised())))
print("  spent ignores    ", said(lambda: (lambda it: (list(it), it.__setstate__(0),
                                                       it.__length_hint__(), list(it)))(iter(SRC))))
print("  spent list back  ", said(lambda: (lambda it: (list(it), it.__setstate__(1),
                                                       it.__length_hint__(), list(it)))(iter(SRC))))
print("  nothing          ", said(lambda: iter(SRC).__setstate__()))
print("  two of them      ", said(lambda: iter(SRC).__setstate__(1, 2)))
print("  named            ", said(lambda: iter(SRC).__setstate__(state=1)))

print("== the window a backwards cursor clips into")
for state in (-2, -1, 0, 1, 2, 3, 5, True, 2.5):
    print("  %-5s %s" % (repr(state), said(lambda v=state: jump_back(SRC, v))))
print("  spent backwards  ", said(lambda: (lambda it: (list(it), it.__setstate__(2),
                                                       it.__length_hint__(), list(it)))
                                    (reversed(SRC))))

print("== the list a cursor is walking can change under it")
growing = [1, 2, 3]
walked = iter(growing)
print("  first            ", said(lambda: next(walked)))
growing.append(4)
print("  after append     ", said(lambda: list(walked)))
shrinking = [1, 2, 3, 4]
second = iter(shrinking)
said(lambda: next(second))
del shrinking[2:]
print("  after shrink     ", said(lambda: (second.__length_hint__(), list(second),
                                           second.__length_hint__(), reduced(second, iter))))
print("  the source is it ", said(lambda: (lambda it: it.__reduce__()[1][0] is growing)(iter(growing))))
for one in ([1, 2], (1, 2), "ab", b"ab"):
    print("  keeps what it got", type(one).__name__,
          said(lambda v=one: iter(v).__reduce__()[1][0] is v))

print("== building one is refused")
for kind in (iter([]), reversed([]), iter(()), iter(b""), iter("\u00e9"), iter("a")):
    T = type(kind)
    print(T.__name__)
    print("  call             ", said(lambda: T()))
    print("  call with an arg ", said(lambda: T(1)))
    print("  call named       ", said(lambda: T(x=1)))
    print("  object new       ", said(lambda: object.__new__(T)))
    print("  no new row       ", said(lambda: "__new__" in vars(T)))
    print("  new inherited    ", said(lambda: (type(T.__new__).__name__, T.__new__ is object.__new__)))
    print("  write a name     ", said(lambda: setattr(kind, "extra", 1)))
    print("  delete a name    ", said(lambda: delattr(kind, "extra")))
    print("  weakref          ", said(lambda: hasattr(kind, "__weakref__")))
    print("  slots            ", said(lambda: T.__slots__))
    print("  set in the dict  ", said(lambda: vars(T).__setitem__("extra", 1)))
    print("  dict is the type ", said(lambda: kind.__class__ is T))

print("== reading the rows the way the type answers them")
KIND = type(iter(SRC))
print("  hint unbound     ", said(lambda: vars(KIND)["__length_hint__"](iter(SRC))))
print("  next unbound     ", said(lambda: vars(KIND)["__next__"](iter(SRC))))
print("  wrong receiver   ", said(lambda: vars(KIND)["__next__"](3)))
print("  wrong hint recv  ", said(lambda: vars(KIND)["__length_hint__"]("x")))
print("  wrong reduce recv", said(lambda: vars(KIND)["__reduce__"](iter(()))))
print("  wrong setstate r ", said(lambda: vars(KIND)["__setstate__"](iter(()), 1)))
print("  setstate unbound ", said(lambda: vars(KIND)["__setstate__"](iter([1, 2, 3]), 1)))
print("  too few          ", said(lambda: vars(KIND)["__next__"]()))
print("  too many         ", said(lambda: vars(KIND)["__next__"](iter(SRC), 1)))
print("  named receiver   ", said(lambda: vars(KIND)["__iter__"](self=iter(SRC))))
print("  get with no obj  ", said(lambda: vars(KIND)["__next__"].__get__(None, KIND)))
print("  bound kinds      ", said(lambda: [type(getattr(iter(SRC), n)).__name__ for n in ROWS[:-1]]))
print("  bound names      ", said(lambda: [getattr(iter(SRC), n).__name__ for n in
                                           ("__length_hint__", "__reduce__", "__setstate__")]))
print("  bound qualnames  ", said(lambda: [getattr(iter(SRC), n).__qualname__ for n in
                                           ("__length_hint__", "__reduce__", "__setstate__")]))
print("  bound receivers  ", said(lambda: (lambda it: [getattr(it, n).__self__ is it for n in
                                                       ("__iter__", "__next__",
                                                        "__length_hint__")])(iter(SRC))))
print("  read twice       ", said(lambda: (lambda it: (it.__length_hint__ is it.__length_hint__,
                                                       it.__length_hint__() == it.__length_hint__()))
                                  (iter(SRC))))
print("  doc of bound     ", said(lambda: iter(SRC).__setstate__.__doc__))
print("  objclass         ", said(lambda: KIND.__length_hint__.__objclass__ is KIND))
print("  missing name     ", said(lambda: iter(SRC).send))
