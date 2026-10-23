"""The two numbers a search or a cut takes, read the way CPython reads them.

A bound of a range and a count of work are not the same kind of number. The bound is an index: it is
clamped, or — for a start, in the one case an empty needle can see it — left past the end so that the
answer can be -1 rather than an edge. The count is a machine word: ``2 ** 40`` means every cut there is,
and only a number too wide for the word overflows, and the sentence it overflows with is the ssize one,
not the index one.

Both readings were measured on the bytes side first, and the same two helpers answer for ``str``, so this
script pins the strings against the same numbers. The values on either side of the word — ``2 ** 63 - 1``
and ``2 ** 63``, ``-(2 ** 63)`` and one below it — are where an off-by-one in the width test would show."""

STRINGS = (b"  a  b\tc  ", "  a  b\tc  ")
EMPTY_NEEDLE = (b"", "")
HUGE = 10 ** 30
WORD_MAX = 2 ** 63 - 1
WORD_OVERFLOW = 2 ** 63
NEGATIVE_MAX = -(2 ** 63)
NEGATIVE_OVERFLOW = -(2 ** 63) - 1


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


print("== a count is a machine word, on both sides ==")
for source, needle in zip(STRINGS, (b" ", " ")):
    for count in (0, 1, -1, WORD_MAX, WORD_OVERFLOW, NEGATIVE_MAX, NEGATIVE_OVERFLOW, HUGE, -HUGE,
                  2 ** 40, -(2 ** 40), 2.0, "x", None, True):
        print(type(source).__name__, "split", count, "->",
              describe("f", lambda: source.split(needle, count)))
        print(type(source).__name__, "rsplit", count, "->",
              describe("f", lambda: source.rsplit(needle, count)))
    for count in (0, 1, WORD_MAX, WORD_OVERFLOW, NEGATIVE_OVERFLOW, HUGE, 2.0, "x"):
        print(type(source).__name__, "replace", count, "->",
              describe("f", lambda: source.replace(needle, needle, count)))

print("== a start past the end, however far past it ==")
for source, needle in zip(STRINGS, EMPTY_NEEDLE):
    for start in (4, 5, 6, 12, 2 ** 31, 2 ** 40, HUGE, -12, -HUGE, None):
        args = () if start is None else (start,)
        print(type(source).__name__, "find b''", args, "->",
              describe("f", lambda: source.find(needle, *args)))
        print(type(source).__name__, "rfind b''", args, "->",
              describe("f", lambda: source.rfind(needle, *args)))
        print(type(source).__name__, "count b''", args, "->",
              describe("f", lambda: source.count(needle, *args)))
        print(type(source).__name__, "startswith b''", args, "->",
              describe("f", lambda: source.startswith(needle, *args)))
        print(type(source).__name__, "endswith b''", args, "->",
              describe("f", lambda: source.endswith(needle, *args)))

print("== a stop past the end, and a needle that is not empty ==")
for source, letter in zip(STRINGS, (b"a", "a")):
    for stop in (6, 12, 2 ** 40, HUGE, -12, -HUGE, None):
        args = (0,) if stop is None else (0, stop)
        print(type(source).__name__, "find a", args, "->",
              describe("f", lambda: source.find(letter, *args)))
        print(type(source).__name__, "count a", args, "->",
              describe("f", lambda: source.count(letter, *args)))
        print(type(source).__name__, "find empty at stop", args, "->",
              describe("f", lambda: source.find("", *args)))

print("== a bound that is not a number at all ==")
for source, letter in zip(STRINGS, (b"a", "a")):
    for bound in (2.0, "x", [], True):
        print(type(source).__name__, "find bound", repr(bound), "->",
              describe("f", lambda: source.find(letter, bound)))
        print(type(source).__name__, "count bound", repr(bound), "->",
              describe("f", lambda: source.count(letter, bound, bound)))
        # Two bad arguments at once name the one that was read first, and the two types do not read
        # them in the same order: a bytes search parses its range before its needle, a str search
        # takes the argument clinic's order, and str's two edge tests follow the bytes again.
        print(type(source).__name__, "find needle too", repr(bound), "->",
              describe("f", lambda: source.find(2, bound)))
        print(type(source).__name__, "startswith needle too", repr(bound), "->",
              describe("f", lambda: source.startswith(2, bound)))

print("== both bounds of an edge test ==")
for source in STRINGS:
    for bounds in ((0, 9), (2, 4), (4, 2), (9, 9), (12, 9), (9, 12), (0, 12), (2 ** 40, 2 ** 40),
                   (-12, None), (None, 2 ** 40), (2 ** 40, None), (-HUGE, None), (0, -HUGE)):
        start, stop = bounds
        if stop is None:
            args = (start,)
        elif start is None:
            args = (0, stop)
        else:
            args = (start, stop)
        print(type(source).__name__, "startswith", args, "->",
              describe("f", lambda: source.startswith("", *args)),
              "| a", describe("f", lambda: source.startswith("a", *args)),
              "| endswith", describe("f", lambda: source.endswith("", *args)),
              "| c", describe("f", lambda: source.endswith("c", *args)))
