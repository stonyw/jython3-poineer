"""What M5 slice 20 owes: `_tokenize.TokenizerIter` as the scanner `tokenize.py` is written over.

The reference `tokenize.py` since 3.14 is a thin wrapper: `generate_tokens` hands the readline to
`_tokenize.TokenizerIter`, makes a `TokenInfo` out of each five-tuple, and turns the tokenizer's
`SyntaxError` into `TokenError`.  So the whole module is the reference's own Python again once this
runtime files `TokenizerIter`, and the script puts the reference library on `sys.path` so that both
interpreters run the same wrapper over their own scanner.

The first half is the token stream: names, numbers in every radix, every operator (whose `type` is
`OP` and whose `exact_type` is the precise one), strings in each quote form, comments, blank lines,
indentation and the two flavours of DEDENT, parentheses that hold a newline back, a backslash
continuation, CRLF line ends, and a last line with no newline at all.  The second half is the refusal
path: an unterminated string, an unclosed bracket, a bad dedent, mixed tabs and spaces, and an
unterminated triple quote, each reported with the fields both interpreters fill.

Not exercised here: a prefixed literal.  This runtime still reads `f'...'` as one `STRING` where the
reference emits `FSTRING_START`/`FSTRING_MIDDLE`/`FSTRING_END`; that is the named deviation of the
first segment of this slice, and the corpus avoids f-strings until the second segment lands.
"""
import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
                   "Versions/3.14/lib/python3.14")

import inspect
import tokenize


def show(label, source):
    print("==", label)
    lines = source.splitlines(True)
    for token in tokenize.generate_tokens(iter(lines).__next__):
        print(token)


show("basic", "x = 1 + 2  # hi\nif x:\n    y = 'abc'\n")
show("numbers", "a = 0x1f + 0b1010 + 0o17 + 1_000 + 1.5e-3 + 2j + 3.0\n")
show(
    "operators",
    "a = b + c - d * e ** f / g // h % i @ j\n"
    "a <<= b >>= c &= d |= e ^= f @= g //= h **= i\n"
    "a = b < c > d <= e >= f == g != h\n"
    "a = b -> c := d\n"
    "a = (b) [c] {d} , e : f . g ; h ... i\n",
)
show("strings", "a = 'one'\nb = \"two\"\nc = '''three\nlines'''\nd = \"\"\"four\nlines\"\"\"\ne = ''\n")
show("layout", "x = 1\n\n# only a comment\n\ny = 2\nif y:\n    z = 3\n    if z:\n        w = 4\nq = 5\n")
show("brackets", "x = [1,\n     2,\n     ]\ny = {\n    'a': 1,\n}\n")
show("continuation", "x = 1 + \\\n    2\n")
show("crlf", "x = 1\r\nif x:\r\n    y = 2\r\n")
show("no trailing newline", "x = 1")
show("soft keywords", "match x:\n    case 1:\n        pass\n\nasync def f():\n    await g()\n")

print("== exact types")
lines = iter(["a = b + c - d * e ** f / g // h % i @ j\n"]).__next__
print(
    [
        (token.string, token.type, token.exact_type)
        for token in tokenize.generate_tokens(lines)
        if token.string.strip()
    ]
)


def f(a, b=1, *c, d, **e):
    pass


class C:
    def m(self, x, y=2):
        return x


print("== signatures")
print(inspect.signature(f))
print(inspect.signature(C.m))
print(inspect.signature(C().m))
print(inspect.signature(print))


def refusal(source):
    try:
        list(tokenize.generate_tokens(iter(source.splitlines(True)).__next__))
    except BaseException as failure:
        return (
            type(failure).__name__,
            str(failure),
            getattr(failure, "lineno", None),
            getattr(failure, "offset", None),
            getattr(failure, "text", None),
        )
    return "no error"


print("== refusals")
for bad in [
    "x = 'abc\n",
    "x = (1,\n",
    "if x:\n    y\n  z\n",
    "if x:\n\ty\n        z\n",
    "x = '''abc\n",
]:
    print(refusal(bad))
