"""memoryview's fourth segment, second half, part one: `tobytes` and `tolist`.

Both copy the elements the window selects rather than the run underneath, so a strided or reversed
slice answers its own order and a write that lands after the copy does not change it. `tobytes`
takes one keyword, `order`, whose three values all mean that same copy here: a one-dimensional
window has one layout, and the reference converts a non-contiguous view to C before copying either
way. `tolist` answers the same octets as integers.

The argument sentences differ in what they name. `tobytes` is parsed from a clinic table, so its
arity and keyword refusals say `tobytes()` and its value refusal names the parameter, while
`tolist` is hand-written and refuses with `memoryview.tolist()`. The order of the checks is worth
naming: arity and the type of `order` are settled before the released check, so a spent view still
answers a malformed call with the malformed-call sentence, but the spelling of the letter is asked
afterwards, so `spent.tobytes("x")` is refused as a released view rather than as a bad order.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


data = b"abcdef"
mv = memoryview(data)

print("tobytes", mv.tobytes())
print("tolist", mv.tolist())
print("empty", memoryview(b"").tobytes(), memoryview(b"").tolist())
print("strided", mv[::2].tobytes(), mv[::2].tolist())
print("reversed", mv[::-1].tobytes(), mv[::-1].tolist())
print("slice", mv[1:4].tobytes(), mv[1:4].tolist())
print("types", type(mv.tobytes()).__name__, type(mv.tolist()).__name__, mv.readonly)


# --- a writable view copies the run as it stands now, and the copy is a copy --------------

run = bytearray(b"xyz")
writable = memoryview(run)
writable[0] = ord("X")
print("live", writable.tobytes(), writable.tolist(), run)
copy = writable.tobytes()
writable[1] = ord("Y")
print("copy", copy, writable.tobytes())


# --- order: three spellings and None, all the same copy here -----------------------------

for order in ("C", "F", "A", None):
    print("order", order, mv.tobytes(order), mv.tobytes(order=order))

attempt("order-c", lambda: mv.tobytes("c"))
attempt("order-empty", lambda: mv.tobytes(""))
attempt("order-long", lambda: mv.tobytes("CC"))
attempt("order-int", lambda: mv.tobytes(1))
attempt("order-bool", lambda: mv.tobytes(True))
attempt("order-bytes", lambda: mv.tobytes(b"C"))


# --- argument sentences: tobytes parsed, tolist hand-written -----------------------------

attempt("tobytes-extra", lambda: mv.tobytes("C", "F"))
attempt("tobytes-position-and-name", lambda: mv.tobytes("C", order="F"))
attempt("tobytes-unknown", lambda: mv.tobytes(foo="C"))
attempt("tobytes-two-keywords", lambda: mv.tobytes(foo="C", order="F"))
attempt("tolist-arg", lambda: mv.tolist(1))
attempt("tolist-keyword", lambda: mv.tolist(order="C"))

print("class", memoryview.tobytes(mv), memoryview.tolist(mv), memoryview.tobytes(mv, order="A"))


# --- released: the arguments run first, then the state refuses ---------------------------

spent = memoryview(b"abc")
spent.release()
attempt("released-tobytes", lambda: spent.tobytes())
attempt("released-tolist", lambda: spent.tolist())
attempt("released-bad-args", lambda: spent.tobytes(1, 2))
attempt("released-bad-type", lambda: spent.tobytes(1))
attempt("released-bytes", lambda: spent.tobytes(b"C"))
attempt("released-bad-order", lambda: spent.tobytes("c"))
attempt("released-ok-order", lambda: spent.tobytes("F"))


# --- signatures, docs and filing ---------------------------------------------------------

print("signatures", memoryview.tobytes.__text_signature__, memoryview.tolist.__text_signature__)
print("docs", memoryview.tobytes.__doc__.splitlines()[0], memoryview.tolist.__doc__)
print("names", memoryview.tobytes.__name__, memoryview.tobytes.__qualname__)
print(
    "order",
    [name for name in memoryview.__dict__ if name in
     ("release", "tobytes", "tolist", "__enter__", "__exit__", "obj")],
)
