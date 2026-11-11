"""bytearray's third segment: the read-only methods.

The forty methods a bytearray shares with bytes answer with the same bodies, and the results
that are runs come back as bytearrays rather than bytes: split's list, partition's tuple,
translate's copy, upper's folded run. The two that are not methods of an instance are filed
here too -- fromhex through the type and maketrans as a static -- and the sentences of the
eighteen names whose text mentions the receiver's own type are the mutable ones.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


class Rune:
    def __index__(self):
        return 97


class Blob:
    def __bytes__(self):
        return b"ab"


run = bytearray(b"Hello World")
print("type", type(run.upper()).__name__, type(run.split()).__name__)

print("capitalize", bytearray(b"hELLO wORLD").capitalize())
print("capitalize-empty", bytearray().capitalize())
print("center", bytearray(b"ab").center(6, b"-"))
print("center-default", bytearray(b"ab").center(5))
print("center-odd", bytearray(b"ab").center(5, b"."))
print("center-even", bytearray(b"ab").center(6, b"."))
print("center-narrow", bytearray(b"abc").center(2, b"."))
print("count", run.count(b"l"), run.count(b"l", 4), run.count(b"l", 0, 3))
print("count-empty-needle", run.count(b""), run.count(b"", 4))
print("decode", run.decode(), run.decode("utf-8", "strict"))
print("endswith", run.endswith(b"ld"), run.endswith((b"x", b"ld")), run.endswith(b"l", 0, 3))
print("endswith-empty", run.endswith(b""), run.endswith(b"", 4, 3))
print("expandtabs", bytearray(b"a\tb").expandtabs(), bytearray(b"a\tb").expandtabs(4))
print("expandtabs-zero", bytearray(b"ab\tc").expandtabs(0))
print("find", run.find(b"o"), run.find(b"o", 5), run.find(b"z"))
print("find-empty", run.find(b""), run.find(b"", 4, 2))
print("hex", run.hex(), bytearray(b"\x00\xff").hex(" ", 1))
print("index", run.index(b"W"), run.index(b"o", 5))
print("isalnum", bytearray(b"abc123").isalnum(), bytearray(b"abc").isalnum())
print("isalpha", bytearray(b"abc").isalpha(), bytearray(b"ab1").isalpha())
print("isascii", bytearray(b"abc").isascii(), bytearray(b"\xe9").isascii())
print("isdigit", bytearray(b"123").isdigit(), bytearray(b"12a").isdigit())
print("islower", bytearray(b"abc").islower(), bytearray(b"Abc").islower())
print("isspace", bytearray(b" \t\n").isspace(), bytearray(b" a").isspace())
print("istitle", bytearray(b"Hello World").istitle(), bytearray(b"Hello world").istitle())
print("isupper", bytearray(b"ABC").isupper(), bytearray(b"ABc").isupper())
print("join", bytearray(b"-").join([b"a", b"b"]), type(bytearray(b"-").join([])).__name__)
print("join-bytearray-items", bytearray(b"-").join([bytearray(b"a"), bytearray(b"b")]))
print("ljust", bytearray(b"ab").ljust(5), bytearray(b"ab").ljust(5, b"."))
print("lower", bytearray(b"HeLLo").lower())
print("lstrip", bytearray(b"  ab  ").lstrip(), bytearray(b"xxab").lstrip(b"x"))
print("partition", bytearray(b"a-b-c").partition(b"-"))
print("partition-missing", bytearray(b"abc").partition(b"-"))
print("replace", run.replace(b"l", b"L"), run.replace(b"l", b"L", 2), run.replace(b"", b"."))
print("removeprefix", bytearray(b"abcd").removeprefix(b"ab"))
print("removeprefix-missing", bytearray(b"abcd").removeprefix(b"xy"))
print("removeprefix-empty", bytearray(b"abcd").removeprefix(b""))
print("removesuffix", bytearray(b"abcd").removesuffix(b"cd"))
print("removesuffix-empty", bytearray(b"abcd").removesuffix(b""))
print("rfind", run.rfind(b"o"), run.rfind(b"o", 0, 7), run.rfind(b"z"))
print("rindex", run.rindex(b"o"))
print("rjust", bytearray(b"ab").rjust(5), bytearray(b"ab").rjust(5, b"."))
print("rpartition", bytearray(b"a-b-c").rpartition(b"-"))
print("rpartition-missing", bytearray(b"abc").rpartition(b"-"))
print("rsplit", run.rsplit(None, 1), run.rsplit(b"l"))
print("rstrip", bytearray(b"  ab  ").rstrip(), bytearray(b"abxx").rstrip(b"x"))
print("split", run.split(), run.split(None, 1), run.split(b"o"))
attempt("split-empty-sep", lambda: run.split(b""))
print("splitlines", bytearray(b"a\r\nb\nc").splitlines())
print("splitlines-keepends", bytearray(b"a\r\nb\nc").splitlines(True))
print("startswith", run.startswith(b"He"), run.startswith((b"x", b"He")))
print("startswith-bytearray", run.startswith(bytearray(b"He")))
print("strip", bytearray(b"  ab  ").strip(), bytearray(b"xxabxx").strip(b"x"))
print("swapcase", bytearray(b"HeLLo").swapcase())
print("title", bytearray(b"hello world").title())
print("translate", bytearray(b"hello").translate(bytearray.maketrans(b"lo", b"01")))
print("translate-delete", bytearray(b"hello").translate(None, b"l"))
print("upper", bytearray(b"hello").upper())
print("upper-non-ascii", bytearray(b"\xe9").upper())
print("zfill", bytearray(b"42").zfill(5), bytearray(b"-42").zfill(6), bytearray(b"+42").zfill(5))
print("zfill-negative", bytearray(b"-42").zfill(2))
print("fromhex", bytearray.fromhex("61 62"), bytearray.fromhex("6162"))
print("fromhex-bytes-arg", bytearray.fromhex(b"61 62"))
print("maketrans", bytearray.maketrans(b"ab", b"01"), type(bytearray.maketrans(b"ab", b"01")).__name__)

print("method-type", type(bytearray.fromhex).__name__, type(bytearray.maketrans).__name__)
print("fromhex-on-instance", bytearray(b"ab").fromhex("61"))
print("maketrans-on-instance", type(bytearray(b"ab").maketrans(b"a", b"b")))

translated = bytearray(b"hello").translate(bytearray.maketrans(b"l", b"L"))
print("translate-copy", translated, translated is run)

pieces = bytearray(b"a b").split()
pieces[0][0] = 90
print("split-fresh", pieces, bytearray(b"a b").split())

attempt("capitalize-argument", lambda: bytearray(b"ab").capitalize(1))
attempt("upper-argument", lambda: bytearray(b"ab").upper(1))
attempt("isascii-keyword", lambda: bytearray(b"ab").isascii(x=1))
attempt("center-fill-long", lambda: bytearray(b"ab").center(4, b"xy"))
attempt("center-fill-empty", lambda: bytearray(b"ab").center(4, b""))
attempt("center-fill-int", lambda: bytearray(b"ab").center(4, 1))
attempt("ljust-wide", lambda: bytearray(b"ab").ljust(2 ** 100, b"."))
attempt("zfill-wide", lambda: bytearray(b"ab").zfill(2 ** 100))
attempt("expandtabs-negative", lambda: bytearray(b"a\tb").expandtabs(-1))
attempt("expandtabs-text", lambda: bytearray(b"a\tb").expandtabs("4"))
attempt("count-int", lambda: bytearray(b"ab").count(1))
attempt("find-index-missing", lambda: bytearray(b"abc").index(b"z"))
attempt("rindex-missing", lambda: bytearray(b"abc").rindex(b"z"))
attempt("find-float-start", lambda: bytearray(b"abc").find(b"a", 1.5))
attempt("count-index-class", lambda: bytearray(b"abc").count(b"a", Rune()))
attempt("join-text", lambda: bytearray(b"-").join([1]))
attempt("join-bytes-separator-item", lambda: bytearray(b"-").join([b"a", "b"]))
attempt("join-not-iterable", lambda: bytearray(b"-").join(1))
attempt("replace-int", lambda: bytearray(b"ab").replace(97, b"x"))
attempt("removeprefix-int", lambda: bytearray(b"ab").removeprefix(1))
attempt("split-int", lambda: bytearray(b"ab").split(1))
attempt("split-two-args", lambda: bytearray(b"a b").split(b" ", 1, 2))
attempt("split-keyword", lambda: bytearray(b"a b").split(sep=b" ", maxsplit=1))
attempt("splitlines-text", lambda: bytearray(b"ab").splitlines("yes"))
attempt("startswith-int", lambda: bytearray(b"ab").startswith(1))
attempt("startswith-float-tuple", lambda: bytearray(b"ab").startswith((1.5,)))
attempt("startswith-text", lambda: bytearray(b"ab").startswith("a"))
attempt("hex-negative", lambda: bytearray(b"ab").hex(-1))
attempt("hex-zero", lambda: bytearray(b"ab").hex(0))
attempt("hex-two-args", lambda: bytearray(b"ab").hex(" ", 1, 2))
attempt("hex-text-sep", lambda: bytearray(b"ab").hex(1))
attempt("decode-unknown", lambda: bytearray(b"ab").decode("nope"))
attempt("decode-three-args", lambda: bytearray(b"ab").decode("utf-8", "strict", 1))
attempt("decode-bad-bytes", lambda: bytearray(b"\xff").decode("utf-8"))
attempt("fromhex-space", lambda: bytearray.fromhex("61  62"))
attempt("fromhex-odd", lambda: bytearray.fromhex("616"))
attempt("fromhex-bad", lambda: bytearray.fromhex("zz"))
attempt("fromhex-int", lambda: bytearray.fromhex(1))
attempt("fromhex-keyword", lambda: bytearray.fromhex(string="61"))
attempt("fromhex-two-args", lambda: bytearray.fromhex("61", "62"))
attempt("maketrans-unequal", lambda: bytearray.maketrans(b"ab", b"0"))
attempt("maketrans-int", lambda: bytearray.maketrans(1, 2))
attempt("maketrans-keyword", lambda: bytearray.maketrans(frm=b"a", to=b"b"))
attempt("translate-bad-table", lambda: bytearray(b"ab").translate(bytearray(b"abc")))
attempt("translate-text-table", lambda: bytearray(b"ab").translate("x"))
attempt("translate-int", lambda: bytearray(b"ab").translate(1))

for name in (
    "capitalize",
    "center",
    "count",
    "decode",
    "endswith",
    "expandtabs",
    "find",
    "hex",
    "index",
    "isalnum",
    "isalpha",
    "isascii",
    "isdigit",
    "islower",
    "isspace",
    "istitle",
    "isupper",
    "join",
    "ljust",
    "lower",
    "lstrip",
    "partition",
    "replace",
    "removeprefix",
    "removesuffix",
    "rfind",
    "rindex",
    "rjust",
    "rpartition",
    "rsplit",
    "rstrip",
    "split",
    "splitlines",
    "startswith",
    "strip",
    "swapcase",
    "title",
    "translate",
    "upper",
    "zfill",
    "fromhex",
    "maketrans",
):
    member = getattr(bytearray, name)
    print("member", name, member.__text_signature__, repr(member.__doc__))
