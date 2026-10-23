"""``bytes.maketrans(from, to)`` and ``bytes.translate(table, delete=b"")``, the two halves of a map.

A translation table is not a mapping: it is a 256-octet bytes whose index is an octet and whose value is
what that octet becomes, with everything ``maketrans`` was not told about left pointing at itself. That
is why ``len(bytes.maketrans(b"", b""))`` is 256 and why the table for ``b"abc" -> b"xyz"`` answers 120
at index 97 and 97 at index 120 while still answering 65 at index 65 — the upper case is untouched. A
name repeated in ``from`` is written twice and the last write wins, so ``maketrans(b"aba", b"xyz")``
leaves 97 pointing at ``y``: the loop CPython runs in ``_Py_bytes_maketrans`` walks ``from`` once and
assigns, with no check that a key appears twice.

Unlike ``str.maketrans``, this one takes octets only. A ``str`` argument is refused with the bytes-like
complaint, ``a bytes-like object is required, not 'str'``, and its two parameters are positional-only,
so naming them is the other refusal, ``bytes.maketrans() takes no keyword arguments``. Its arity
sentence belongs to CPython's legacy family — ``maketrans expected 2 arguments, got 1``, bare name,
``got`` rather than ``(N given)`` — while its keyword sentence belongs to the modern one. One method,
two wordings, because the declaration and the wrapper are what choose, not the method's own preference.

``translate`` is the other half and is declared ``($self, table, /, delete=b"")``: the table is
positional-only, the delete may be named, and the two refusals a call outside those bounds gets are the
qualified vectorcall pair — ``translate() takes at least 1 positional argument (0 given)``, singular at
one, and ``translate() takes at most 2 arguments (3 given)``. ``None`` is a legal table, meaning the
identity, and is not a legal delete; a table of the wrong length says ``translation table must be 256
characters long``, and that check comes before the delete is converted, so a short table and a
nonsense delete report the table. What comes first of all is the arity: a third argument is refused
before either converter runs.

The result is a copy only when something changed. ``b"abc".translate(maketrans(b"", b""))`` is the same
object, and so is a table that maps only an octet the receiver does not contain — CPython looks at the
octets it wrote, not at the table it was handed, which is why ``.translate(maketrans(b"z", b"q"))`` also
answers with ``self`` for a receiver with no ``z`` in it."""

ALPHABET = bytes(range(97, 123))
SWAP = bytes.maketrans(b"abc", b"xyz")
IDENTITY = bytes.maketrans(b"", b"")
REVERSE = bytes.maketrans(bytes(range(256)), bytes(range(255, -1, -1)))


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


print("== a table is 256 octets that default to pointing at themselves ==")
print("type", type(IDENTITY).__name__, "len", len(IDENTITY), "identity", list(IDENTITY) == list(range(256)))
print("the mapped run ->", list(SWAP[97:103]), list(SWAP[65:71]))
print("untouched neighbours ->", SWAP[96], SWAP[100], SWAP[0], SWAP[255])
print("non-ascii in to ->", describe("f", lambda: len(bytes.maketrans(b"ab", b"\xe9\xff"))),
      bytes.maketrans(b"ab", b"\xe9\xff")[97])
print("last write wins ->", list(bytes.maketrans(b"aba", b"xyz"))[97:99],
      list(bytes.maketrans(b"aa", b"xy"))[97])
print("the whole alphabet rotated ->", list(bytes.maketrans(ALPHABET, ALPHABET[13:] + ALPHABET[:13])[97:103]))

print("== maketrans takes two octet runs, positionally ==")
for args in ((b"", b""), b"ab", (b"ab",), (b"abc", b"x"), (b"", b"x"), (b"ab", b"xy", b"z")):
    print(repr(args), "->", describe("f", lambda: len(bytes.maketrans(*args))))
print("str from ->", describe("f", lambda: bytes.maketrans("ab", b"xy")))
print("str to ->", describe("f", lambda: bytes.maketrans(b"ab", "xy")))
print("int ->", describe("f", lambda: bytes.maketrans(3, 4)))
print("named ->", describe("f", lambda: bytes.maketrans(frm=b"ab", to=b"xy")))
print("empty keywords ->", describe("f", lambda: len(bytes.maketrans(b"ab", b"xy", **{}))))
print("no arguments ->", describe("f", lambda: bytes.maketrans()))
print("read from a value ->", describe("f", lambda: len(b"abc".maketrans(b"a", b"b"))))

print("== translate maps, and deletes, or both ==")
print("table ->", describe("f", lambda: b"abcabc".translate(SWAP)))
print("no table, delete ->", describe("f", lambda: b"abcabc".translate(None, b"b")))
print("table and delete ->", describe("f", lambda: b"abcabc".translate(SWAP, b"a")))
print("delete beats the table ->", describe("f", lambda: b"abc".translate(bytes.maketrans(b"a", b"z"), b"a")))
print("zeros ->", describe("f", lambda: b"abc".translate(b"\x00" * 256)))
print("swap ->", describe("f", lambda: b"abab".translate(bytes.maketrans(b"ab", b"ba"))))
print("every octet deleted ->", describe("f", lambda: bytes(range(256)).translate(None, bytes(range(256)))))
print("reverse twice is itself ->", describe("f", lambda: bytes(range(256)).translate(REVERSE).translate(REVERSE) == bytes(range(256))))
print("empty receiver ->", describe("f", lambda: b"".translate(SWAP)),
      describe("f", lambda: b"".translate(None, b"a")))

print("== a copy only when something changed ==")
TEXT = b"abc"
CASES = (("identity table", IDENTITY, None), ("no table", None, None),
         ("maps an absent octet", bytes.maketrans(b"z", b"q"), None),
         ("maps a present octet to itself", bytes.maketrans(b"a", b"a"), None),
         ("no table, empty delete", None, b""), ("identity table, empty delete", IDENTITY, b""),
         ("identity table, absent delete", IDENTITY, b"z"), ("a real table", SWAP, None),
         ("a real table, absent delete", SWAP, b"z"), ("no table, absent delete", None, b"z"))
for label, table, delete in CASES:
    answer = TEXT.translate(table) if delete is None else TEXT.translate(table, delete)
    print("%-32s -> %-8r is the receiver: %s" % (label, answer, answer is TEXT))

print("== the checks come in this order ==")
print("short table, bad delete ->", describe("f", lambda: b"abc".translate(b"ab", 3)))
print("short table, str delete ->", describe("f", lambda: b"abc".translate(b"ab", "x")))
print("bad table, bad delete ->", describe("f", lambda: b"abc".translate(3, 4)))
print("255 ->", describe("f", lambda: b"abc".translate(b"a" * 255)))
print("257 ->", describe("f", lambda: b"abc".translate(b"a" * 257)))
print("str table ->", describe("f", lambda: b"abc".translate("a" * 256)))
print("list table ->", describe("f", lambda: b"abc".translate(list(range(256)))))
print("None table, None delete ->", describe("f", lambda: b"abc".translate(None, None)))
print("identity table, None delete ->", describe("f", lambda: b"abc".translate(IDENTITY, None)))
print("arity first ->", describe("f", lambda: b"abc".translate(3, 4, 5)))

print("== translate's parameters, named and unnamed ==")
print("none ->", describe("f", lambda: TEXT.translate()))
print("three ->", describe("f", lambda: TEXT.translate(IDENTITY, b"a", b"b")))
print("delete by name ->", describe("f", lambda: TEXT.translate(None, delete=b"a")))
print("table by name ->", describe("f", lambda: TEXT.translate(table=IDENTITY)))
print("both by name ->", describe("f", lambda: TEXT.translate(table=SWAP, delete=b"b")))
print("name and position ->", describe("f", lambda: TEXT.translate(IDENTITY, b"a", delete=b"b")))
print("unknown name ->", describe("f", lambda: TEXT.translate(IDENTITY, x=1)))
print("empty keywords ->", describe("f", lambda: TEXT.translate(SWAP, **{})))
print("unbound ->", describe("f", lambda: bytes.translate(b"abc", SWAP)))
print("unbound, wrong self ->", describe("f", lambda: bytes.translate("abc", SWAP)))
