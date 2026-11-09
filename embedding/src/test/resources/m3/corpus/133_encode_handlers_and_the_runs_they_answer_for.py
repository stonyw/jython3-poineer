"""The two names a codec is asked for, and the run of characters each answer covers.

`str.encode` looks like one call that turns text into octets and is really three questions asked in order:
the parser's converters read the two arguments, the registry resolves the encoding name, and the error
handler is looked up only once some character has actually failed to encode. The last of those is the one a
program guesses wrong. `handler not reached` reads `b'x'` on both faces of the call — `nope` is a name the
registry has never heard and nothing asked it — while the same name one character later answers `LookupError:
unknown error handler name 'nope'`, and the case and the spaces of a name are kept, so `"IGNORE"` and
`" ignore "` are refused for what they are rather than folded into `ignore`.

Both converters name their own parameter, which is what `_PyArg_BadArgument` is for: `encode() argument
'errors' must be str, not function`, `bytes() argument 'encoding' must be str, not bytes`. Neither one is a
`str()` conversion in disguise — a `Marker` with a `__str__` that returns `'utf-8'` is refused by its own
class name rather than asked — and both refuse an embedded null as `ValueError: embedded null character`
before the registry has seen the name. `None` is called `None`, not `NoneType`, which is the half an
implementation that formats a type name gets wrong; the four refusals of the calling convention are the
count family's (`encode() takes at most 2 arguments (3 given)`, `bytes() takes at most 3 arguments (4
given)`) and the keyword family's (`... got an unexpected keyword argument 'enc'`).

The name a program writes is not the name the registry files. `_Py_normalize_encoding` lowercases, keeps
letters, digits and the dot, and turns every *run* of anything else into a single underscore that never
leads. So `ISO_8859-1`, `iso-8859-1`, `8859` and `L1` all reach the same entry, and `ANSI_X3.4-1968` keeps
its two dots and is found in the alias table by that spelling — while `u-t-f8` normalizes to `u_t_f8`, which
is nobody's module name. That is the difference between a separator and a hyphen: `utf-8` works because its
one run of non-letters is one underscore, and `u-t-f8` fails because its two runs are two. The 21 names of
`the names the registry answers` fold onto the seven codecs this runtime files; three of the 21 — `u-t-f8`,
`nope` and the empty name — are the three it refuses.

The 200 rows of the grid are five texts against five codecs against eight handler names, and the columns
separate on what a handler is asked to do rather than on what it is called. `strict` is the one that answers
in the codec's words, `ignore` writes nothing, `replace` writes one `?` per character, `backslashreplace`
and `xmlcharrefreplace` write a *text* that is then encoded again through the same codec — which is why
`\\U0001f600` survives as ten ASCII characters where `&#128512;` survives as nine, and why in utf-16-be the
escape arrives one unit per character: `b'\\x00\\\\\\x00u\\x00d\\x008\\x000\\x000\\x00b'`. The 15 `nope` rows that
refuse and the 10 that answer octets are the same fact seen twice: the handler is reached only by a character
the codec cannot write.

What a codec cannot write is a run, and the run is what the sentence says. `ascii` and `latin-1` collect every
successive character over their limit — `0x80` and `0x100` — and `utf-8` collects every successive surrogate,
so eight rows of the grid answer `can't encode characters in position a-b` while the rest of the file's 46
codec refusals name one character. Counted over the whole file, 35 of them read `ordinal not in range(128)` or
`ordinal not in range(256)` and 11 read `surrogates not allowed`, and all 11 are in the grid: the two families
are what separates a codec that fails on a value from one that fails on a code point. The utf-16 family
collects nothing, so it refuses one character at a time and its `replace` row writes `?` for a single unit.
`surrogateescape` is the handler with a rule of its own: it can only turn `\\udc80` through `\\udcff` into the
octet `0x80` through `0xff`, so it escapes a run up to the first character outside that window and re-raises
from there — `escape then refuse` names the `\\xe9` that stopped it and `escape mid run` names the `\\udc7f` —
and on utf-8, where a non-ASCII character is perfectly encodable, the same handler writes `b'a\\x80\\xc3\\xa9b'`
without refusing anything at all. `surrogatepass` writes a surrogate as its own three-byte utf-8 form and
refuses on the codecs that are not utf, which is the pair of answers programs that mix the two handlers get
wrong.

`utf-16` and `utf-8-sig` write a prefix, and the prefix is not part of the text: an empty text encodes to
`b'\\xff\\xfe'` and `b'\\xef\\xbb\\xbf'`, and `ignore` on `a\\udc80b` still opens with the byte-order mark before it
drops the surrogate. `utf-16-be` and `utf-16-le` are the explicit-endian forms and mark nothing, so the empty
text is the empty answer.

`bytes` is the same two converters in front of a different first question. `bytes_new_impl` asks about the
*string* before it asks about anything else: `bytes(5, "ascii")` is refused as `encoding without a string
argument` because the count five is not text, `bytes("abc", errors="ignore")` as `string argument without an
encoding` because text with only an errors name cannot be encoded, and `bytes(encoding="a", errors="b")` as
the first of the two again. Give it the encoding and the handler and the call is `str.encode` under another
name, which is why `plain`, `named` and `surrogateescape` here read exactly like their three neighbours in
the grid.

The last 12 rows are the reason the escape table lives outside both calls. `ascii(x)` is `repr(x)` walked
through `backslashreplace`, and `repr(x).encode("ascii", "backslashreplace").decode("ascii")` is the same
walk written out; the two answers are the same text for every one of the six values, including the `\\t` the
repr had already escaped and left alone the second time.

Every refusal above is one the reference makes too. What this runtime does not have is left unmeasured on
purpose: `namereplace` would need the Unicode name database, `utf-32` and `cp1252` and `unicode_escape` would
need the codec modules behind those names, and reading octets back shares this name table but not these
handlers — none of the three is asked for here, and the only rows that reach across to `decode` are the two
that prove the table of names is one table.
"""

import sys

out = sys.__stdout__


def show(label, fn):
    try:
        out.write("%s -> %r\n" % (label, fn()))
    except BaseException as exc:
        out.write("%s -> %s: %s\n" % (label, type(exc).__name__, exc))


TEXTS = ["caf\xe9", "caf\xe9 \u65e5\u672c", "a\udc80\udc81b", "a\ud800b", "\U0001f600"]
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
for text in TEXTS:
    for encoding in ["ascii", "latin-1", "utf-8", "utf-16", "utf-16-be"]:
        for handler in HANDLERS:
            show(
                "%r %s %s" % (text, encoding, handler),
                lambda t=text, e=encoding, h=handler: t.encode(e, h),
            )

out.write("\n== the names the registry answers ==\n")
for name in [
    "utf",
    "U8",
    "utf_8",
    "UTF-8",
    "u-t-f8",
    "latin",
    "L1",
    "8859",
    "ISO_8859-1",
    "iso-8859-1",
    "ANSI_X3.4-1968",
    "cp367",
    "us-ascii",
    "ascii",
    "u16",
    "UTF-16LE",
    "unicodebigunmarked",
    "utf_8_sig",
    "utf-8-sig",
    "nope",
    "",
]:
    show("%s -> text" % name, lambda n=name: "\xe9".encode(n))

show("same table for octets", lambda: b"caf\xe9".decode("L1"))
show("same refusal for octets", lambda: b"x".decode("u-t-f8"))

out.write("\n== the argument face ==\n")
show("no args", lambda: "\xe9".encode())
show("errors only named", lambda: "\xe9".encode(errors="ignore"))
show("both named", lambda: "\xe9".encode(encoding="ascii", errors="ignore"))
show("encoding None", lambda: "\xe9".encode(None))
show("errors None", lambda: "\xe9".encode("ascii", None))
show("errors int", lambda: "\xe9".encode("ascii", 7))
show("errors function", lambda: "\xe9".encode("ascii", show))
show("three positional", lambda: "\xe9".encode("ascii", "ignore", "x"))
show("bad keyword", lambda: "\xe9".encode(enc="ascii"))
show("encoding with a null", lambda: "\xe9".encode("as\x00ci"))
show("errors with a null", lambda: "\xe9".encode("ascii", "ign\x00re"))
show("handler case", lambda: "\xe9".encode("ascii", "IGNORE"))
show("handler spaces", lambda: "\xe9".encode("ascii", " ignore "))
show("handler not reached", lambda: "x".encode("ascii", "nope"))
show("utf16le", lambda: "\xe9".encode("utf-16-le"))
show("utf8sig", lambda: "\xe9".encode("utf-8-sig"))


class Marker:
    def __str__(self):
        return "utf-8"


out.write("\n== bytes asks the same questions ==\n")
show("plain", lambda: bytes("caf\xe9", "ascii", "ignore"))
show("named", lambda: bytes("caf\xe9", encoding="ascii", errors="replace"))
show("surrogateescape", lambda: bytes("a\udc80b", "utf-8", "surrogateescape"))
show("utf16 surrogatepass", lambda: bytes("a\udc80b", "utf-16", "surrogatepass"))
show("bad handler", lambda: bytes("\xe9", "ascii", "nope"))
show("handler not reached", lambda: bytes("x", "ascii", "nope"))
show("alias", lambda: bytes("\xe9", "L1"))
show("dot name", lambda: bytes("\xe9", "ANSI_X3.4-1968"))
show("no separator", lambda: bytes("\xe9", "u-t-f8"))
show("None encoding", lambda: bytes("x", None))
show("int encoding", lambda: bytes("x", 7))
show("bytes encoding", lambda: bytes("x", b"ascii"))
show("None errors", lambda: bytes("\xe9", "ascii", None))
show("int errors", lambda: bytes("\xe9", "ascii", 7))
show("encoding with a null", lambda: bytes("x", "as\x00ci"))
show("errors with a null", lambda: bytes("x", "ascii", "ign\x00re"))
show("four args", lambda: bytes("x", "ascii", "strict", 1))
show("bad keyword", lambda: bytes("x", enc="ascii"))
show("encoding without a string", lambda: bytes(5, "ascii"))
show("encoding alone named", lambda: bytes(encoding="ascii"))
show("errors alone named", lambda: bytes("abc", errors="ignore"))
show("both named no string", lambda: bytes(encoding="a", errors="b"))
show("str __str__ not asked", lambda: bytes("\xe9", Marker(), "ignore"))
show("no args", lambda: bytes())
show("int count", lambda: bytes(3))
show("from iterable", lambda: bytes([104, 105]))

out.write("\n== the empty text and the prefix it still writes ==\n")
show("empty utf-16", lambda: "".encode("utf-16"))
show("empty utf-8-sig", lambda: "".encode("utf-8-sig"))
show("empty utf-16-be", lambda: "".encode("utf-16-be"))
show("bom then ignore", lambda: "a\udc80b".encode("utf-16", "ignore"))
show("bom then surrogatepass", lambda: "\ud800".encode("utf-8-sig", "surrogatepass"))
show("bom then ignore hi", lambda: "\ud800".encode("utf-8-sig", "ignore"))

out.write("\n== the run a collecting handler stops at ==\n")
show("two then gap", lambda: "caf\xe9 \u65e5\u672cx".encode("ascii"))
show("one run", lambda: "caf\xe9x".encode("ascii"))
show("all of it", lambda: "\u65e5\u672c\U0001f600".encode("ascii", "replace"))
show("backslash per char", lambda: "caf\xe9x".encode("ascii", "backslashreplace"))
show("names per char", lambda: "\u65e5\u672c".encode("ascii", "xmlcharrefreplace"))
show("escape outside range", lambda: "\udc7f".encode("ascii", "surrogateescape"))
show("escape then refuse", lambda: "a\udc80\udc81\xe9b".encode("ascii", "surrogateescape"))
show("escape mid run", lambda: "\udc80\udc7f".encode("ascii", "surrogateescape"))
show("escape utf8", lambda: "a\udc80\udcffb".encode("utf-8", "surrogateescape"))
show("escape then refuse utf8", lambda: "a\udc80\xe9b".encode("utf-8", "surrogateescape"))
show("surrogatepass refuses a run", lambda: "a\udc80\udc81b".encode("utf-8", "surrogatepass"))
show("surrogatepass writes one", lambda: "a\udc80b".encode("utf-16", "surrogatepass"))
show("replace in utf-16", lambda: "\ud800b".encode("utf-16", "replace"))
show("backslash in utf-16-be", lambda: "\ud800b".encode("utf-16-be", "backslashreplace"))

out.write("\n== ascii walks the same table ==\n")
for value in ["\xe9", "\U0001f600", "\t", "\xa0", b"\xe9", 7]:
    show("ascii(%r)" % (value,), lambda v=value: ascii(v))
    show(
        "repr encoded",
        lambda v=value: repr(v).encode("ascii", "backslashreplace").decode("ascii"),
    )
