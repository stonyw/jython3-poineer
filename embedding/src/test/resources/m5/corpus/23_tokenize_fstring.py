"""What M5 slice 20b owes: the literal half of the scanner, f-strings and t-strings.

The first segment of this slice handed `_tokenize.TokenizerIter` to the reference `tokenize.py` and
avoided prefixed literals, because one `f'...'` used to arrive as a single `STRING`.  Now it arrives
the way the reference sends it: `FSTRING_START`, then the literal text as `FSTRING_MIDDLE` (or, in
3.14, the format-specifier text as its own `FSTRING_MIDDLE`), the field expression through the
ordinary token loop, and `FSTRING_END`.  `t'...'` follows the same shape with the `TSTRING_*` names.

The markers are what this script measures: a simple field, an empty one, a literal with no field at
all, doubled braces, the `=` debug form, `!r` conversions, a format specifier and a specifier that
holds another field, three-deep nesting, an f-string inside another f-string, the uppercase `F`/`T`
spellings, a raw f-string, a `\\N{...}` name, a string whose quote is the f-string's own, and a
triple-quoted literal that spans lines.

The refusals are the other half: prefix combinations that are not a valid kind (`bf`, `bu`, `bfr`,
`rtb`, `utf`, `urf`), a lone `}`, a field that never closes, a single `}` with no field before it, a
format specifier that takes a newline, four nested fields, an unterminated single- and triple-quoted
f-string or t-string, a NUL byte, a non-UTF-8 coding cookie and a cookie naming a codec that does
not exist, and the exact `offset` `E_EOF` reports when the unterminated f-string starts on a later
logical line, after a blank line, or after a backslash continuation.

Named deviation, so nobody reads more into this than is there: with `extra_tokens=False` (the mode
only a direct `_tokenize.TokenizerIter` caller can ask for, never the compiler front end) the
reference suppresses comments and NL, strips the newline off `NEWLINE.string`, and reports
INDENT/DEDENT/ENDMARKER at column -1.  This runtime always tokenises the `tokenize.py` way, so this
script only drives the scanner with `extra_tokens=True`.
"""
import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
                   "Versions/3.14/lib/python3.14")

import _tokenize
import tokenize


def show(label, source):
    print("==", label)
    try:
        for token in tokenize.generate_tokens(iter(source.splitlines(True)).__next__):
            print(token)
    except BaseException as failure:
        print(
            "!!",
            type(failure).__name__,
            failure.args,
            getattr(failure, "lineno", None),
            getattr(failure, "offset", None),
            repr(getattr(failure, "text", None)),
            getattr(failure, "end_lineno", None),
            getattr(failure, "end_offset", None),
        )


def raw(label, source):
    print("==", label, repr(source))
    try:
        for info in _tokenize.TokenizerIter(
            iter(source.splitlines(True)).__next__, extra_tokens=True
        ):
            print(info)
    except BaseException as failure:
        print(
            "!!",
            type(failure).__name__,
            getattr(failure, "msg", None),
            getattr(failure, "lineno", None),
            getattr(failure, "offset", None),
            repr(getattr(failure, "text", None)),
            getattr(failure, "end_lineno", None),
            getattr(failure, "end_offset", None),
        )


print("== markers")
show("simple", "f'{x}'\n")
show("empty field", "f'{}'\n")
show("empty literal", "f''\n")
show("triple empty", "f''''''\n")
show("doubled braces", "f'{{}}'\n")
show("one doubled brace", "f'{{'\n")
show("two closes", "f'}}'\n")
show("name escape", "f'\\N{GREEK SMALL LETTER ALPHA}\\t{x}'\n")
show("name escape between text", "f'a\\N{GREEK SMALL LETTER ALPHA}b'\n")
show("name escape twice", "f'\\N{GREEK SMALL LETTER ALPHA}\\N{GREEK SMALL LETTER ALPHA}'\n")
show("name escape in spec", "f'{x:\\N{GREEK SMALL LETTER ALPHA}}'\n")
show("name escape left open", "f'\\N{abc'\n")
show("escaped backslash before name", "f'\\\\N{GREEK SMALL LETTER ALPHA}'\n")
show("raw name escape", "rf'\\N{GREEK SMALL LETTER ALPHA}'\n")
show("debug", "f'{x=}'\n")
show("debug and spec", "f'{x=:>10}'\n")
show("conversion", "f'{x!r}'\n")
show("conversion and spec", "f'{x!s:>{w}}'\n")
show("spec nested", "f'{a:{b}}'\n")
show("spec three deep", "f'{a:{b:{c}}}'\n")
show("nested literal", "f'{f'{x}'}'\n")
show("uppercase", "F'{x}' T'{y}'\n")
show("t-string", "t'{x}'\n")
show("raw", "rf'\\{x}'\n")
show("same quote", "f'{'a'}'\n")
show("comparison", "f'{x == y}'\n")
show("triple multiline", "f'''ab\ncd{x}\n'''\n")

print("== prefix conflicts")
for prefix in ["bf", "bu", "bfr", "rtb", "utf", "urf"]:
    raw(prefix, prefix + "'x'\n")

print("== refusal paths")
for bad in [
    "f'}'  \n",
    "f'{x}}'\n",
    "f'{x'\n",
    "f'{x'\n\n",
    "f'abc\n",
    "f'''abc\n",
    "t'''abc\n",
    "f'{x:abc\n",
    "f'{'\n",
    "f'{''\n",
    "f'''{x'''\n",
    "f'''{x''\n",
    "f'{x}\n",
    "f'{x",
    "f'''{x\n",
    "f'{x:",
    "f\"{'abc\n\"",
    "f'{x\"\n",
    "f'''{x'\n",
    "f'{x\"\"\"\n",
    "f'{b'\n",
    "f'{rb'\n",
    "f'{u'\n",
    "f'{a:{b:{c:{d}}}}'\n",
    "f'\\N{abc\n",
]:
    raw("bad", bad)

print("== nul and coding cookies")
raw("nul", "x = 1\0\n")
raw("nul mid", "x = \0y\n")
raw("nul line3", "x = 1\ny = 2\nz = \0\n")
raw("latin", "# coding: latin-1\nx = 1\n")
raw("bad codec", "# coding: nonsense\nx = 1\n")

print("== eof offsets")
for bad in [
    "f'{x\n",
    "f'{x\n\n",
    "  f'{x'\n",
    "y = 1\nf'{x'\n",
    "y = 1\nf'{x",
    "x = 1 + (2 *\n",
    "x = \\\n1 + \\\nf'{x\n",
    "x = 1 + \\\nf'{x\n",
    "x = (1 + \\\n2)\nf'{x\n",
    "y=1\r\nf'{x'\r\n",
]:
    raw("eof", bad)

print("== tokenize refusals")
for bad in [
    "f'{x'\n",
    "f'''{x\n",
    "f'abc\n",
    "f'''abc\n",
    "bf'x'\n",
    "f'{x:abc\n",
    "f'{x}}'\n",
    "f'{a:{b:{c:{d}}}}'\n",
    "f'{}'\n",
    "f'{x=}'\n",
    "f'{x!r:>{w}}'\n",
    "t'{x}'\n",
    "rf'\\{x}'\n",
]:
    show(bad, bad)


def show_bytes(label, data):
    print("==", label, repr(data))
    try:
        for token in tokenize.tokenize(iter(data.splitlines(True)).__next__):
            print(token)
    except BaseException as failure:
        print(
            "!!",
            type(failure).__name__,
            failure.args,
            getattr(failure, "lineno", None),
            getattr(failure, "offset", None),
            repr(getattr(failure, "text", None)),
        )


print("== bytes")
show_bytes("latin", "# coding: latin-1\nx = 'caf\xe9'\n".encode("latin-1"))
show_bytes("bad codec", b"# coding: nonsense\nx = 1\n")
show_bytes("bom", b"\xef\xbb\xbfx = 1\n")
show_bytes("utf8 default", "x = '\u00e9'\n".encode("utf-8"))
show_bytes("bom cookie clash", b"\xef\xbb\xbf# coding: latin-1\nx = 1\n")
show_bytes("nul bytes", b"x = 1\0\n")
