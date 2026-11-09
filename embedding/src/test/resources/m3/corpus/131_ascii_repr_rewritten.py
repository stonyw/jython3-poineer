"""One call to `repr`, then a pass over the text that call handed back.

`ascii` looks like a second way to print a value and is really a transformation of an answer already
given. `PyObject_ASCII` asks for the repr once — the counting rows below show a `__repr__` called once
per call, and `Raises` shows that whatever the repr refuses is what the caller hears — and then walks
over *that text*, replacing every code point above `0x7f` with the escape its width asks for: `\\x` and
two lowercase digits up to `0xff`, `\\u` and four up to `0xffff`, `\\U` and eight beyond that. The
thirteen rows of `the width that picks the escape` run from the last character that arrives as the repr
had already written it to the widest escape there is, and outside the quotes the three widths cost four,
six and ten characters. The one backslash counted under `the answer is literal text` is the proof that
those are characters of text, not an escape that anything downstream will decode again.

Everything at or below `0x7f` is handed on as it stands, and that is the half programs get wrong. The
pass runs on the text the repr produced, so a control character the repr had already escaped is not
escaped a second time — the `same=True` column under `what the repr had already escaped` says the answer
and the repr are the same text there — while a raw newline written by a program's own `__repr__` stays a
raw newline inside the answer, next to the escapes its non-ASCII neighbours became.

Because the pass runs over text rather than over values, it reaches whatever the repr wrote, whoever
wrote it: each element of a container brings its own repr text, the escape inside a class's `<class
...>` comes from the type machinery rather than from any `__repr__` a program wrote, and a value whose
`__str__` and `__repr__` disagree answers with the repr's side. That is the opposite of `print`, which
writes the `str`, and the same side `sys.displayhook` writes — but escaped here, where the displayhook
leaves the character raw.

The one argument is positional-only and the calling convention is the counting family's, so a second
argument and any keyword at all are refused before the repr is asked — the last two rows show the counter
still where the previous section left it.
"""

import builtins

ORIGINAL = builtins.ascii


class Says:
    """A repr that writes exactly the text this program wants escaped."""

    def __init__(self, text):
        self.text = text

    def __repr__(self):
        return self.text


class Counts:
    calls = 0

    def __repr__(self):
        Counts.calls += 1
        return "caf\xe9"


class Both:
    def __str__(self):
        return "str\xe9"

    def __repr__(self):
        return "repr\xe9"


class Sub(Both):
    pass


class Raises:
    def __repr__(self):
        raise ValueError("no text")

    def __str__(self):
        return "never asked"


class NonStr:
    def __repr__(self):
        return 3


class É:
    pass


def told(action):
    try:
        return "ok " + answer(action())
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def answer(value):
    if isinstance(value, str):
        return repr(value)
    return str(value)


print("== the row the name carries ==")
row = builtins.ascii
print("name", row.__name__, "qualname", row.__qualname__)
print("module", row.__module__, "type", type(row).__name__)
print("signature", repr(row.__text_signature__))
print("doc length", len(row.__doc__), "doc lines", row.__doc__.count("\n") + 1)
print("repr", repr(row))
print("the same function", row is builtins.ascii, row is ORIGINAL)

print("== the width that picks the escape ==")
for code in [0x7F, 0x80, 0x81, 0x9F, 0xA0, 0xFF, 0x100, 0x7FF, 0x20AC, 0x8000, 0xFFFF, 0x10000, 0x10FFFF]:
    escaped = ascii(chr(code))
    print("%-8s %-13s len=%-3d in a list %-15s ascii only=%s"
          % (hex(code), escaped, len(escaped), ascii([chr(code)]), escaped.isascii()))

print("== what the repr had already escaped is not escaped again ==")
for code in [0x00, 0x07, 0x09, 0x0A, 0x0D, 0x22, 0x27, 0x5C, 0x7F, 0x85]:
    value = chr(code)
    print("%-6s repr %-10s ascii %-10s same=%-5s len=%d"
          % (hex(code), repr(value), ascii(value), ascii(value) == repr(value), len(ascii(value))))

print("== the answer is literal text, not an escape ==")
one = ascii("caf\xe9")
print("printed", one, "| len", len(one), "backslashes", one.count("\\"), "| as repr", repr(one))
print("a real e in it", "\xe9" in one, "second pass is repr", ascii(one) == repr(one))
print("third pass is repr", ascii(ascii(one)) == repr(ascii(one)))
for text in ["\xe9", "\u0100", "\U0001f600"]:
    print("escape of %-10s outside the quotes: %d characters" % (hex(ord(text)), len(ascii(text)) - 2))

print("== below 0x80 the repr's own characters stay ==")
raw = Says("a\nb\tc\x7fd\xe9\u0100")
print("as repr", repr(ascii(raw)))
print("raw newline kept", "\n" in ascii(raw), "raw del kept", "\x7f" in ascii(raw))
print("escaped e kept", "\\xe9" in ascii(raw), "raw e gone", "\xe9" not in ascii(raw))
print("all of it ascii", ascii(raw).isascii())

print("== the repr's text, never the str's ==")
both = Both()
print("lengths  str", len(str(both)), "repr", len(repr(both)), "ascii", len(ascii(both)))
print("texts", repr(str(both)), repr(repr(both)), repr(ascii(both)))
print("the repr with its e escaped", repr(both).replace("\xe9", "\\xe9"),
      ascii(both) == repr(both).replace("\xe9", "\\xe9"))
print("inherited", ascii(Sub()))
print("raises", told(lambda: ascii(Raises())), "non-string", told(lambda: ascii(NonStr())))

print("== the repr is asked once ==")
counted = Counts()
print("first", ascii(counted), "calls", Counts.calls)
print("second", ascii(counted), "calls", Counts.calls)
print("str of it", str(counted), "calls", Counts.calls)

print("== each element brings its own text ==")
print(ascii(["caf\xe9", "\u65e5\u672c", b"\xff", "\U0001f600"]))
print(ascii({"caf\xe9": "\u65e5", ("\xe9",): ["\xe9"]}))
print(ascii(["'x'", '"y"', "it's"]))
print(ascii([1, 2.5, True, None, ["\xe9"], Says("raw\xe9")]))
print(ascii(b"caf\xe9"), ascii(b"\x00\xff\n"))
print("a class name", ascii(É))

print("== every answer is ascii ==")
VALUES = ["\xe9", "\u65e5", "\U0001f600", ["\xe9"], {"\xe9": "\u65e5"}, ("\xe9",),
          1, "plain", -0.0, 2**70, slice(0, "\xe9"), Ellipsis, NotImplemented,
          range(3), dict(a="\xe9").keys(), Says("raw\xe9")]
for value in VALUES:
    print("%-14s %-24s ascii=%s" % (type(value).__name__, ascii(value), ascii(value).isascii()))
print("all of them", all(ascii(value).isascii() for value in VALUES))

print("== the calling convention ==")
print("nothing", told(lambda: ascii()))
print("two", told(lambda: ascii(1, 2)))
print("three", told(lambda: ascii("a", "b", "c")))
print("named", told(lambda: ascii(obj=1)))
print("mixed", told(lambda: ascii(1, obj=1)))
print("unpacked", told(lambda: ascii(1, **{})), told(lambda: ascii(*())))
print("calls before the refusals", Counts.calls)
print("two given", told(lambda: ascii(counted, 2)), "named", told(lambda: ascii(counted, obj=1)))
print("calls after them", Counts.calls)
