"""memoryview's fifth segment: hex, count, index, __class_getitem__, and the forty-one rows.

`hex` copies the octets in the order the window presents them and then spells them exactly as
`bytes.hex` does, separators and grouping included. Its two parameters are optional and may be given
by keyword, so the argument count is settled before the released check — three arguments raise the
`TypeError` even on a spent window.

`count` and `index` compare each element with `==` rather than looking for a sub-sequence, which is
why `b"hello".count(b"l")` is zero while `count(108)` is two. `index` takes its bounds as slice
bounds but refuses `None`, since the search path uses the wording without it in the list.

`__class_getitem__` is the generic subscription: a `classmethod_descriptor` answering a
`types.GenericAlias`, with the sentence that names no argument table.

The dictionary is closed at forty-one rows: every name in the reference's order, every row the same
kind of descriptor.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


class Anything:
    def __eq__(self, other):
        return True


class Explodes:
    def __eq__(self, other):
        raise RuntimeError("boom")


# --- hex: the digits and the separators ---------------------------------------------------

plain = memoryview(b"\xb9\x01\xef")
print("hex", plain.hex())
print("hex-sep", plain.hex(":"))
print("hex-two", plain.hex(":", 2))
print("hex-minus-two", plain.hex(":", -2))
print("hex-two-minus", plain.hex(":", -2), plain.hex(":", 2))
print("hex-zero", plain.hex(":", 0))
print("hex-wide", plain.hex(":", 5))
print("hex-bytes-sep", plain.hex(b":"))
attempt("hex-bytearray-sep", lambda: plain.hex(bytearray(b":")))
print("hex-bool-step", plain.hex(":", True))
print("hex-empty", memoryview(b"").hex(), memoryview(b"").hex(":"))
print("hex-one", memoryview(b"a").hex(":"), memoryview(b"a").hex(":", 0))
print("hex-keywords", plain.hex(sep=":"), plain.hex(bytes_per_sep=2))
print("hex-both-keywords", plain.hex(sep=":", bytes_per_sep=-2))
print("hex-mixed", plain.hex(":", bytes_per_sep=2))
attempt("hex-too-many", lambda: plain.hex(":", 1, 2))
attempt("hex-unknown", lambda: plain.hex(start=":"))
attempt("hex-sep-none", lambda: plain.hex(None))
attempt("hex-sep-int", lambda: plain.hex(5))
attempt("hex-sep-empty", lambda: plain.hex(""))
attempt("hex-sep-two", lambda: plain.hex("::"))
attempt("hex-sep-wide", lambda: plain.hex("\xe9"))
attempt("hex-step-float", lambda: plain.hex(":", 1.5))
attempt("hex-step-big", lambda: plain.hex(":", 2**31))
attempt("hex-step-huge", lambda: plain.hex(":", 2**70))


# --- hex: what the window presents --------------------------------------------------------

print("hex-strided", memoryview(b"abcdef")[::2].hex())
print("hex-reversed", memoryview(b"abc")[::-1].hex())
print("hex-sliced", memoryview(b"abcdef")[1:4].hex(":"))
two_d = memoryview(b"abcd").cast("B", (2, 2))
print("hex-2d", two_d.hex(), two_d.hex(":", 2), two_d.shape)
wide = memoryview(b"\x01\x02\x03\x04").cast("H")
print("hex-wide-format", wide.hex(), wide.hex(":"), wide.itemsize)
chars = memoryview(b"abc").cast("c")
print("hex-chars", chars.hex())
live = bytearray(b"abc")
window = memoryview(live)
live[0] = ord("Z")
print("hex-live", window.hex())

spent = memoryview(b"ab")
spent.release()
attempt("hex-released", lambda: spent.hex())
attempt("hex-released-many", lambda: spent.hex(":", 1, 2))
attempt("hex-released-bad", lambda: spent.hex(5))
print("hex-class", memoryview.hex(memoryview(b"ab")))


# --- count: equality against each element -------------------------------------------------

text = memoryview(b"abab")
print("count", text.count(97), text.count(98), text.count(99))
print("count-float", text.count(97.0))
print("count-bool", memoryview(b"\x01\x00").count(True))
print("count-bytes", memoryview(b"hello").count(b"l"), memoryview(b"hello").count(108))
print("count-slice", text[1:].count(97), text[::-1].count(97), text[::2].count(97))
print("count-empty", memoryview(b"").count(0))
print("count-anything", text.count(Anything()))
attempt("count-explodes", lambda: text.count(Explodes()))

print("char-count", memoryview(b"abc").cast("c").count(b"b"))
print("short-count", memoryview(b"\x01\x00\x01\x00").cast("H").count(1))
print("float-count", memoryview(b"\x00\x00\x80\x3f").cast("f").count(1.0))
print("nan-count", memoryview(b"\x00\x00\xc0\x7f").cast("f").count(float("nan")))
print("bool-count", memoryview(b"\x00\x01").cast("?").count(True))

attempt("count-2d", lambda: two_d.count(97))
attempt("count-released", lambda: spent.count(97))
attempt("count-released-zero", lambda: spent.count())
attempt("count-none", lambda: text.count())
attempt("count-extra", lambda: text.count(97, 98))
attempt("count-keyword", lambda: text.count(value=97))
print("count-class", memoryview.count(text, 97))


# --- index: where the first equal element sits --------------------------------------------

found = memoryview(b"abcb")
print("index", found.index(98), found.index(98, 2), found.index(98, 2, 4))
print("index-first", found.index(97), found.index(98, -2), found.index(97, 0, -3))
print("index-clamp", found.index(97, -100))
attempt("index-empty-range", lambda: found.index(98, 4, 1))
print("index-bool", found.index(98, True))
print("index-anything", found.index(Anything()))
attempt("index-missing", lambda: found.index(99))
attempt("index-missing-after", lambda: found.index(97, 1))
attempt("index-none", lambda: found.index(98, None))
attempt("index-none-stop", lambda: found.index(98, 0, None))
attempt("index-string", lambda: found.index(98, "x"))
attempt("index-float", lambda: found.index(98, 1.0))
print("index-char", memoryview(b"abc").cast("c").index(b"c"))
print("index-reversed", memoryview(b"abc")[::-1].index(97))
print("index-short", memoryview(b"\x01\x00\x02\x00").cast("H").index(2))
attempt("index-2d", lambda: two_d.index(97))
attempt("index-released", lambda: spent.index(97))
attempt("index-released-zero", lambda: spent.index())
attempt("index-zero", lambda: found.index())
attempt("index-extra", lambda: found.index(97, 0, 4, 1))
attempt("index-keyword", lambda: found.index(value=97))
print("index-class", memoryview.index(found, 98, 2, 4))


# --- __class_getitem__ --------------------------------------------------------------------

one = memoryview[int]
print("alias", repr(one), type(one).__name__, one.__origin__ is memoryview, one.__args__)
print("alias-pair", repr(memoryview[bytes, int]), memoryview[bytes, int].__args__)
print("alias-colon", repr(memoryview[int, ...]))
row = memoryview.__dict__["__class_getitem__"]
print("alias-row", type(row).__name__, row.__text_signature__)
print("alias-doc", row.__doc__)


# --- the dictionary is closed -------------------------------------------------------------

rows = list(memoryview.__dict__)
print("rows", len(rows))
print("names", rows)
for name in rows:
    print("row", name, type(memoryview.__dict__[name]).__name__)

for name in ("hex", "count", "index"):
    method = memoryview.__dict__[name]
    print("method", name, type(method).__name__, method.__text_signature__)
    print("doc", name, method.__doc__)
