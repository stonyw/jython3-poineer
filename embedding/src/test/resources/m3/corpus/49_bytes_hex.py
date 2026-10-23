"""The two halves of one text: ``b.hex(sep, bytes_per_sep)`` and ``bytes.fromhex(string)``.

``hex`` is CPython's ``_Py_strhex_impl`` in ``Python/pystrhex.c``. It writes two lower-case characters
per octet out of ``Py_hexdigits`` and never one — there is no upper-case form of a bytes' hex, because
the octet is the thing being named and ``0x61`` has only one spelling here. What the two parameters do
is the part worth pinning, since neither is what the name suggests. ``bytes_per_sep`` is a number of
*octets*, not of characters, so a group of two octets is four digits; it is counted from whichever end
its sign points at, and the remainder falls in the group *farthest* from that end — ``b"abcde".hex(":",
4)`` is ``61:62636465`` while ``.hex(":", -4)`` is ``61626364:65``. A step of zero, or of an absolute
value the whole run does not fill, writes no separator at all. And it is converted to a C ``int``
before ``sep`` is looked at, so ``b"".hex("::", 2**31)`` answers ``OverflowError`` about the step
rather than ``ValueError`` about the separator.

``sep`` is asked three questions in a fixed order, and the answers are three different messages:
``len(sep)``, which is a ``TypeError`` for anything without a length and so reaches ``str``, ``bytes``,
a one-character ``str`` and a one-octet ``bytes`` but refuses a list of them; then whether the length
is one, whose complaint is ``sep must be length 1.``; then whether the single character is below 128,
whose complaint is ``sep must be ASCII.``, with the period included in both. A ``str`` of length one is
read as a code point, so ``\\ud800`` is refused for being above ``0x7f`` like any accented letter.

``fromhex`` walks the text itself and is the reason the position in its error is worth pinning: the
count is of the characters as they were given, not of the digits kept. Spaces (``0x20``) and the five
C whitespace octets ``0x09``-``0x0d`` are skipped anywhere, a non-digit reports ``non-hexadecimal
number found in fromhex() arg at position N``, and a lone trailing digit reports the even-length
complaint *after* everything else has been accepted — so ``"   6"`` is an odd count, not position three.
The two directions round-trip only where the separators fall between pairs, which is what the last
section measures: ``b"\\xff" * 4`` read back through ``hex(":", 2)`` fails at position 4."""

VALUES = (b"", b"\x00", b"a", b"ab", b"abc", b"abcde", b"\xff" * 4, bytes(range(256)))
STEPS = (1, 2, 3, 4, 5, 6, 7, 0, -1, -2, -3, -4, -5, -6, -7)
TEXTS = ("", " ", "61", "6162", "414243", "AbCd", "61 62", " 61\t62\n\r\x0b\x0c 63 ", "6", "616",
         "z", "6z", "6 1 6 2", "61  ", "  ", "\n", "0x61", "61_62", "61626364656667")


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


print("== the two digits of every octet ==")
for octet in list(range(0, 256, 17)) + [0x7F, 0x80]:
    print("%3d %s" % (octet, bytes([octet]).hex()))
print("all:", len(bytes(range(256)).hex()), bytes(range(16)).hex())
print("empty:", repr(b"".hex()), "len", len(b"".hex()))

print("== a separator between groups, counted from each end ==")
for step in STEPS:
    print("%2d %s" % (step, b"abcde".hex(":", step)))
for step in STEPS:
    print("%2d %s" % (step, b"abcde".hex(b":", step)))

print("== the three questions sep is asked, in order ==")
for sep in (":", b":", b"::", "  ", "", b"", "\u00e9", "\ud800", "\u2028", "\u007f", "\u0080"):
    print(repr(sep), "->", describe("f", lambda: b"abcd".hex(sep)))
for sep in ([":"], (), set(), {"a": 1}, {"a"}, str, bytes, len, print, iter):
    print(repr(sep)[:16], "->", describe("f", lambda: b"abc".hex(sep)))
print("None ->", describe("f", lambda: b"abcd".hex(None)))

print("== the step is converted before the separator is looked at ==")
print("bad sep, bad step ->", describe("f", lambda: b"abcd".hex("::", "x")))
print("bad sep, huge step ->", describe("f", lambda: b"abcd".hex("::", 2**31)))
print("ok sep, huge step ->", describe("f", lambda: b"abcd".hex(":", 2**31)))
print("ok sep, huge negative ->", describe("f", lambda: b"abcd".hex(":", -(2**31) - 1)))
print("widest step ->", describe("f", lambda: b"abcd".hex(":", 2**31 - 1)))
print("float step ->", describe("f", lambda: b"abcd".hex(":", 2.5)))
print("None step ->", describe("f", lambda: b"abcd".hex(":", None)))
print("bool step ->", describe("f", lambda: b"abcd".hex(":", True)))
print("sep given, no step ->", describe("f", lambda: b"".hex(":")))
print("step given, no sep ->", describe("f", lambda: b"abcdef".hex(bytes_per_sep=2)))

print("== hex takes two parameters, in either order, by either name ==")
for value in VALUES:
    print(len(value), "hex ->", value.hex(), "| sep and 2 ->", describe(
        "f", lambda: value.hex(":", 2)), "| names ->", describe(
        "f", lambda: value.hex(bytes_per_sep=2, sep=":")))
print("three ->", describe("f", lambda: b"abcd".hex(":", 1, 2)))
print("unknown keyword ->", describe("f", lambda: b"abcd".hex(x=1)))
print("name and position ->", describe("f", lambda: b"abcd".hex(":", sep=",")))
print("both filled twice ->", describe("f", lambda: b"abcd".hex(":", 2, bytes_per_sep=3)))
print("empty keyword dict ->", describe("f", lambda: b"abcd".hex(**{})))
print("unbound ->", describe("f", lambda: bytes.hex(b"abcd")))
print("unbound, wrong self ->", describe("f", lambda: bytes.hex("abcd")))

print("== fromhex walks the text and counts it as it was given ==")
for text in TEXTS:
    print(repr(text), "->", describe("f", lambda: bytes.fromhex(text)))
for value in (b"6162", b"61 62", b"\xff 61", b"61\xff", b"6", b"\t61\x0c62"):
    print(repr(value), "->", describe("f", lambda: bytes.fromhex(value)))
print("100 spaces ->", describe("f", lambda: bytes.fromhex(" " * 100 + "z")))
print("odd after spaces ->", describe("f", lambda: bytes.fromhex("   6")))
print("500 pairs ->", describe("f", lambda: len(bytes.fromhex("ab" * 500))))
print("odd at the end of a long text ->", describe("f", lambda: bytes.fromhex("61" * 500 + "z")))

print("== fromhex takes one argument, positionally, of a str or a bytes ==")
for value in (3, None, ["61"], 3.5, (), {}, 1j):
    print(type(value).__name__, "->", describe("f", lambda: bytes.fromhex(value)))
print("none ->", describe("f", lambda: bytes.fromhex()))
print("two ->", describe("f", lambda: bytes.fromhex("61", "62")))
print("named string ->", describe("f", lambda: bytes.fromhex(string="61")))
print("named x ->", describe("f", lambda: bytes.fromhex(x="61")))
print("empty keywords ->", describe("f", lambda: bytes.fromhex("61", **{})))
print("read from a value ->", describe("f", lambda: b"".fromhex("61")))

print("== one of these takes no receiver and is the same object every time ==")
print("maketrans is itself ->", bytes.maketrans is bytes.maketrans)
print("maketrans read from a value ->", b"".maketrans is bytes.maketrans)
print("fromhex is itself ->", bytes.fromhex is bytes.fromhex)
print("fromhex read twice ->", b"".fromhex is bytes.fromhex)
print("names ->", bytes.maketrans.__name__, bytes.fromhex.__name__,
      bytes.maketrans.__qualname__, bytes.fromhex.__qualname__)
print("self ->", repr(bytes.maketrans.__self__), repr(bytes.fromhex.__self__)[0:16])
print("reprs ->", repr(bytes.maketrans)[0:38], "|", repr(bytes.fromhex)[0:38])

print("== the round trip only works where the separators fall between pairs ==")
for value in VALUES:
    for sep, step in ((None, None), (":", 2), (",", -3), (" ", 1), ("|", -1)):
        text = value.hex() if sep is None else value.hex(sep, step)
        print(len(value), repr(sep), step, describe("f", lambda: bytes.fromhex(text) == value),
              "len", describe("n", lambda: len(text)))
