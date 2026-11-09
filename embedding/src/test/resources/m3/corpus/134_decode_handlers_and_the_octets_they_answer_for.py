"""The octets a decode walk gives up on, and the position its answer sends the walk back to.

`bytes.decode` looks like one call that turns octets into text and is really the same three questions
`str.encode` asks, in the same order: two Clinic converters read the arguments, the registry resolves the
encoding name, and the error handler is looked up only once an octet has actually failed to decode. The last
of those is the one a program guesses wrong. `handler not reached` reads `'x'` — `nope` is a name the
registry has never heard and nothing asked it — while the same name one octet later answers `LookupError:
unknown error handler name 'nope'`, and the case and the spaces of a name are kept, so `"IGNORE"` and
`" ignore "` are refused for what they are rather than folded into `ignore`. The empty run asks neither
question: `b"".decode("nope")`, `b"".decode("utf-8", "nope")` and `b"".decode("utf-32")` are all `''`, and
the name that decides them is one octet long. A codec that never refuses is the third way out of the
question — the 256-octet run read as latin-1 answers all 256 characters with `nope` as its handler, because
there is no octet latin-1 cannot read.

What a refusal covers is a range of *octets*, one to three of them, and its width comes from the shape of the
sequence the decoder was in the middle of. 72 of this file's 555 rows end in a `UnicodeDecodeError` sentence;
55 of them name one octet, 15 name two and 2 name three. That is the difference from the encode half, where
`ascii` collects every successive character over its limit into one range: `b"\\x80\\x81\\x82"` is three
refusals of one octet each where the same three values were one run of three characters when writing them, and
the only two rows in the file that read `can't decode bytes in position 0-2` are both a sequence that ran out
of input — a four-octet UTF-8 sequence three of whose octets arrived, a UTF-16-BE high surrogate with one
spare byte behind it.

Which octets those are is a table the lead byte picks. An octet no family owns — a continuation byte where a
lead belongs, `0xC0`, `0xC1`, anything from `0xF5` up — is refused as its own single octet, `invalid start
byte`. An octet that fails the second or third place of a sequence is refused with the lead in the range and
the offender left out, which is why the sentence names the *lead* (`can't decode byte 0xe0`) and why
`invalid continuation byte` therefore covers one octet for a second-place failure and two for a
third-place one. A sequence that runs out of input is refused by everything that did arrive. The 4-byte family
judges the second octet before it notices the run is short, and that order is what makes `b"\\xf0\\x00"`,
`b"\\xf0\\x00\\x00"` and `b"\\xf0\\x80\\x80"` all name one octet: three of a four-octet sequence look like they
should say `unexpected end of data`, and only the ones whose second octet survives the `0x90`/`0xF4` test get
to. `invalid start byte` appears 7 times and `invalid continuation byte` 7 times in these rows, `unexpected
end of data` 15.

UTF-16 asks four questions and the order it asks them in is what the 180 rows of `the four questions utf-16
asks` read. 39 of them refuse: 26 `truncated data`, 6 `unexpected end of data`, 5 `illegal UTF-16 surrogate`,
2 `illegal encoding`. A low surrogate is refused as `illegal encoding` for its own two octets *before* the
walk looks at anything behind it. A high surrogate with fewer than two octets behind it is `unexpected end of
data`, and its range reaches to the true end rather than to the end of the surrogate — `b"\\xd8\\x00\\x00"`
read big-endian says `position 0-2`, which is one of the file's two three-octet ranges. A high surrogate with
a unit behind it that is not a low one is `illegal UTF-16 surrogate` covering the high unit only, so the unit
behind it is read again after the handler answers: `b"\\xd8\\x00\\x00a".decode("utf-16-be", "ignore")` is
`'a'`. `truncated data` is the leftover odd octet at the end, and the only refusal whose range can be
*sized* rather than shaped — the walk stops because there is one octet left, not because an octet was wrong.

The name in the sentence is not always the name in the call. `utf-16` negotiates an order — a `0xFF 0xFE`
mark reads little-endian, `0xFE 0xFF` big-endian, and a run with no mark uses the order the machine stores
words in — and every refusal after that names the order it settled on, `'utf-16-le'` here. `utf-8-sig` takes
three octets off the front when they are the mark, and the run it complains about is the run after them:
`b'\\xef\\xbb\\xbfa\\xe9'` says `position 1` and its `object` is `b'a\\xe9'`: the four fields the error carries
describe a run the caller never passed in. A prefix shorter than three octets is not a mark, so `b"\\xef\\xbb"`
is read as the two octets it is.

The handlers answer the same five ways they do when encoding, except that everything is measured in octets
and one answer is not per-octet: `ignore` writes nothing, `replace` writes *one* `\\ufffd` for the whole range
where the encoder writes one `?` per character, `backslashreplace` writes `\\x..` per octet. `surrogateescape`
escapes up to four octets from the front of the range, stops at the first ASCII octet because an ASCII octet
has no surrogate to stand for it, and resumes the walk where it stopped — which is *inside* the range it was
given, so `b"\\xdc\\x00"` read big-endian escapes `0xdc`, resumes at octet 1, and is refused again for the odd
octet left there. When the range opens below `0x80` the handler escapes nothing and declines, and the codec's
own refusal comes back unchanged: `b"\\x00\\xd8a\\x00"` with `surrogateescape` says `illegal UTF-16 surrogate`
at position 0-1, the same sentence `strict` says. `surrogatepass` is the handler that reads the codec's name
first: `get_standard_encoding` knows `utf-8`, `utf-16-le` and `utf-16-be` and nobody else, and it reads one
unit of that codec's width at the position complained about. So a range wider than the unit is answered a unit
at a time and the rest of it comes back: `b"\\xd8\\x00\\x00"` says 0-2, the handler writes the surrogate for
the first two octets, and the odd octet left at position 2 is refused again as `truncated data` — while the
same three octets with an `a` behind them, whose refusal is only the unit itself, answer `'\\ud800a'`.

The two remaining names have nothing to say about octets. `xmlcharrefreplace` and `namereplace` substitute a
*character reference* for a character the codec cannot write; a range of octets that will not decode contains
no character, so both answer `TypeError: don't know how to handle UnicodeDecodeError in error callback` —
`wrong_exception_type`, reached by 6 of the 11 rows here and by 8 of the grid's 200.

A name the registry does not file is complained about with at most 400 characters of itself: a handler named
with 405 `x` is refused in a sentence 429 characters long, `%.400s` having cut five of them. The encoding
name carries no such cut — the same length as an encoding answers 423 characters, all 405 of the name
included.

`str(b, encoding, errors)` reaches the same decoder through the third constructor's own parser: 11 rows here,
and the six that decode read like their neighbours in the grid. Its refusals about names are worded from
`str()`'s argument list rather than `decode()`'s, and an empty run of octets answers `''` before either name
is resolved there too.

The last 27 rows are the two halves agreeing. Of the 25 texts read back through themselves, 6 never reach the
decoder at all because the *encoder* refuses first, and the other 21 answer `True`; `surrogateescape` and
`surrogatepass` round-trip the octets and the surrogate they stand for, which is the pair of facts the two
handlers exist to make possible. Every one of the file's 24 rows that answer a boolean answers `True`.

What this runtime does not have is left unmeasured on purpose: the incremental decoders, which report how much
of a run they consumed instead of refusing the tail of it; the codecs behind `utf-32`, `cp1252` and
`unicode_escape`; the one-octet `bytes` objects CPython keeps interned, which is what makes a reference say
`exc.object is data` for a single octet and not for two; and `str()`'s own `None` wording, which differs
between a positional and a keyword argument. None of the four is asked for here.
"""

import sys

out = sys.__stdout__


def show(label, fn):
    try:
        out.write("%s -> %r\n" % (label, fn()))
    except BaseException as exc:
        out.write("%s -> %s: %s\n" % (label, type(exc).__name__, exc))


def refused(fn):
    """The refusal one call makes, or nothing when it makes none."""
    try:
        fn()
    except UnicodeDecodeError as exc:
        return exc
    return None


def lookup_of(fn):
    """How long the registry's own sentence about a bad name reads."""
    try:
        fn()
    except LookupError as exc:
        return len(str(exc))
    return "no refusal"


def fields(octets, encoding, handler="strict"):
    exc = refused(lambda: octets.decode(encoding, handler))
    if exc is None:
        return "no refusal"
    return (
        exc.encoding,
        exc.object,
        exc.start,
        exc.end,
        exc.reason,
        exc.args,
        exc.object is octets,
    )


SAMPLES = [b"caf\xe9", b"caf\xe9 \xe6\x97\xa5\xe6\x9c\xac", b"a\x80\x81b", b"\x00a", b"ab"]
HANDLERS = [
    "strict",
    "ignore",
    "replace",
    "backslashreplace",
    "xmlcharrefreplace",
    "surrogateescape",
    "surrogatepass",
    "nope",
]

out.write("== the grid ==\n")
for octets in SAMPLES:
    for encoding in ["ascii", "latin-1", "utf-8", "utf-16", "utf-16-be"]:
        for handler in HANDLERS:
            show(
                "%r %s %s" % (octets, encoding, handler),
                lambda o=octets, e=encoding, h=handler: o.decode(e, h),
            )

out.write("\n== which octets a refusal covers ==\n")
for octets in [
    b"\x80",
    b"\x80\x81\x82",
    b"\xc3",
    b"\xc3\xa9e",
    b"\xe0\xa0",
    b"\xe0\x80\x80",
    b"\xe0\xa0\x80",
    b"\xed\xa0\x80",
    b"\xef\xbf\xbf",
    b"\xf0\x9f\x92\xa9",
    b"\xf0\x9f\x92",
    b"\xf0\x9f",
    b"\xf0",
    b"\xf0\x80\x80",
    b"\xf4\x8f\xbf\xbf",
    b"\xf4\x90\x80\x80",
    b"\xc0\x80",
    b"\xf5\xff",
]:
    show("strict %r" % (octets,), lambda o=octets: o.decode("utf-8"))
    show("ignore %r" % (octets,), lambda o=octets: o.decode("utf-8", "ignore"))
    show("replace %r" % (octets,), lambda o=octets: o.decode("utf-8", "replace"))
    show(
        "backslash %r" % (octets,),
        lambda o=octets: o.decode("utf-8", "backslashreplace"),
    )

out.write("\n== the four questions utf-16 asks ==\n")
for octets in [
    b"\x00",
    b"\xdc\x00",
    b"\xdc\x00\xd8\x00",
    b"\xd8\x00",
    b"\xd8\x00\x00",
    b"\xd8\x00\x00a",
    b"\xd8\x00\xdc\x00",
    b"\x00\xd8a\x00",
    b"\x00\xd8",
    b"\xff\xfeA\x00B\x00",
    b"\xfe\xff\x00A\x00B",
    b"\xff",
]:
    for encoding in ["utf-16", "utf-16-be", "utf-16-le"]:
        for handler in [None, "ignore", "replace", "surrogateescape", "surrogatepass"]:
            show(
                "%r %s %s" % (octets, encoding, handler),
                lambda o=octets, e=encoding, h=handler: (
                    o.decode(e) if h is None else o.decode(e, h)
                ),
            )

out.write("\n== the mark is not part of the run ==\n")
for octets in [
    b"\xef\xbb\xbfab",
    b"\xef\xbb\xbfa\xe9",
    b"\xef\xbb",
    b"\xefAA",
    b"\xef\xbb\xbf\xa9",
]:
    show("sig %r" % (octets,), lambda o=octets: o.decode("utf-8-sig"))
    show("sig fields %r" % (octets,), lambda o=octets: fields(o, "utf-8-sig"))

out.write("\n== the fields one refusal carries ==\n")
for octets, encoding in [
    (b"caf\xe9", "utf-8"),
    (b"caf\xe9", "ascii"),
    (b"caf\xe9", "utf-16"),
    (b"\x80", "latin-1"),
    (b"a\x80b", "ascii"),
    (b"\xd8\x00", "utf-16-be"),
    (b"\x00\xd8", "utf-16-le"),
    (b"\xf0\x80\x80", "utf-8"),
]:
    show("fields %r %s" % (octets, encoding), lambda o=octets, e=encoding: fields(o, e))

exc = refused(lambda: b"caf\xe9".decode("utf-8"))
show("type", lambda: type(exc).__name__)
show("mro", lambda: [c.__name__ for c in type(exc).__mro__])
show("str", lambda: str(exc))
show("len args", lambda: len(exc.args))
show("isinstance ValueError", lambda: isinstance(exc, ValueError))
show("isinstance UnicodeError", lambda: isinstance(exc, UnicodeError))
show("args unpack", lambda: UnicodeDecodeError(*exc.args))
show("raise the same", lambda: str(UnicodeDecodeError("utf-8", b"caf\xe9", 3, 4, "x")))
show("bad start", lambda: str(UnicodeDecodeError("utf-8", b"caf\xe9", 9, 4, "x")))
show("end before start", lambda: str(UnicodeDecodeError("utf-8", b"caf\xe9", 3, 2, "x")))

out.write("\n== the argument face ==\n")
show("no args", lambda: b"caf\xc3\xa9".decode())
show("errors only named", lambda: b"\x80".decode(errors="ignore"))
show("both named", lambda: b"\x80".decode(encoding="ascii", errors="ignore"))
show("encoding None", lambda: b"x".decode(None))
show("errors None", lambda: b"x".decode("ascii", None))
show("errors int", lambda: b"x".decode("ascii", 7))
show("errors function", lambda: b"x".decode("ascii", show))
show("three positional", lambda: b"x".decode("ascii", "ignore", "x"))
show("bad keyword", lambda: b"x".decode(enc="ascii"))
show("encoding with a null", lambda: b"x".decode("as\x00ci"))
show("errors with a null", lambda: b"x".decode("ascii", "ign\x00re"))
show("handler case", lambda: b"\x80".decode("ascii", "IGNORE"))
show("handler spaces", lambda: b"\x80".decode("ascii", " ignore "))
show("handler not reached", lambda: b"x".decode("ascii", "nope"))
show("handler reached", lambda: b"\x80".decode("ascii", "nope"))
show("utf16le", lambda: b"\xff\xfeA\x00".decode("utf-16-le"))
show("utf8sig", lambda: b"\xef\xbb\xbfA".decode("utf-8-sig"))


class Marker:
    def __str__(self):
        return "utf-8"


show("marker encoding", lambda: b"x".decode(Marker()))
show("marker errors", lambda: b"x".decode("ascii", Marker()))

out.write("\n== the two names with nothing to say ==\n")
for handler in ["xmlcharrefreplace", "namereplace"]:
    for octets in [b"\x80", b"caf\xe9", b"\xd8\x00\x00", b"ab"]:
        show("%r %s" % (octets, handler), lambda o=octets, h=handler: o.decode("utf-8", h))
show("message length", lambda: len(str(refused(lambda: b"\x80".decode("ascii", "nope")))))
show(
    "long handler clipped",
    lambda: lookup_of(lambda: b"\x80".decode("ascii", "x" * 405)),
)
show(
    "long encoding not clipped",
    lambda: lookup_of(lambda: b"\x80".decode("y" * 405)),
)

out.write("\n== str(b, encoding, errors) is the same call ==\n")
show("no args", lambda: str(b"caf\xc3\xa9", "utf-8"))
show("named", lambda: str(b"caf\xc3\xa9", encoding="utf-8", errors="strict"))
show("ignore", lambda: str(b"caf\xe9", "utf-8", "ignore"))
show("replace", lambda: str(b"caf\xe9", "utf-8", "replace"))
show("surrogateescape", lambda: str(b"a\x80\x81b", "utf-8", "surrogateescape"))
show("empty asks nothing", lambda: str(b"", "nope", "nope2"))
show("bad encoding", lambda: str(b"x", "nope"))
show("int encoding", lambda: str(b"x", 7))
show("int first", lambda: str(1, "utf-8"))
show("four args", lambda: str(b"x", "ascii", "strict", 1))
show("bad keyword", lambda: str(b"x", enc="ascii"))

out.write("\n== what the empty run asks nothing ==\n")
show("empty bad encoding", lambda: b"".decode("nope"))
show("empty bad handler", lambda: b"".decode("utf-8", "nope"))
show("empty utf-32", lambda: b"".decode("utf-32"))
show("empty surrogateescape", lambda: b"".decode("utf-16", "surrogateescape"))
show("one octet bad encoding", lambda: b"x".decode("nope"))
show("one octet bad handler", lambda: b"x".decode("utf-16", "nope"))
show(
    "latin-1 never refuses",
    lambda: bytes(range(256)).decode("latin-1", "nope") == "".join(
        chr(n) for n in range(256)
    ),
)

out.write("\n== the round trip both halves agree on ==\n")
for text in ["caf\xe9", "\u65e5\u672c", "\U0001f600", "\x00a\x7f", "\xa0"]:
    for encoding in ["ascii", "latin-1", "utf-8", "utf-16", "utf-8-sig"]:
        show(
            "%r through %s" % (text, encoding),
            lambda t=text, e=encoding: t.encode(e).decode(e) == t,
        )
show(
    "escape and back",
    lambda: "a\x80b".encode("utf-8", "surrogateescape").decode(
        "utf-8", "surrogateescape"
    )
    == "a\x80b",
)
show(
    "pass and back",
    lambda: "\ud800".encode("utf-8", "surrogatepass").decode(
        "utf-8", "surrogatepass"
    )
    == "\ud800",
)
