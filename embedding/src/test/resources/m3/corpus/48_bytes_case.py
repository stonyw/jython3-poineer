"""The bytes case family and the eight predicates, which are ASCII and nothing else.

A bytes is a run of octets, so its categories are the C library's: an octet above ``0x7f`` is in none of
them. ``b"\\xe9".isalpha()`` is false while ``"é".isalpha()`` is true, and ``b"A\\" + bytes([0x8a])``
never upper-cases the second octet. That is the whole difference from ``str``, and it is worth pinning
both ways: the tables a bytes uses are twenty-six letters, ten digits and the handful of spaces C's
``ctype.h`` names, and nothing else.

``title`` is the only one with a state, and its state is the previous octet: a letter is upper-cased
when what came before it was not an alphabetic octet, and lower-cased otherwise — so ``b"a.B".title()``
keeps the ``B`` because a full stop is not a letter, while ``b"aB".title()`` lower-cases the ``B``.
``istitle`` reads the same rule back: a run must have at least one cased octet, and every cased octet
must be upper exactly where ``title`` would have put it upper.

``capitalize`` upper-cases one octet and lower-cases the rest of the run, which is why ``b'AB'.
capitalize()`` is ``b'Ab'`` and not ``b'AB'``. ``swapcase`` is the only one that leaves an unknown octet
alone, so ``b'a\\xff'.swapcase()`` keeps the ``\\xff``.

None of them is in-place: a bytes copies even when the case is already the one asked for, which is why
``b'abc'.lower() is b'abc'`` is false — the same question ``str`` answers the other way round. And the
predicates are ``METH_O``-free no-argument methods, so they refuse an argument with the qualified
``bytes.isalpha() takes no arguments (1 given)`` rather than the legacy sentence their neighbours use."""

STRINGS = (b"  a  b\tc  ", "  a  b\tc  ")
PREDICATES = ("isalnum", "isalpha", "isascii", "isdigit", "islower", "isspace", "istitle", "isupper")
CASES = ("capitalize", "lower", "title", "upper", "swapcase")
SENTENCES = (b"", b" ", b"\t\n\v\f\r ", b"\x1c\x1d\x85\xa0", b"a", b"A", b"aB", b"a1", b"1",
             b"0123456789", b"abc def", b"ABC DEF", b"Abc Def", b"\xe9", b"\xffa", b"a\xff", b"2+2",
             b"-1", b"\xb9", b"\xc2\xb2", b"Hello World", b"HELLO world", b"a b", b"\x0b", b"_x",
             b"Title Case Here", b"a.B c", b"  abc  ", b"1a", b"a1 ", b"\xff", b"\x7f", b"\x00",
             b"a_b", b"A_b", b"a B", b"A a", b"__a", b"i", b"I", b"\xb5", b"\xc3\x9f")
EDGE = (b" ", b"a", b"A", b"1", b"_", b".", b"\t", b"\xff", b"\x00", b"\x0b")
ACCENTED = (b"\xe9", b"\xc9", "é", "É", "ß", b"\xc3\x9f", "İ", "a\u0301", b"a\x01", "²", b"\xb2",
            "\u00a0", b"\xa0", "\u212a", "K", b"K", "\u0301", "1", b"1")


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


def answers(value, names):
    # Repr rather than str, because a predicate's answer is a word but a case method's is a run of
    # octet values that includes the ones a terminal would not like to be handed.
    return " ".join("%s=%r" % (name, getattr(value, name)()) for name in names)


print("== every octet, classified ==")
for octet in range(256):
    value = bytes([octet])
    print("%3d %6s %s" % (octet, repr(value)[1:], answers(value, PREDICATES)))

print("== every octet, put into each case ==")
for octet in range(256):
    value = bytes([octet])
    print("%3d %6s %s" % (octet, repr(value)[1:], answers(value, CASES)))

print("== title's neighbour rule, on pairs ==")
for first in EDGE:
    for second in EDGE:
        pair = first + second
        print(repr(pair), "title ->", repr(pair.title()), "| istitle =", pair.istitle(),
              "| islower =", pair.islower(), "| isupper =", pair.isupper(),
              "| capitalize ->", repr(pair.capitalize()), "| swapcase ->", repr(pair.swapcase()))

print("== the predicates over sentences ==")
for value in SENTENCES:
    print(repr(value), "->", answers(value, PREDICATES))

print("== the case family over the same sentences ==")
for value in SENTENCES:
    print(repr(value), "->", answers(value, CASES))

print("== the same questions of a str, for the difference is the point ==")
for value in SENTENCES:
    text = value.decode("latin-1")
    print(repr(text), "->", answers(text, PREDICATES), "|", answers(text, CASES))

print("== where a bytes and a str disagree ==")
for value in ACCENTED:
    print(type(value).__name__, repr(value), "->", answers(value, PREDICATES))
for value in ACCENTED:
    print(type(value).__name__, repr(value), "->", answers(value, CASES))

print("== a character whose case one table decides and a mapping does not ==")
# A letter already in its title form is neither of the two cases ``swapcase`` switches between, an
# uppercase digraph is title-cased on its own, and a handful of letters are cased by Unicode's tables
# while Java's one-to-one mapping leaves them where they are. 'ŉ' is not among them: its title form is
# two characters, which is the mapping this runtime does not have.
CASED = ("ǅ", "ǈ", "ǋ", "ǲ", "Ǆ", "Ǳ", "ǆ", "ʰ", "ᴬ", "Ⅰ", "ⅰ", "Ⓐ", "ⓐ", "ↄ", "Ʞ")
for letter in CASED:
    print("%s %04X ->" % (repr(letter)[1:-1], ord(letter)),
          answers(letter, PREDICATES), "|", answers(letter, CASES))
for word in ("aǅb", "ǅ b", "Ǆǅ", "ǅǄ", "Ⅰx", "xⅰ", "Ⓐⓐ", "aʰb", "ʰA", "Aʰ", "ǅ. ǈ-ǋ_ǲ"):
    print(repr(word), "->", answers(word, PREDICATES), "|", answers(word, CASES))

print("== the arity of the case family and the predicates ==")
for name in PREDICATES + CASES:
    octet = getattr(b"ab", name)
    text = getattr("ab", name)
    print("bytes", name, "one ->", describe("f", lambda: octet(b"a")))
    print("bytes", name, "keyword ->", describe("f", lambda: octet(x=1)))
    print("bytes", name, "two ->", describe("f", lambda: octet(b"a", b"b")))
    print("str", name, "one ->", describe("f", lambda: text("a")))
    print("str", name, "keyword ->", describe("f", lambda: text(x=1)))

print("== a padding's neighbour is asked the same questions ==")
for value in STRINGS:
    print(type(value).__name__, "center 6 ->", describe("f", lambda: value.center(6)),
          "| the case of it ->", describe("f", lambda: value.center(6).upper()),
          "| predicate of it ->", describe("f", lambda: value.center(6).isspace()))

print("== none of them is in place, and one of them is ==")
for value in (b"abc", b"ABC", b"123", b"", b"a\xff"):
    print(repr(value), "lower is self:", value.lower() is value,
          "| upper is self:", value.upper() is value,
          "| capitalize is self:", value.capitalize() is value,
          "| title is self:", value.title() is value,
          "| swapcase is self:", value.swapcase() is value)
for value in ("abc", "ABC", "123", "", "a\u00e9"):
    print(repr(value), "lower is self:", value.lower() is value,
          "| upper is self:", value.upper() is value,
          "| capitalize is self:", value.capitalize() is value,
          "| title is self:", value.title() is value,
          "| swapcase is self:", value.swapcase() is value)
