"""UTF-32, cp1252 and unicode_escape, read from both faces of each codec.

`utf-32` is `utf-16` with the unit widened to four octets, and every rule that follows from the width follows
unchanged: one code point per unit, the mark on the plain name and none on the endian ones, a refusal that
hands the handler all four octets, and a `surrogatepass` that writes the surrogate as itself in four. The
unit is big-endian on `utf-32-be` and little-endian everywhere else, and the mark is `FF FE 00 00` rather than
`FF FE`, which is what makes the two marks distinguishable: on the way in the four octets are a prefix to
skip, and a stream with no mark at all is read little-endian rather than refused.

The encode face refuses a surrogate with `surrogates not allowed` and never collects a run, so `a\\ud800b` is
one refusal of one character while the same text under `ascii` is a refusal of the whole run above `0x80`.
The decode face has three refusals of its own, and their ranges are all four octets wide: `truncated data`
when fewer than four octets are left, `code point not in range(0x110000)` when the quad is above the last
code point, and `code point in surrogate code point range(0xd800, 0xe000)` when it names a surrogate. The
positions count from the start of the input rather than from the end of the mark, so a three-octet tail after
a four-octet mark is refused at `4-6`.

`cp1252` is the one-octet walk with a table for `0x80..0x9F`, and the table has five holes — `0x81`, `0x8D`,
`0x8F`, `0x90` and `0x9D` — so the codec both refuses some octets below `0xA0` and accepts every octet above
it. Its errors carry the reported name `charmap` on both faces, not `cp1252`, which is the name the error
object's `encoding` field shows and the one every sentence begins with; the reason on both faces is
`character maps to <undefined>`. A character with no octet is collected with its neighbours, so
`a\\u0100\\u0101b` is a refusal of positions `1-2`, and `surrogateescape` is a fast path here just as it is for
the one-byte family: `\\udc80` becomes `0x80` and `\\udcff` becomes `0xFF` without asking the registry.

`unicode_escape` is the only codec in this runtime that refuses nothing when encoding: every code point has
an escape, so the handler name is never looked up and `"\\ud800".encode("unicode_escape", "nope")` answers the
same six characters as `"\\ud800".encode("unicode_escape")`. The escapes it writes are short for the four
characters that have short forms — `\\\\`, `\\n`, `\\r`, `\\t` — plain for ASCII from `0x20` to `0x7E`, `\\xNN`
below `0x100`, `\\uXXXX` below `0x10000` and `\\UXXXXXXXX` above it, always in lowercase hex. The decode face is
the only walk here that reads a grammar: an octet that is not a backslash is passed through as latin-1, and a
backslash starts a sequence whose width is named by the character after it.

The grammar's refusals are quoted by the octets they read. A backslash at the end is `\\ at end of string`
over that one octet; a `\\x`, `\\u` or `\\U` that runs out of input is `truncated \\xXX escape`,
`truncated \\uXXXX escape` or `truncated \\UXXXXXXXX escape` over everything from the backslash to the last
octet; a quad above `0x10FFFF` is `illegal Unicode character` over the whole escape. `\\N` has four refusals
of its own: a name that does not open with a brace is `malformed \\N character escape` over two octets, a
brace that never closes is the same sentence over the rest of the input, an empty name is the same sentence
up to but not including the closing brace, and a name the table does not know is `unknown Unicode character
name` over the escape including that brace. Octal reads up to three digits, `\\101` is `A` and `\\377` is
`0xFF`.

Two things this file leaves alone. `namereplace` and the Unicode name table behind `\\N` have a file of their
own, because the table is exported reference data rather than a codec rule. And the probe texts use lone
surrogates only: this runtime's lexer joins an adjacent high and low surrogate into one astral character, which
is a gap of its own and not a codec question, so a pair written as two escapes would measure the lexer rather
than the decoder.
"""

import sys

out = sys.__stdout__


def show(label, fn):
    try:
        out.write("%s -> %r\n" % (label, fn()))
    except BaseException as exc:
        out.write("%s -> %s: %s\n" % (label, type(exc).__name__, exc))


out.write("== utf-32 writes four octets per unit ==\n")
show("plain empty", lambda: "".encode("utf-32"))
show("le empty", lambda: "".encode("utf-32-le"))
show("be empty", lambda: "".encode("utf-32-be"))
show("plain text", lambda: "ab".encode("utf-32"))
show("le text", lambda: "ab".encode("utf-32-le"))
show("be text", lambda: "ab".encode("utf-32-be"))
show("be astral", lambda: "\U0001f600".encode("utf-32-be"))

out.write("\n== utf-32 and its handlers ==\n")
show("strict", lambda: "a\ud800b".encode("utf-32"))
show("strict le", lambda: "a\ud800b".encode("utf-32-le"))
show("surrogatepass", lambda: "a\ud800b".encode("utf-32", "surrogatepass"))
show("ignore", lambda: "a\ud800b".encode("utf-32", "ignore"))
show("replace", lambda: "a\ud800b".encode("utf-32", "replace"))
show("backslashreplace", lambda: "a\ud800b".encode("utf-32", "backslashreplace"))
show("xmlcharrefreplace", lambda: "a\ud800b".encode("utf-32", "xmlcharrefreplace"))
show("surrogateescape", lambda: "a\udc80b".encode("utf-32", "surrogateescape"))
show("handler named but not reached", lambda: "ab".encode("utf-32", "nope"))

out.write("\n== the names utf-32 answers ==\n")
for name in ["u32", "utf32", "UTF_32LE", "utf-32be", "utf32le", "unicodeescape"]:
    show(name, lambda n=name: "a".encode(n))

out.write("\n== utf-32 reads the mark and the order ==\n")
show("mark then le", lambda: b"\xff\xfe\x00\x00a\x00\x00\x00".decode("utf-32"))
show("mark then be", lambda: b"\x00\x00\xfe\xff\x00\x00\x00a".decode("utf-32"))
show("no mark is le", lambda: b"a\x00\x00\x00".decode("utf-32"))
show("no mark read as le", lambda: b"\x00\x00\x00a".decode("utf-32"))
show("le explicit", lambda: b"a\x00\x00\x00".decode("utf-32-le"))
show("be explicit", lambda: b"\x00\x00\x00a".decode("utf-32-be"))
show("surrogate kept", lambda: b"\x00\xd8\x00\x00".decode("utf-32-le", "surrogatepass"))
show("surrogate replaced", lambda: b"\x00\xd8\x00\x00".decode("utf-32-le", "replace"))

out.write("\n== utf-32 refusals quote four octets ==\n")
show("tail after mark", lambda: b"\xff\xfe\x00\x00a\x00\x00".decode("utf-32"))
show("tail be", lambda: b"\x00\x00\x00a\x00".decode("utf-32-be"))
show("above the range", lambda: b"\x00\x11\x00\x00".decode("utf-32-be"))
show("surrogate", lambda: b"\x00\xd8\x00\x00".decode("utf-32-le"))
show("escape resumes inside a quad", lambda: b"\x80\xdc\x00\x00".decode("utf-32-le", "surrogateescape"))
show("escape then surrogate", lambda: b"\xff\xfe\x00\x00\x00\xdc\x00\x00".decode("utf-32", "surrogateescape"))

out.write("\n== unicode_escape writes an escape for every character ==\n")
show("empty", lambda: "".encode("unicode_escape"))
show("plain", lambda: "abc".encode("unicode_escape"))
show("short forms", lambda: "\x00\x07\x08\x09\x0a\x0d\x1f\x7f".encode("unicode_escape"))
show("backslash", lambda: "a\\b".encode("unicode_escape"))
show("latin-1", lambda: "\x80\xa0\xe9\xff".encode("unicode_escape"))
show("bmp", lambda: "\u0100\u65e5\uffff".encode("unicode_escape"))
show("astral", lambda: "\U0001f600\U0010ffff".encode("unicode_escape"))
show("surrogate", lambda: "\ud800".encode("unicode_escape"))
show("handler named but not reached", lambda: "\ud800".encode("unicode_escape", "nope"))

out.write("\n== unicode_escape reads a grammar ==\n")
show("plain", lambda: b"abc".decode("unicode_escape"))
show("latin-1 pass through", lambda: b"\x80\xe9".decode("unicode_escape"))
show("short forms", lambda: b"\\x00\\t\\n\\r\\\\".decode("unicode_escape"))
show("hex", lambda: b"\\x41\\x7f\\xe9".decode("unicode_escape"))
show("four digits", lambda: b"\\u65e5\\u00e9".decode("unicode_escape"))
show("eight digits", lambda: b"\\U0001f600".decode("unicode_escape"))
show("octal", lambda: b"\\101\\0\\377".decode("unicode_escape"))
show("line continuation", lambda: b"a\\\nb".decode("unicode_escape"))

out.write("\n== unicode_escape refusals ==\n")
show("trailing backslash", lambda: b"abc\\".decode("unicode_escape"))
show("truncated x", lambda: b"\\x4".decode("unicode_escape"))
show("truncated u", lambda: b"\\u12".decode("unicode_escape"))
show("truncated U", lambda: b"\\U0012".decode("unicode_escape"))
show("above the range", lambda: b"\\U00110000".decode("unicode_escape"))

out.write("\n== the names unicode_escape knows ==\n")
show("bullet", lambda: b"\\N{BULLET}".decode("unicode_escape"))
show("snowman", lambda: b"\\N{SNOWMAN}".decode("unicode_escape"))
show("empty name", lambda: b"\\N{}".decode("unicode_escape"))
show("no brace", lambda: b"\\Nx".decode("unicode_escape"))
show("no closing brace", lambda: b"\\N{BULLET".decode("unicode_escape"))
show("unknown name", lambda: b"\\N{BOGUS NAME}".decode("unicode_escape"))

out.write("\n== cp1252 has a table and five holes ==\n")
show("table", lambda: "\u20ac\u201a\u2014".encode("cp1252"))
show("hole in the text", lambda: "\x81".encode("cp1252"))
show("run of two", lambda: "a\u0100\u0101b".encode("cp1252"))
show("past the table", lambda: "\ufb01".encode("cp1252"))
show("replace", lambda: "a\u20ac\u0100b".encode("cp1252", "replace"))
show("backslashreplace", lambda: "\u20ac\u0100".encode("cp1252", "backslashreplace"))
show("xmlcharrefreplace", lambda: "\u20ac\u0100".encode("cp1252", "xmlcharrefreplace"))
show("surrogateescape", lambda: "\udc80\udcff".encode("cp1252", "surrogateescape"))
show("surrogatepass", lambda: "\ud800".encode("cp1252", "surrogatepass"))
show("aliases", lambda: ("a".encode("1252"), "a".encode("windows_1252")))

show("decode table", lambda: b"\x80\xa0\xe9".decode("cp1252"))
show("decode holes replaced", lambda: b"\x80\x81\x8d\x8f\x90\x9d\xa0".decode("cp1252", "replace"))
show("decode a hole", lambda: b"a\x81b".decode("cp1252"))
show("decode two holes", lambda: b"\x81\x8d".decode("cp1252"))
show("decode surrogateescape", lambda: b"\x80\x81\xe9".decode("cp1252", "surrogateescape"))
