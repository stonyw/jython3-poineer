"""The six names that look for a run of octets, and the two ways of naming what they look for.

``find``, ``index``, ``count`` and their right-facing mirrors all take a needle and a range, and the needle
may be written two ways: as another bytes, or as the integer one octet is. That second reading brings a
second refusal with it — an integer outside 0 and 255 is refused for its value, ``byte must be in
range(0, 256)``, while anything that is neither an integer nor a run of octets is refused for its type, in
the words the buffer protocol uses for a str but not for the search's own argument. So ``b'ab'.find('a')``
says ``argument should be integer or bytes-like object`` and ``b'a' in b'b'`` says ``a bytes-like object is
required``: the same mistake, reported by two different functions.

The range is a third thing again. Its bounds are read before the needle is looked at, they are refused with
the slice sentence, and a start past the end is left where it was put rather than pulled back — which only
an empty needle can see, because an empty needle is not found in a range but read off its edges. ``count``
of an empty needle is therefore one more than the size of the range: every boundary is a match, and the two
edges of an empty range are boundaries.

Arity is a fourth: these methods are the old kind, which refuses a keyword at the call site in the type's
own words and then counts what arrived positionally with a bare name."""

TEXT = b"abracadabra"
EDGE = b"a"


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


print("== the two readings of a needle ==")
for needle in (b"a", b"ra", b"abracadabra", b"zz", b"", 97, 0, 255, 256, -1, 10 ** 30, None, 2.0, "a"):
    print("find", repr(needle), "->", describe("f", lambda: TEXT.find(needle)))
    print("rfind", repr(needle), "->", describe("f", lambda: TEXT.rfind(needle)))
    print("index", repr(needle), "->", describe("f", lambda: TEXT.index(needle)))
    print("rindex", repr(needle), "->", describe("f", lambda: TEXT.rindex(needle)))
    print("count", repr(needle), "->", describe("f", lambda: TEXT.count(needle)))
    print("in", repr(needle), "->", describe("f", lambda: needle in TEXT))

print("== a range, and what an empty needle makes of it ==")
for bounds in ((0, 11), (1, 4), (4, 1), (-4, None), (None, -4), (11, None), (12, None), (-12, None),
               (4, 2), (0, 0), (10 ** 30, None), (-(10 ** 30), None), (2 ** 40, None), (0, 10 ** 30),
               (0, -(10 ** 30)), (None, None), (2.0, None), ("x", None)):
    start, stop = bounds
    args = () if stop is None else (start, stop) if start is not None else (start,)
    print("find b'a'", args, "->", describe("f", lambda: TEXT.find(b"a", *args)),
          "| empty", describe("f", lambda: TEXT.find(b"", *args)),
          "| rfind empty", describe("f", lambda: TEXT.rfind(b"", *args)))
    print("count b''", args, "->", describe("f", lambda: TEXT.count(b"", *args)),
          "| b'ra'", describe("f", lambda: TEXT.count(b"ra", *args)),
          "| b'a'", describe("f", lambda: TEXT.count(b"a", *args)))

print("== a bound that is not a number ==")
for bound in (2.0, "x", [], 1.5):
    print("find bound", repr(bound), "->", describe("f", lambda: TEXT.find(b"a", bound)))
    print("startswith bound", repr(bound), "->", describe("f", lambda: TEXT.startswith(b"a", bound)))
    # Both arguments bad at once says which one was read first, so the range's refusal wins even
    # when the needle could not be read at all.
    print("find bound and needle", repr(bound), "->", describe("f", lambda: TEXT.find("x", bound)))

print("== the empty needle at the edges of a one-octet value ==")
for name in ("find", "rfind", "count"):
    print("one", name, "b'' ->", describe("f", lambda: getattr(EDGE, name)(b"")))
    print("empty self", name, "b'' ->", describe("f", lambda: getattr(b"", name)(b"")))
print("one index b'' ->", describe("f", lambda: EDGE.index(b"")))
print("b'' in one ->", describe("f", lambda: b"" in EDGE))
print("octet in empty ->", describe("f", lambda: 97 in b""))

print("== startswith and endswith, alone or in a tuple ==")
CANDIDATES = (b"a", b"ra", b"", b"zz", (b"a", b"zz"), (b"zz", b"a"), (b"a", "x"), ("x", b"a"), (),
              (b"a", (b"a",)), 2, "x", None, [b"a"], 10 ** 30)
for candidate in CANDIDATES:
    print("startswith", repr(candidate), "->", describe("f", lambda: TEXT.startswith(candidate)))
    print("endswith", repr(candidate), "->", describe("f", lambda: TEXT.endswith(candidate)))

print("== the range decides, but the needle is still read ==")
for bounds in ((1, 0), (2, 2), (5, None), (len(TEXT), None), (0, 0), (3, 3)):
    start, stop = bounds
    print("startswith b''", bounds, "->", describe("f", lambda: TEXT.startswith(b"", start, stop)))
    print("startswith b'a'", bounds, "->", describe("f", lambda: TEXT.startswith(b"a", start, stop)))
    print("endswith b'ra'", bounds, "->", describe("f", lambda: TEXT.endswith(b"ra", start, stop)))
    print("startswith bad in range", bounds, "->", describe("f", lambda: TEXT.startswith("x", start, stop)))
    print("startswith tuple of bad", bounds, "->",
          describe("f", lambda: TEXT.startswith((b"zz", "x"), start, stop)))
    print("startswith tuple matched", bounds, "->",
          describe("f", lambda: TEXT.startswith((b"a", "x"), start, stop)))

print("== the method's own words for a first argument ==")
for candidate in ("x", 2, None, [b"a"], b"a"):
    print("startswith", repr(candidate), "->", describe("f", lambda: TEXT.startswith(candidate, 1, 0)))
    print("endswith", repr(candidate), "->", describe("f", lambda: TEXT.endswith(candidate, 1, 0)))

print("== arity, and the two families of refusal ==")
for name in ("count", "find", "index", "rfind", "rindex", "startswith", "endswith"):
    method = getattr(TEXT, name)
    print(name, "no arguments ->", describe("f", lambda: method()))
    print(name, "four ->", describe("f", lambda: method(b"a", 0, 1, 2)))
    print(name, "keyword ->", describe("f", lambda: method(b"a", start=1)))
    print(name, "keyword only ->", describe("f", lambda: method(sub=b"a")))
print("strip keyword ->", describe("f", lambda: TEXT.strip(chars=b"a")))

print("== asked through the type, and the same object asked twice ==")
print("descriptor is one:", describe("f", lambda: bytes.find is bytes.find))
print("bound is not:", describe("f", lambda: TEXT.find is TEXT.find))
print("through the type:", describe("f", lambda: bytes.find(TEXT, b"ra")))
print("bound answers:", describe("f", lambda: TEXT.find(b"ra")))
print("same answer:", describe("f", lambda: bytes.find(TEXT, b"ra") == TEXT.find(b"ra")))
print("wrong receiver:", describe("f", lambda: bytes.find("abracadabra", b"ra")))
print("no receiver:", describe("f", lambda: bytes.find(b"ra")))
print("names:", describe("f", lambda: (bytes.find.__name__, bytes.find.__qualname__)))
