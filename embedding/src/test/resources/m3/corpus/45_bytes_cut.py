"""Cutting a bytes apart: the separators, the alphabet, the three breaks and the count that is a size.

The cut family reads its arguments in the order CPython's own argument tables list them, and that order is
not the order a reader expects. ``split`` takes ``sep`` first and ``maxsplit`` second but converts the
count first — ``b'ab'.split('x', 'y')`` is refused as an integer, not as a run of octets — while
``startswith`` converts the range before the needle, and ``replace`` converts the needle before the count.
Three of these methods take keywords and four do not: ``split``, ``rsplit`` and ``splitlines`` are the new
kind, naming themselves in a refusal that counts everything that arrived (``split() takes at most 2
arguments (3 given)``), while ``strip`` and the search family are the old kind, refusing a keyword in
``bytes.strip() takes no keyword arguments`` and then counting with a bare ``strip expected at most 1
argument, got 2``.

An octet count is read as a machine word, not as an index: ``split(b'a', 2 ** 40)`` is every cut there is,
and only ``10 ** 30`` overflows, with the ssize sentence rather than the index one.

The alphabet is ASCII, and only ASCII. The six spaces ``strip`` and ``split()`` remove are ``\t\n\v\f\r``
and the space; ``\x85``, ``\xa0`` and the three information separators are whitespace to ``str`` and are
kept by ``bytes``. The three breaks ``splitlines`` reads are ``\n``, ``\r`` and the pair between them, so
``b'a\x0bb'.splitlines()`` is one line where ``'a\x0bb'.splitlines()`` is two. Case is the same story:
``b'\xff'.upper()`` is ``b'\xff'``, because the fold is a to z and nothing else.

Where nothing has to change, CPython hands back the very same object, and ``is`` says so."""

TEXT = b"abracadabra"
SPACED = b"  a  b\tc\nd\x0b\x0c\r\x1c\x1d\x1e\x85\xa0 e  "
MIXED = b"a\nb\rc\r\nd\x0be\x0cf\x1cg\x1dh\x1e\x85\xe2\x80\xa8"


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


def same(label, outer, inner):
    print(label, "->", describe("f", lambda: outer is inner))


print("== split and rsplit, by separator and by whitespace ==")
for source in (TEXT, b"", b"a", b"a,a", b",,", SPACED, b"   ", b" , "):
    for separator in (b",", b"a", b" ", None):
        for count in (None, 0, 1, 2, -1, 10 ** 30, -(10 ** 30), 2 ** 40):
            args = () if separator is None else (separator,) if count is None else (separator, count)
            print(repr(source), "split", args, "->", describe("f", lambda: source.split(*args)))
            print(repr(source), "rsplit", args, "->", describe("f", lambda: source.rsplit(*args)))

print("== a separator that is refused, and the count read first ==")
for arguments in (("x",), (2,), (b"",), (b"", 2.0), ("x", 2.0), ("x", "y"), (b"a", 2.0), (b"a", "y"),
                  (b"a", 10 ** 30), (b"a", -(10 ** 30)), (None, None)):
    print("split", arguments, "->", describe("f", lambda: TEXT.split(*arguments)))
    print("rsplit", arguments, "->", describe("f", lambda: TEXT.rsplit(*arguments)))

print("== the keywords only three of them take ==")
for name in ("split", "rsplit"):
    method = getattr(TEXT, name)
    print(name, "by name ->", describe("f", lambda: method(sep=b"a")))
    print(name, "count by name ->", describe("f", lambda: method(maxsplit=2)))
    print(name, "both by name ->", describe("f", lambda: method(sep=b"a", maxsplit=2)))
    print(name, "name and position ->", describe("f", lambda: method(b"a", sep=b"b")))
    print(name, "unknown name ->", describe("f", lambda: method(b"a", x=1)))
    print(name, "three given ->", describe("f", lambda: method(b"a", 1, 2)))
    print(name, "keyword and position ->", describe("f", lambda: method(b"a", 1, maxsplit=2)))
print("splitlines by name ->", describe("f", lambda: TEXT.splitlines(keepends=True)))
print("splitlines unknown ->", describe("f", lambda: TEXT.splitlines(keep=1)))
print("splitlines two ->", describe("f", lambda: TEXT.splitlines(1, 2)))

print("== splitlines reads anything true, and three breaks ==")
for keepends in (0, 1, "", "x", [], [0], None, True, False, 2):
    print("keepends", repr(keepends), "->", describe("f", lambda: MIXED.splitlines(keepends)))
for source in (b"", b"\n", b"\r", b"\r\n", b"a\n", b"a\n\n", b"\n\na", b"a\rb", b"a\r\rb", b"\x85",
               b"\xe2\x80\xa8", b"\n\r", b"\r\n\r\n"):
    print(repr(source), "->", describe("f", lambda: source.splitlines()),
          "| kept", describe("f", lambda: source.splitlines(True)))

print("== partition and rpartition ==")
for separator in (b"a", b"bra", b"", b"zz", b"abracadabra", 2, "x"):
    print("partition", repr(separator), "->", describe("f", lambda: TEXT.partition(separator)))
    print("rpartition", repr(separator), "->", describe("f", lambda: TEXT.rpartition(separator)))
print("partition none ->", describe("f", lambda: TEXT.partition()))
print("partition keyword ->", describe("f", lambda: TEXT.partition(sep=b"a")))
print("partition two ->", describe("f", lambda: TEXT.partition(b"a", 1)))
same("miss keeps the value", TEXT.partition(b"zz")[0], TEXT)
same("miss keeps the value on the right", TEXT.rpartition(b"zz")[2], TEXT)
print("found cuts:", describe("f", lambda: TEXT.partition(b"c")[0] == b"abra"))

print("== the alphabet, and what it is not ==")
for alphabet in (None, b"", b"a", b" x", b"\t\n\x0b\x0c\r ", b"\x85\xa0\x1c", SPACED):
    for name in ("strip", "lstrip", "rstrip"):
        print(name, repr(alphabet), "of", repr(SPACED), "->",
              describe("f", lambda: getattr(SPACED, name)(alphabet)))
        print(name, repr(alphabet), "of", repr(b"  ab  "), "->",
              describe("f", lambda: getattr(b"  ab  ", name)(alphabet)))
for wrong in ("x", 2, 1.0, [b"a"]):
    print("strip", repr(wrong), "->", describe("f", lambda: TEXT.strip(wrong)))
print("strip two ->", describe("f", lambda: TEXT.strip(b"a", 1)))
print("strip keyword ->", describe("f", lambda: TEXT.strip(chars=b"a")))
same("nothing removed", SPACED.strip(b"q"), SPACED)
same("nothing removed on the left", SPACED.lstrip(b"q"), SPACED)
print("all removed:", describe("f", lambda: b"aaa".strip(b"a")))

print("== removeprefix and removesuffix ==")
for edge in (b"ab", b"", b"abracadabra", b"zz", b"a" * 12, 2, "x", None):
    print("removeprefix", repr(edge), "->", describe("f", lambda: TEXT.removeprefix(edge)))
    print("removesuffix", repr(edge), "->", describe("f", lambda: TEXT.removesuffix(edge)))
same("no prefix removed", TEXT.removeprefix(b"zz"), TEXT)
same("no suffix removed", TEXT.removesuffix(b"zz"), TEXT)
print("one argument:", describe("f", lambda: TEXT.removeprefix()))
print("two arguments:", describe("f", lambda: TEXT.removeprefix(b"a", 1)))

print("== replace ==")
for old, new, count in ((b"a", b"X", None), (b"a", b"X", 0), (b"a", b"X", 1), (b"a", b"X", -1),
                        (b"a", b"X", 2 ** 40), (b"a", b"X", 10 ** 30), (b"a", b"", None),
                        (b"", b"-", None), (b"", b"-", 2), (b"", b"", None), (b"ra", b"R", 1),
                        (b"zz", b"R", None), (b"a", b"a", None)):
    args = (old, new) if count is None else (old, new, count)
    print("replace", args, "of", repr(TEXT), "->", describe("f", lambda: TEXT.replace(*args)))
for wrong in ((b"a", b"X", 2.0), (b"a", b"X", b"1"), (b"a", "X"), ("a", b"X"), (2, 3), (b"a",)):
    print("replace", wrong, "->", describe("f", lambda: TEXT.replace(*wrong)))
print("replace keyword ->", describe("f", lambda: TEXT.replace(old=b"a", new=b"X")))
print("replace four ->", describe("f", lambda: TEXT.replace(b"a", b"X", 1, 2)))
same("nothing replaced", TEXT.replace(b"zz", b"R"), TEXT)
same("count zero", TEXT.replace(b"a", b"X", 0), TEXT)
same("empty prefix keeps", TEXT.removeprefix(b""), TEXT)

print("== join ==")
for parts in ([b"a", b"b"], (), [b""], (b"a",), [b"a", 1], [1, 2], ["a"], b"ab", b"", 3, "ab"):
    print("join", type(parts).__name__, repr(parts)[:24], "->",
          describe("f", lambda: b",".join(parts)))
print("empty separator ->", describe("f", lambda: b"".join([b"a", b"b"])))
print("generator ->", describe("f", lambda: b",".join(x for x in (b"a", b"b"))))
print("one argument:", describe("f", lambda: b",".join()))
print("two arguments:", describe("f", lambda: b",".join([b"a"], 2)))
print("keyword:", describe("f", lambda: b",".join(iterable=[b"a"])))

print("== case, ASCII only ==")
for source in (b"abc", b"ABC", b"MiXeD", b"\xff\xe9\x85", b"\xc5\xc8\xde", b"a1 \x00", b""):
    print(repr(source), "upper ->", describe("f", lambda: source.upper()),
          "| lower", describe("f", lambda: source.lower()))
same("unchanged case copies", TEXT.upper() is TEXT, False)
