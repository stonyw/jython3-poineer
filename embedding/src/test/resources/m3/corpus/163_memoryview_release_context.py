"""memoryview's fourth segment, first half: `release` and the `with` pair.

`release` turns the window spent rather than dropping the exporter: the reference unpins the buffer
it acquired, and every operation afterwards raises the same `ValueError` sentence. Four rows stay
outside that check — `repr` prints the released window, `release` answers `None` again, `__exit__` is
a release and so repeats it, and the two equalities fall back to identity — which is why a released
view is unequal to the octets it used to cover and why it is equal only to itself.

Release is a state of one view, not of what it looks into: another view of the same bytearray still
reads and writes after this one is spent, and so does a view taken of the spent view before it was
released. The `with` statement is the reachable spelling of the state — `__enter__` answers the very
same window and `__exit__` releases it however the body ended, answering `None` so an exception
leaves unchanged. Both rows sit where the reference keeps them, after `__delitem__`; the two buffer
protocol slots and the conversions from `tobytes` to `toreadonly` are the other half of the segment.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


data = b"abcdef"
mv = memoryview(data)

print("before-obj", mv.obj is data)
print("before-len", len(mv), bytes(mv), mv.readonly)


# --- release is idempotent, and repr is what a spent window still does --------------------

print("release", repr(mv.release()), repr(mv.release()))
spent_repr = repr(mv)
print(
    "released-repr",
    spent_repr.startswith("<released memory at 0x"),
    spent_repr.endswith(">"),
    "abcdef" not in spent_repr,
)

print("eq-self", mv == mv, mv != mv, mv.__eq__(mv), mv.__ne__(mv))
print("eq-bytes", mv == data, mv != data, mv.__eq__(data), mv.__ne__(data))
print("eq-view", mv == memoryview(data), mv != memoryview(data))
print("eq-live-side", memoryview(data) == mv, memoryview(data) != mv)


# --- every other read refuses with the one sentence --------------------------------------

attempt("len", lambda: len(mv))
attempt("bool", lambda: bool(mv))
attempt("iter", lambda: iter(mv))
attempt("index", lambda: mv[0])
attempt("slice", lambda: mv[0:2])
attempt("delitem", lambda: mv.__delitem__(0))
attempt("hash", lambda: hash(mv))
attempt("buffer", lambda: memoryview(mv))
attempt("enter", lambda: mv.__enter__())

for prop in (
    "obj",
    "nbytes",
    "readonly",
    "itemsize",
    "format",
    "ndim",
    "shape",
    "strides",
    "suboffsets",
    "c_contiguous",
    "f_contiguous",
    "contiguous",
):
    attempt("released-" + prop, lambda prop=prop: getattr(mv, prop))

spent_writable = memoryview(bytearray(b"abc"))
spent_writable.release()
attempt("setitem", lambda: spent_writable.__setitem__(0, 1))
attempt("setitem-slice", lambda: spent_writable.__setitem__(slice(0, 1), b"z"))

print("release-after", repr(mv.release()), repr(memoryview.release(mv)))
print("exit-after", repr(mv.__exit__()), repr(memoryview.__exit__(mv)))


# --- release spends one view, not the octets underneath ----------------------------------

run = bytearray(b"abcdef")
first = memoryview(run)
second = memoryview(run)
child = memoryview(first)
first.release()
second[0] = ord("Z")
print("sibling", len(second), bytes(second), second.obj is run)
print("child", len(child), bytes(child), child.obj is run)
attempt("parent", lambda: len(first))
print("child-readonly", child.readonly)


# --- the with statement enters the same window and leaves it released --------------------

writable = memoryview(bytearray(b"ab"))
entered = writable.__enter__()
print("enter-self", entered is writable, memoryview.__enter__(writable) is writable)
entered[0] = ord("Q")
print("enter-write", bytes(writable))
print("exit", repr(writable.__exit__()), repr(writable.__exit__()))

with memoryview(b"ghi") as seen:
    print("with-inside", bytes(seen), seen.readonly)
print("with-after", repr(seen).startswith("<released memory at 0x"))

try:
    with memoryview(b"jkl") as inside:
        raise KeyError("gone")
except KeyError as refusal:
    print("with-raise", type(refusal).__name__, str(refusal))
print("with-raise-after", repr(inside).startswith("<released memory at 0x"))

lengths = memoryview(b"mnop")
print("exit-args", repr(lengths.__exit__(None, None, None)))
attempt("exit-many", lambda: lengths.__exit__(1, 2, 3))


# --- the three rows: arity, keywords, signatures and order -------------------------------

live = memoryview(b"abc")
attempt("release-arg", lambda: live.release(1))
attempt("release-kw", lambda: live.release(foo=1))
attempt("release-both", lambda: live.release(1, foo=2))
attempt("enter-arg", lambda: live.__enter__(1))
attempt("enter-kw", lambda: live.__enter__(foo=1))
attempt("exit-kw", lambda: live.__exit__(foo=1))

for name in ("release", "__enter__", "__exit__"):
    member = memoryview.__dict__[name]
    print("row", name, repr(member.__text_signature__), repr(member.__doc__))

print("bound-release-doc", repr(live.release.__doc__))
print("named", live.release.__name__, live.release.__qualname__)
print(
    "order",
    [
        name
        for name in list(memoryview.__dict__)
        if name in ("__delitem__", "release", "__enter__", "__exit__", "obj", "__doc__")
    ],
)
