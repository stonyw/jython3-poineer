"""The value an octet run is: ``int.from_bytes(bytes, byteorder='big', *, signed=False)``.

This is CPython's ``int_from_bytes_impl`` over ``_PyLong_FromByteArray``, and the first thing to pin is
the parameter shape: ``byteorder`` is positional-or-keyword and only ``signed`` is keyword-only, so a
third positional is one too many — ``from_bytes(b'\\xff', 'big', False)`` answers ``takes at most 2
positional arguments (3 given)`` while four of any kind answer the shorter ``takes at most 3 arguments
(4 given)``. The run is at position 1, which is what makes ``from_bytes(signed=True)`` a
missing-argument complaint rather than a default.

Then the byteorder is chosen — its *value* checked before the run is read at all, so
``from_bytes('ab', 'native')`` is about the order and ``from_bytes('ab', 1)`` is the converter naming
``int`` rather than text — and only then does ``PyObject_Bytes`` ask what the argument is. That order is
the whole of the difference between ``from_bytes("x", "native")`` and ``from_bytes(b"x", "native")``:
the first never looks at the ``str``.

``PyObject_Bytes`` is the part worth pinning item by item. A ``bytes`` comes out whole; anything else is
asked for ``__bytes__`` first, and its answer must *be* bytes or the complaint names whatever came back.
Failing that, a ``str`` is refused before being iterated — text has no octet meaning — and everything
else is drained one item at a time, so an out-of-range item stops the walk with the rest of the iterator
unconsumed and a generator left half-run. Each item is read as an index, so ``1.5`` and ``'a'`` are
``TypeError`` while ``True`` is a perfectly good ``1``; ``256`` and ``-1`` are ``ValueError`` about the
range, and the huge ones never overflow into a positive octet because the bound is asked before the
octet is written.

The last difference from every other method here is *who* the result belongs to. ``from_bytes`` is a
``METH_CLASS`` builtin, and CPython's ``classmethod_descriptor`` binds to the class the read ran on and
then builds the value through it — so ``bool.from_bytes(b'\\xff')`` is ``True``, ``bool.from_bytes(b'')``
is ``False``, ``True.from_bytes(b'\\x02')`` is a bool however far up the run goes, and reading the method
off ``bool`` hands out an object whose ``__self__`` and ``__qualname__`` name ``bool``, not ``int``.
"""

RUNS = (b"", b"\x00", b"\xff", b"\x01\x00", b"\xff\xff", b"\x80", b"\x7f", b"\x01\x02\x03\x04",
        b"ab", bytes(range(8)))
ITEMS = ([1, 0], (1, 0), [255], [0], [], [1.5], ["a"], [256], [-1], [None], [[1]], [True],
         [2**70], [-(2**70)], {0, 1}, frozenset({0, 1}), {"a": 1, "b": 0}, range(1, 4),
         bytes(range(3)), b"ab", "ab", 3, 3.5, None, True)


class Rev:
    def __iter__(self):
        return iter([1, 2])


class Empty:
    def __iter__(self):
        return iter([])


class Plain:
    pass


class GoodBytes:
    def __bytes__(self):
        return b"\x01\x00"


class BadBytes:
    def __bytes__(self):
        return "not bytes"


class LoudIter:
    def __iter__(self):
        raise KeyError("no")


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


def items():
    yield 1
    yield 256
    yield 0


def there(value, length, order, signed):
    run = value.to_bytes(length, order, signed=signed)
    return run, int.from_bytes(run, order, signed=signed)


print("== the run comes first and the order second ==")
for run in RUNS:
    for signed in (False, True):
        print(repr(run), signed, "big ->", describe("f", lambda: int.from_bytes(run, "big", signed=signed)),
              "| little ->", describe("f", lambda: int.from_bytes(run, "little", signed=signed)),
              "| default ->", describe("f", lambda: int.from_bytes(run, signed=signed)))

print("== three positional arguments are one too many ==")
for run in (b"", b"\xff", b"\x01\x00"):
    for signed in (False, True):
        for order in ("big", "little"):
            print(repr(run), order, signed, "->", describe("f", lambda: int.from_bytes(run, order, signed)))

print("== every kind of argument PyObject_Bytes will accept ==")
for value in ITEMS:
    print(type(value).__name__, "->", describe("f", lambda: int.from_bytes(value)))
print("Rev ->", describe("f", lambda: int.from_bytes(Rev())))
print("Empty ->", describe("f", lambda: int.from_bytes(Empty())))
print("Plain ->", describe("f", lambda: int.from_bytes(Plain())))
print("GoodBytes ->", describe("f", lambda: int.from_bytes(GoodBytes())))
print("BadBytes ->", describe("f", lambda: int.from_bytes(BadBytes())))
print("LoudIter ->", describe("f", lambda: int.from_bytes(LoudIter())))
print("list iterator ->", describe("f", lambda: int.from_bytes(iter([1, 0]))))
print("generator ->", describe("f", lambda: int.from_bytes(items() for _ in [0])))

print("== a bad item stops the walk with the rest of it unconsumed ==")
run = items()
print("first ->", describe("f", lambda: int.from_bytes(run)))
print("left ->", describe("f", lambda: next(run)))
print("exhausted ->", describe("f", lambda: int.from_bytes(run)))

print("== the order is chosen before the run is read ==")
print("str, bad order ->", describe("f", lambda: int.from_bytes("ab", "native")))
print("str, ok order ->", describe("f", lambda: int.from_bytes("ab", "big")))
print("bad order, bad run ->", describe("f", lambda: int.from_bytes(3, "little")))
for order in ("big", "little", "native", "little ", "BIG", "", None, 1, b"big", ["big"], True, Plain):
    print(repr(order)[:10], "->", describe("f", lambda: int.from_bytes(b"\xff", order)))

print("== signed is keyword-only and only ever asked for truth ==")
for flag in (None, True, False, 0, 1, 0.0, 1.5, "", "x", [], [0], (), {0: 0}, object, len, print):
    print(repr(flag)[:12], "->", describe("f", lambda: int.from_bytes(b"\xff", signed=flag))[:40])
print("positionally ->", describe("f", lambda: int.from_bytes(b"\xff", "big", True)))

print("== arity, and the names a wrong one is checked against ==")
print("none ->", describe("f", lambda: int.from_bytes()))
print("four ->", describe("f", lambda: int.from_bytes(b"\xff", "big", True, False)))
print("named run ->", describe("f", lambda: int.from_bytes(bytes=b"\xff")))
print("two names ->", describe("f", lambda: int.from_bytes(byteorder="little", signed=True)))
print("empty keywords ->", describe("f", lambda: int.from_bytes(**{})))
print("wrong name ->", describe("f", lambda: int.from_bytes(b"\xff", octets="big")))
print("near miss ->", describe("f", lambda: int.from_bytes(b"\xff", byte_order="big")))
print("near miss two ->", describe("f", lambda: int.from_bytes(b"\xff", sighned=True)))
print("run twice ->", describe("f", lambda: int.from_bytes(b"\xff", bytes=b"\x00")))
print("order twice ->", describe("f", lambda: int.from_bytes(b"\xff", "big", byteorder="little")))

print("== the reading type builds the value ==")
print("int ->", describe("f", lambda: int.from_bytes(b"\xff")), "|", describe("t", lambda: type(int.from_bytes(b"\xff")).__name__))
print("bool ff ->", describe("f", lambda: bool.from_bytes(b"\xff")))
print("bool 00 ->", describe("f", lambda: bool.from_bytes(b"\x00")))
print("bool 02 ->", describe("f", lambda: bool.from_bytes(b"\x02")))
print("bool empty ->", describe("f", lambda: bool.from_bytes(b"")))
print("bool type ->", describe("t", lambda: type(bool.from_bytes(b"\x01")).__name__))
print("from a value ->", describe("f", lambda: True.from_bytes(b"\xff")),
      "|", describe("f", lambda: (7).from_bytes(b"\xff")))
print("self ->", describe("f", lambda: int.from_bytes.__self__ is int), "|",
      describe("f", lambda: bool.from_bytes.__self__ is bool), "|",
      describe("f", lambda: b"".fromhex.__self__ is bytes))
print("names ->", int.from_bytes.__name__, int.from_bytes.__qualname__,
      "|", bool.from_bytes.__qualname__)
print("read twice ->", describe("f", lambda: int.from_bytes is int.from_bytes), "|",
      describe("f", lambda: bool.from_bytes is int.from_bytes))
print("not a descriptor ->", hasattr(int.from_bytes, "__get__"))

print("== the round trip the two halves make ==")
for value in (0, 1, 255, 256, -1, 127, 128, -128, -129, 2**16 - 1, -(2**16), 2**64):
    for length in (0, 1, 2, 3, 8, 9):
        for signed in (False, True):
            for order in ("big", "little"):
                print(value, length, order, signed, "->", describe("f", lambda: there(value, length, order, signed)))
