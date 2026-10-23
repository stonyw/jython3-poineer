"""The five ways a bytes pads itself, and the two numbers each of them reads.

None of these truncates. A value already as wide as it was asked to be is the value itself — the same
object, not a copy of it — which is what makes ``b'ab'.center(2) is b'ab'`` true and ``center(3)``
false. The width is a machine word, so any number that fits one is taken for a width and only a number
wider than the word is refused as a width; the fill byte is a one-octet bytes, and its two refusals
differ by a colon depending on whether the argument was the wrong type or merely the wrong length.

``center`` is the only one that splits a margin, and it does not split an odd one by rounding: the extra
octet goes left only when the width is odd as well, which is CPython's ``marg / 2 + (marg & width & 1)``
and is why ``b'ab'.center(4)`` is ``b' ab '`` while ``center(5)`` is ``b'  ab '``. ``zfill`` pads with
zeros and then moves a leading sign in front of them, so ``b'-1'.zfill(6)`` is ``b'-00001'`` rather
than ``b'0000-1'``.

``expandtabs`` is the odd one of the five: its number is a C ``int`` rather than a word, so it overflows
a hundred powers of two before the widths do; only ``\\n`` and ``\\r`` restart the column, which is
fewer breaks than ``splitlines`` reads and fewer spaces than ``strip`` removes; and — unlike ``str``,
which hands back the same object when there is no tab to expand — a bytes is copied either way.

A width too wide to allocate is refused in two ways. ``bytes`` has a check of its own in the allocator,
so the very widest answer is ``OverflowError: byte string is too large`` and one octet narrower answers
an empty ``MemoryError``; ``str`` has no such check, because its two margins always add back up to the
width it was given, so the widest paddings of ``str`` only ever fail as memory does. The band in
between — a width CPython's ``malloc`` can satisfy but a Java array cannot even be numbered to — is not
pinned here, because which side of it a machine is on is not a property of the language."""

STRINGS = (b"ab", "ab")
FILLS = (b"-", b"", b"ab", b"ab" * 40, 45, 256, -1, 1, "x", None, 2.0, [b"-"])
STR_FILLS = ("-", "", "xy", "abc", None, 45, [], True, "\u00e9", "\U0001f600")
OCTETS = (b"", b" ", b"-", b"ab", b"\x00", b"\xff")
VALUES = (b"1", b"-1", b"+1", b"", b"-", b"+", b"0012", b"ab", b"-1234567890", b"--1", b" 1",
          b"\t1", b"\x00", b"0")
TABS = (b"a\tb", b"\t", b"a\t", b"\ta", b"aaa\tb", b"a \tb", b"\n\t", b"\r\t", b"\r\n\t", b"\x0b\t",
        b"\x0c\t", b"a\nbb\tc", b"a\rb\tc", b"ab\t", b"", b"\x85\t", b"\xff\t", b"\t\t")
STR_TABS = ("a\tb", "\t\ta", "a\nbb\tc", "a\rb\tc", "a\x0bb\tc", "\x85\t", "")
WORD_MAX = 2 ** 63 - 1


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


print("== the paddings over a width grid, on both sides ==")
for source in STRINGS:
    for width in range(-2, 9):
        print(type(source).__name__, repr(source), "center", width, "->",
              describe("f", lambda: source.center(width)),
              "| ljust", describe("f", lambda: source.ljust(width)),
              "| rjust", describe("f", lambda: source.rjust(width)),
              "| zfill", describe("f", lambda: source.zfill(width)))

print("== center's odd margin, by both widths ==")
for source, letter in zip(STRINGS, (b"x", "x")):
    for length in (0, 1, 2, 3):
        short = letter * length
        for width in range(length, length + 7):
            print(type(source).__name__, repr(short), "center", width, "->",
                  describe("f", lambda: short.center(width)))

print("== a padding that changed nothing is the value itself ==")
for source in STRINGS:
    for width in (-1, 0, 1, 2, 3):
        print(type(source).__name__, "center", width, "is self:", source.center(width) is source,
              "| ljust:", source.ljust(width) is source,
              "| rjust:", source.rjust(width) is source,
              "| zfill:", source.zfill(width) is source)
    space = source[:1]
    print(type(source).__name__, "padded with a space it already has is self:",
          source.center(2, space) is source, "| padded wider is not:", source.center(4, space) is source)
    empty = type(source)()
    print(type(source).__name__, "empty center 0 is empty:", empty.center(0) is empty,
          "| center 1 is empty:", empty.center(1) is empty, "| repr:", repr(empty.center(3)))

print("== the fill, refused by type or by length ==")
for name in ("center", "ljust", "rjust"):
    for fill in FILLS:
        print("bytes", name, "fill", type(fill).__name__, "->",
              describe("f", lambda: getattr(b"ab", name)(5, fill)))
    for fill in STR_FILLS:
        print("str", name, "fill", type(fill).__name__, "->",
              describe("f", lambda: getattr("ab", name)(5, fill)))

print("== a fill of one octet, on both sides of the value ==")
for octet in OCTETS:
    if len(octet) != 1:
        continue
    for width in (3, 4, 5, 6):
        print(repr(octet), "b'ab'.center", width, "->", describe("f", lambda: b"ab".center(width, octet)),
              "| b'xyz'.ljust", describe("f", lambda: b"xyz".ljust(width, octet)),
              "| b'xyz'.rjust", describe("f", lambda: b"xyz".rjust(width, octet)))

print("== zfill and its sign ==")
for value in VALUES:
    print(type(value).__name__, repr(value), "zfill(6) ->", describe("f", lambda: value.zfill(6)),
          "| zfill(2) ->", describe("f", lambda: value.zfill(2)),
          "| zfill(0) ->", describe("f", lambda: value.zfill(0)),
          "| is self at 2:", value.zfill(2) is value)
for value in ("1", "-1", "+1", "", "-", "+", "0012", "ab", "--1", "\U0001f600-1"):
    print("str", repr(value), "zfill(6) ->", describe("f", lambda: value.zfill(6)),
          "| zfill(2) ->", describe("f", lambda: value.zfill(2)),
          "| is self at 2:", value.zfill(2) is value)

print("== expandtabs, by tabsize and by what resets the column ==")
for value in TABS:
    for tabsize in (0, 1, 2, 8, -1, -10):
        print(repr(value), "tabs", tabsize, "->", describe("f", lambda: value.expandtabs(tabsize)))
for value in STR_TABS:
    for tabsize in (0, 1, 8, -1):
        print("str", repr(value), "tabs", tabsize, "->", describe("f", lambda: value.expandtabs(tabsize)))
print("default tabsize ->", repr(b"a\tb".expandtabs()), repr("a\tb".expandtabs()))

print("== expandtabs copies a bytes that had nothing to expand ==")
for source in STRINGS:
    empty = type(source)()
    print(type(source).__name__, "'ab'.expandtabs() is self:", source.expandtabs() is source,
          "| empty is empty:", empty.expandtabs() is empty, "| repr:", repr(source.expandtabs()))

print("== a tabsize is a C int, so it is narrower than a width ==")
for tabsize in (2 ** 31, (2 ** 31) - 1, -(2 ** 31), -(2 ** 31) - 1, 2 ** 40, 10 ** 30, 2 ** 63,
                2.0, "x", None, True, b"8"):
    # Asked of a value with no tab in it, so that only the reading of the number can fail: a tabsize
    # this side of the C int would otherwise be answered with two gigabytes of spaces.
    print("expandtabs", type(tabsize).__name__, "->", describe("f", lambda: b"ab".expandtabs(tabsize)),
          "| str", describe("f", lambda: "ab".expandtabs(tabsize)))

print("== a tabsize this side of the C int is counted before it is written ==")
for tabsize in (2 ** 10, 2 ** 16, 2 ** 20):
    # Only its length, because the answer is a million spaces and every octet of it says the same.
    # The column a tab has to cross is what is counted, so a value with no tab in it is the same length
    # however wide the tabsize is, and a newline restarts the count rather than the total.
    for value in (b"", b"ab", b"\t", b"a\tb", b"\t\n\t", b"\r\t", b"ab\t" * 3, "ab\t" * 3):
        print(type(value).__name__, "expandtabs", tabsize, repr(value)[:18], "->",
              describe("len", lambda: len(value.expandtabs(tabsize))))

print("== the arity of the paddings ==")
for name in ("center", "ljust", "rjust"):
    print("bytes", name, "none ->", describe("f", getattr(b"ab", name)))
    print("bytes", name, "three ->", describe("f", lambda: getattr(b"ab", name)(5, b"-", 1)))
    print("bytes", name, "fillbyte= ->", describe("f", lambda: getattr(b"ab", name)(5, fillbyte=b"-")))
    print("bytes", name, "width= ->", describe("f", lambda: getattr(b"ab", name)(width=5)))
    print("str", name, "none ->", describe("f", getattr("ab", name)))
    print("str", name, "three ->", describe("f", lambda: getattr("ab", name)(5, "-", 1)))
    print("str", name, "fillchar= ->", describe("f", lambda: getattr("ab", name)(5, fillchar="-")))
    print("str", name, "width= ->", describe("f", lambda: getattr("ab", name)(width=5)))
print("bytes zfill none ->", describe("f", b"ab".zfill))
print("bytes zfill three ->", describe("f", lambda: b"ab".zfill(5, 6, 7)))
print("bytes zfill width= ->", describe("f", lambda: b"ab".zfill(5, width=6)))
print("bytes zfill empty kw ->", describe("f", lambda: b"ab".zfill(**{})))
print("str zfill none ->", describe("f", "ab".zfill))
print("str zfill three ->", describe("f", lambda: "ab".zfill(5, 6, 7)))
print("bytes expandtabs none ->", describe("f", b"ab".expandtabs))
print("bytes expandtabs two ->", describe("f", lambda: b"ab".expandtabs(8, 8)))
print("bytes expandtabs three ->", describe("f", lambda: b"ab".expandtabs(8, 8, 8)))
print("bytes expandtabs tabsize= ->", describe("f", lambda: b"a\tb".expandtabs(tabsize=3)))
print("bytes expandtabs width= ->", describe("f", lambda: b"a\tb".expandtabs(width=8)))
print("bytes expandtabs both ->", describe("f", lambda: b"a\tb".expandtabs(8, tabsize=3)))
print("bytes expandtabs empty kw ->", describe("f", lambda: b"ab".expandtabs(**{})))
print("bytes center empty kw ->", describe("f", lambda: b"ab".center(**{})))
print("str expandtabs two ->", describe("f", lambda: "ab".expandtabs(8, 8)))
print("str expandtabs tabsize= ->", describe("f", lambda: "a\tb".expandtabs(tabsize=3)))
print("str expandtabs both ->", describe("f", lambda: "a\tb".expandtabs(8, tabsize=3)))
print("str expandtabs empty kw ->", describe("f", lambda: "ab".expandtabs(**{})))

print("== a width that is not a number ==")
for width in (2.0, "x", [], True, b"5", None, 2 ** 62, -(2 ** 62)):
    print("bytes center", type(width).__name__, "->", describe("f", lambda: b"ab".center(width)),
          "| bytes zfill", describe("f", lambda: b"ab".zfill(width)),
          "| str center", describe("f", lambda: "ab".center(width)),
          "| str expandtabs", describe("f", lambda: "a\tb".expandtabs(width)))

print("== a width too wide to allocate ==")
for width in (WORD_MAX, WORD_MAX - 1, WORD_MAX - 32, WORD_MAX - 33, WORD_MAX - 34, WORD_MAX - 100,
              2 ** 62, 2 ** 63, 2 ** 64, 10 ** 30):
    print("bytes center", width, "->", describe("f", lambda: b"ab".center(width)),
          "| ljust", describe("f", lambda: b"ab".ljust(width)),
          "| zfill", describe("f", lambda: b"ab".zfill(width)),
          "| str center", describe("f", lambda: "ab".center(width)),
          "| str zfill", describe("f", lambda: "ab".zfill(width)))
print("an empty value asked for the widest padding ->", describe("f", lambda: b"".center(WORD_MAX)),
      "| str", describe("f", lambda: "".center(WORD_MAX)))
