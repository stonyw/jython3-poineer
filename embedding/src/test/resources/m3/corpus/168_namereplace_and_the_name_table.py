"""`namereplace` on both faces, and the Unicode name table behind them.

`namereplace` is the eighth handler the registry files, and the only one that needs more than the character's
number: it answers the text `\\N{NAME}` for a character whose name it knows and the hex escape
`\\uxxxx`/`\\Uxxxxxxxx` for one it does not, then hands that text back to the codec the way `replace` and
`backslashreplace` do. So the answer's width is the codec's, not the name's: `utf-16` writes two octets per
character of `\\N{...}` and `utf-32` four, while `utf-8` writes the name as UTF-8. The table is exported
from the reference's own `unicodedata` (Unicode 16.0.0), and this file measures that export's shape rather
than transcribing names: the primary names, the formal aliases, the four algorithmic families, and the one
family `name()` refuses to spell but `lookup` knows (Tangut).

A character the table has no name for falls back to the `backslashreplace` form, in lowercase hex: unassigned
`\\u0378`, private use `\\ue000`, a lone surrogate `\\ud800`, and the astral private-use and unassigned
planes `\\U0010fffd` and `\\U0002ee5e`. The fallback is text too, so a wide codec still encodes it at its
own width. Tangut is the exception that separates the two questions the table answers: `name()` has no name
for `\\U00017000`, so the encode face falls back to `\\U00017000`, while `lookup` does know
`TANGUT IDEOGRAPH-17000`, so the decode face accepts it.

The decode face is `unicode_escape`'s `\\N{...}`, and the table is the same one: a name matches after folding
each character to upper case, and not otherwise. `snowman`, `Snowman` and `SNOWMAN` are one name;
`SNOW MAN` and `LATIN_SMALL_LETTER_A` are none, because the space and the underscore are part of the name
rather than separators. The 477 formal aliases are accepted and never written: `LF`, `NUL`, `ZWNBSP`,
`LATIN SMALL LETTER GHA`, `LINE FEED` and `BYTE ORDER MARK` all look up, while a named sequence
(`KEYCAP DIGIT ONE`) and a figment spelling (`LINE FEED (LF)`) do not.

The four algorithmic families are stored as ranges rather than as a name per code point: CJK unified
ideographs in ten blocks, Khitan small script characters in two, Nushu characters in one. Their names are the
prefix and the code point in upper-case hex, and the decode side accepts the same string in either case.

One deviation is named rather than measured here: for `\\U000f0000`..`\\U000f03cc` the reference answers
`\\N{...}` with names from its alias and named-sequence records instead of falling back to `\\U...`, which
looks like a lookup that runs past the name index for a block it has no names for. This runtime has no name
for the block and falls back, so this file leaves the block unmeasured. Two code points that Unicode 16.0
added, `\\U0002ee5d` and `\\U00018cff`, are named here but measured by `ord`, because this runtime's
printability data predates 16.0 and `repr` would escape them — a gap of its own, not of the name table.
"""

import sys

out = sys.__stdout__


def show(label, fn):
    try:
        out.write("%s -> %r\n" % (label, fn()))
    except BaseException as exc:
        out.write("%s -> %s: %s\n" % (label, type(exc).__name__, exc))


out.write("== namereplace writes the primary name ==\n")
show("plain is never asked", lambda: "ab".encode("ascii", "namereplace"))
show("handler never reached", lambda: "ab".encode("ascii", "nope"))
show("accented", lambda: "caf\u00e9".encode("ascii", "namereplace"))
show("euro", lambda: "\u20ac".encode("ascii", "namereplace"))
show("no-break space", lambda: "\u00a0".encode("ascii", "namereplace"))
show("soft hyphen", lambda: "\u00ad".encode("ascii", "namereplace"))
show("hangul syllable", lambda: "\uac00".encode("ascii", "namereplace"))
show("cjk", lambda: "\u4e00".encode("ascii", "namereplace"))
show("emoji", lambda: "\U0001f600".encode("ascii", "namereplace"))
show("two names in a run", lambda: "a\u00e9\u20acb".encode("ascii", "namereplace"))
show("same run, backslashreplace", lambda: "a\u00e9\u20acb".encode("ascii", "backslashreplace"))

out.write("\n== a character without a name falls back ==\n")
show("unassigned", lambda: "\u0378".encode("ascii", "namereplace"))
show("private use", lambda: "\ue000".encode("ascii", "namereplace"))
show("surrogate", lambda: "\ud800".encode("ascii", "namereplace"))
show("astral private use", lambda: "\U0010fffd".encode("ascii", "namereplace"))
show("astral unassigned", lambda: "\U0002ee5e".encode("ascii", "namereplace"))
show("tangut has no name", lambda: "\U00017000".encode("ascii", "namereplace"))
show("tangut supplement has no name", lambda: "\U00018d00".encode("ascii", "namereplace"))
show("latin-1 writes what it can", lambda: "\u00e9\u20ac\ud800".encode("latin-1", "namereplace"))
show("cp1252 writes the euro", lambda: "\u20ac".encode("cp1252", "namereplace"))
show("cp1252 names the rest", lambda: "a\u0100b".encode("cp1252", "namereplace"))

out.write("\n== the answer is text the codec encodes ==\n")
show("utf-8", lambda: "\u00e9\u20ac".encode("utf-8", "namereplace"))
show("utf-16", lambda: "\u00e9".encode("utf-16", "namereplace"))
show("utf-16 le", lambda: "\u00e9".encode("utf-16-le", "namereplace"))
show("utf-16 fallback", lambda: "\ud800".encode("utf-16-le", "namereplace"))
show("utf-32", lambda: "\u20ac".encode("utf-32", "namereplace"))
show("utf-32 le fallback", lambda: "\ud800".encode("utf-32-le", "namereplace"))

out.write("\n== the name names a character ==\n")
show("accented", lambda: b"\\N{LATIN SMALL LETTER E WITH ACUTE}".decode("unicode_escape"))
show("euro", lambda: b"\\N{EURO SIGN}".decode("unicode_escape"))
show("no-break space", lambda: b"\\N{NO-BREAK SPACE}".decode("unicode_escape"))
show("hangul", lambda: b"\\N{HANGUL SYLLABLE GA}".decode("unicode_escape"))
show("snowman lower", lambda: b"\\N{snowman}".decode("unicode_escape"))
show("snowman title", lambda: b"\\N{Snowman}".decode("unicode_escape"))
show("snowman upper", lambda: b"\\N{SNOWMAN}".decode("unicode_escape"))
show("space splits the name", lambda: b"\\N{SNOW MAN}".decode("unicode_escape"))
show("underscore splits the name", lambda: b"\\N{LATIN_SMALL_LETTER_A}".decode("unicode_escape"))
show("only a space", lambda: b"\\N{ }".decode("unicode_escape"))
show("unknown, and where it was", lambda: b"ab\\N{NOPE}cd".decode("unicode_escape"))

out.write("\n== the aliases lookup knows ==\n")
show("lf", lambda: b"\\N{LF}".decode("unicode_escape"))
show("line feed", lambda: b"\\N{LINE FEED}".decode("unicode_escape"))
show("nul", lambda: b"\\N{NUL}".decode("unicode_escape"))
show("zwnbsp", lambda: b"\\N{ZWNBSP}".decode("unicode_escape"))
show("byte order mark", lambda: b"\\N{BYTE ORDER MARK}".decode("unicode_escape"))
show("gha", lambda: b"\\N{LATIN SMALL LETTER GHA}".decode("unicode_escape"))
show("figment is not a name", lambda: b"\\N{LINE FEED (LF)}".decode("unicode_escape"))
show("named sequence is not a name", lambda: b"\\N{KEYCAP DIGIT ONE}".decode("unicode_escape"))

out.write("\n== the algorithmic families are ranges ==\n")
show("cjk start", lambda: b"\\N{CJK UNIFIED IDEOGRAPH-3400}".decode("unicode_escape"))
show("cjk end of a block", lambda: b"\\N{CJK UNIFIED IDEOGRAPH-4DBF}".decode("unicode_escape"))
show("cjk then hexagrams", lambda: b"\\N{HEXAGRAM FOR THE CREATIVE HEAVEN}".decode("unicode_escape"))
show("cjk last of the bmp", lambda: b"\\N{CJK UNIFIED IDEOGRAPH-9FFF}".decode("unicode_escape"))
show("cjk lower hex", lambda: b"\\N{cjk unified ideograph-4e00}".decode("unicode_escape"))
show("cjk astral boundary", lambda: ord(b"\\N{CJK UNIFIED IDEOGRAPH-2EE5D}".decode("unicode_escape")))
show("one past the block", lambda: b"\\N{CJK UNIFIED IDEOGRAPH-2EE5E}".decode("unicode_escape"))
show("no zero padding", lambda: b"\\N{CJK UNIFIED IDEOGRAPH-04E00}".decode("unicode_escape"))
show("khitan start", lambda: b"\\N{KHITAN SMALL SCRIPT CHARACTER-18B00}".decode("unicode_escape"))
show("khitan lone point", lambda: ord(b"\\N{KHITAN SMALL SCRIPT CHARACTER-18CFF}".decode("unicode_escape")))
show("khitan then a gap", lambda: b"\\N{KHITAN SMALL SCRIPT CHARACTER-18CD6}".decode("unicode_escape"))
show("nushu start", lambda: b"\\N{NUSHU CHARACTER-1B170}".decode("unicode_escape"))
show("nushu end", lambda: b"\\N{NUSHU CHARACTER-1B2FB}".decode("unicode_escape"))
show("tangut decodes only", lambda: b"\\N{TANGUT IDEOGRAPH-17000}".decode("unicode_escape"))
show("tangut supplement", lambda: b"\\N{TANGUT IDEOGRAPH-18D00}".decode("unicode_escape"))
show("tangut fallback does not decode", lambda: b"\\N{TANGUT IDEOGRAPH-18D09}".decode("unicode_escape"))

out.write("\n== the two faces agree ==\n")
for point in [0x41, 0xA0, 0x20AC, 0x4E00, 0xAC00, 0x1F600]:
    show(
        "there and back %X" % point,
        lambda point=point: (
            chr(point)
            .encode("ascii", "namereplace")
            .decode("ascii")
            .encode("latin-1")
            .decode("unicode_escape")
            == chr(point)
        ),
    )

out.write("\n== namereplace has nothing to say about octets ==\n")
show("ascii", lambda: b"\xff".decode("ascii", "namereplace"))
show("utf-8", lambda: b"\xff".decode("utf-8", "namereplace"))
show("utf-16", lambda: b"\xff".decode("utf-16", "namereplace"))
show("cp1252 never asks", lambda: b"\xff".decode("cp1252", "namereplace"))
show("unicode_escape never asks either", lambda: "\ud800".encode("unicode_escape", "namereplace"))
