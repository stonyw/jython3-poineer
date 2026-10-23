"""The octet run a value is: ``int.to_bytes(length=1, byteorder='big', *, signed=False)``.

This is CPython's ``int_to_bytes_impl`` over ``_PyLong_AsByteArray``, and three of its details are the
ones a program trips on. The width defaults to **one octet**, not to whatever the value needs, so
``(255).to_bytes()`` answers ``b'\\xff'`` while ``(256).to_bytes()`` overflows; the octets past the
value's own run are sign octets, which is what makes ``(-1).to_bytes(9)`` nine ``0xff``; and the array
is allocated before the value is measured against it, so ``(1).to_bytes(2**62)`` runs out of memory —
with no sentence at all — rather than reporting an overflow.

The order of the refusals is the other half of the shape. Everything ``_PyArg_UnpackKeywords`` can
complain about comes first — count, the positional window, a required name missing, a name given twice,
an unexpected keyword — then the clinic converters run in parameter order: the width is read as an
index, the byteorder as text. So ``to_bytes("x", "native")`` is about the width and not about the
order, and ``to_bytes(1, "native", True)`` is about the count. Only then does the implementation speak,
and in its own order: the byteorder's *value* first, so ``(-1).to_bytes(-1, "native")`` complains about
the order; then that the width is not negative, so ``(-1).to_bytes(-1)`` is about the width and not
about the sign; then the array is allocated; and only once that is here is the value asked whether it
is negative without leave — ``can't convert negative int to unsigned`` — which it is asked *before*
whether the run fills, so ``(-1).to_bytes(0)`` overflows about the sign rather than about the width.

``signed`` is keyword-only and read for truth, so ``signed=None`` is unsigned while ``signed=[]``,
``signed=0.0`` and ``signed=''`` are too, and ``signed=1``, ``signed='x'`` and ``signed=[0]`` are not.
``byteorder`` is compared against two spellings and nothing else: ``'big'`` and ``'little'`` are the
only names that answer, so ``'native'`` fails here even though the docs mention it, while ``1`` and
``None`` fail a step earlier because the converter wants text — and that complaint names ``None`` for
``None``, not ``NoneType``.
"""

VALUES = (0, 1, 255, 256, -1, 127, 128, -128, -129, 2**16 - 1, -(2**16), 2**63, -(2**63), 2**64)
LENGTHS = (0, 1, 2, 3, 4, 8, 9)
ORDERS = ("big", "little", "native", "BIG", "", None, 1, b"big", ["big"], True, "x" * 3)


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


def back(value, length, order, signed):
    run = value.to_bytes(length, order, signed=signed)
    return run, int.from_bytes(run, order, signed=signed)


print("== the width defaults to one octet ==")
for value in VALUES:
    print(describe(str(value), lambda: value.to_bytes()))

print("== sign octets fill what the value leaves ==")
for value in VALUES:
    for length in LENGTHS:
        print(value, length, "unsigned ->", describe("f", lambda: value.to_bytes(length)),
              "| signed ->", describe("f", lambda: value.to_bytes(length, signed=True)))

print("== both orders of every run that fits ==")
for value in VALUES:
    for length in LENGTHS:
        for signed in (False, True):
            print(value, length, signed, describe("f", lambda: value.to_bytes(length, "little", signed=signed)),
                  describe("f", lambda: value.to_bytes(length, "big", signed=signed)))

print("== the parser answers first, the implementation in its own order ==")
for order in ORDERS:
    print(repr(order)[:10], "->", describe("f", lambda: (7).to_bytes(-1, order)),
          "|", describe("f", lambda: (7).to_bytes(2, order)))
print("width text, order bad ->", describe("f", lambda: (7).to_bytes("x", "native")))
print("width float, order bad ->", describe("f", lambda: (7).to_bytes(2.5, "native")))
print("count beats order ->", describe("f", lambda: (7).to_bytes(1, "native", True)))
print("keyword beats order ->", describe("f", lambda: (7).to_bytes(1, "native", x=1)))
print("twice beats order ->", describe("f", lambda: (7).to_bytes(1, "native", byteorder="little")))
print("missing beats order ->", describe("f", lambda: (7).to_bytes(byteorder="native")))
print("non-negative after the name ->", describe("f", lambda: (7).to_bytes(-1, "native")))
print("width bounded before the sign ->", describe("f", lambda: (-1).to_bytes(-1)))
print("order before the bound ->", describe("f", lambda: (-1).to_bytes(-1, "native")))
print("allocated before the sign ->", describe("f", lambda: (-1).to_bytes(2**62)))
print("sign before the fit ->", describe("f", lambda: (-1).to_bytes(0)))
print("unsigned negative ->", describe("f", lambda: (-1).to_bytes(1)))
print("signed negative ->", describe("f", lambda: (-1).to_bytes(1, signed=True)))
print("one short ->", describe("f", lambda: (256).to_bytes(1)), "|", describe("f", lambda: (128).to_bytes(1, signed=True)))
print("exactly one ->", describe("f", lambda: (255).to_bytes(1)), "|", describe("f", lambda: (127).to_bytes(1, signed=True)))
print("zero width, zero value ->", describe("f", lambda: (0).to_bytes(0)),
      "|", describe("f", lambda: (0).to_bytes(0, signed=True)),
      "|", describe("f", lambda: (-1).to_bytes(0, signed=True)))
print("no octets for a value that needs them ->", describe("f", lambda: (1).to_bytes(0)),
      "|", describe("f", lambda: (256).to_bytes(1)))

print("== signed is keyword-only and only ever asked for truth ==")
for flag in (None, True, False, 0, 1, 0.0, 1.5, "", "x", [], [0], (), {0: 0}, object, len, print):
    print(repr(flag)[:12], "->", describe("f", lambda: (-1).to_bytes(2, signed=flag))[:44])
print("positionally ->", describe("f", lambda: (-1).to_bytes(2, "big", True)))
print("by keyword alone ->", describe("f", lambda: (-1).to_bytes(2, signed=True)))

print("== the width is read as an index ==")
for width in (2, 3.5, "2", None, True, 2**70, -(2**70)):
    print(repr(width)[:10], "->", describe("f", lambda: (7).to_bytes(width)))
print("huge width ->", describe("f", lambda: (7).to_bytes(2**62)))

print("== arity, and the names a wrong one is checked against ==")
print("none ->", describe("f", lambda: (7).to_bytes()))
print("three ->", describe("f", lambda: (7).to_bytes(1, "big", True)))
print("four ->", describe("f", lambda: (7).to_bytes(1, "big", True, False)))
print("length by name ->", describe("f", lambda: (7).to_bytes(length=2))[:44])
print("both names ->", describe("f", lambda: (7).to_bytes(byteorder="little", signed=True)))
print("reversed ->", describe("f", lambda: (7).to_bytes(signed=True, byteorder="little")))
print("empty keywords ->", describe("f", lambda: (7).to_bytes(**{})))
print("wrong name ->", describe("f", lambda: (7).to_bytes(1, bytes="big")))
print("near miss ->", describe("f", lambda: (7).to_bytes(1, byte_order="big")))
print("near miss two ->", describe("f", lambda: (7).to_bytes(1, sighned=True)))
print("name and position ->", describe("f", lambda: (7).to_bytes(1, "big", byteorder="little")))
print("width twice ->", describe("f", lambda: (7).to_bytes(1, length=2)))
print("unbound ->", describe("f", lambda: int.to_bytes(7, 2)), "|", describe("f", lambda: int.to_bytes(7)))
print("unbound named ->", describe("f", lambda: int.to_bytes(self=7, length=2)))
print("unbound, wrong self ->", describe("f", lambda: int.to_bytes("x", 2)))

print("== a bool writes itself as one octet ==")
print("True ->", describe("f", lambda: True.to_bytes()), "| False ->", describe("f", lambda: False.to_bytes()))
print("True, two ->", describe("f", lambda: True.to_bytes(2, "little")))
print("False, signed ->", describe("f", lambda: False.to_bytes(2, signed=True)))
print("read off the type ->", describe("f", lambda: int.to_bytes(True, 2, "little")))

print("== what reading the method hands out ==")
print("bound ->", describe("f", lambda: (7).to_bytes(2)))
print("descriptor is itself ->", describe("f", lambda: int.to_bytes is int.to_bytes))
print("bound is not ->", describe("f", lambda: (7).to_bytes is (7).to_bytes))
print("names ->", int.to_bytes.__name__, int.to_bytes.__qualname__)

print("== the round trip back through from_bytes ==")
for value in VALUES:
    for length in LENGTHS:
        for signed in (False, True):
            for order in ("big", "little"):
                print(value, length, order, signed, "->", describe("f", lambda: back(value, length, order, signed)))
