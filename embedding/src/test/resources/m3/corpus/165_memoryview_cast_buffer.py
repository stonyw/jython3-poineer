"""memoryview's fourth segment, second half, part two: cast, toreadonly and the buffer protocol.

`cast` builds another header over the same run. It reads its shape before its format, refuses a
window it cannot walk consecutively first of all, and allows a destination wider than one octet only
when the source is one octet wide — the reference's "cannot cast between two non-byte formats". The
one restriction on the shape is direction: a shape may only be supplied to a one-dimensional source,
and a two-dimensional one may only be flattened with no shape at all.

`toreadonly` always answers a fresh header, even when the window was read-only already; it is a copy
of the header with the write bit pinned, not the same object.

`__buffer__` is the export end of the protocol. It reads the flags as a C `int`, refuses a writable
request over a read-only window, refuses a format request that does not also ask for the shape, and
refuses a strided window that did not ask for strides. Every export is counted, and `release` refuses
to spend a window while one is alive. `__release_buffer__` is the other end, and only the bytearray
takes its own exports back: bytes has the export row and no release row at all.

`_from_flags` is the constructor the protocol reaches. It ignores the flags when its argument is a
view, refuses only the writable bit for bytes, and hands back a writable window for a bytearray.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


def heads(view):
    return view.format, view.itemsize, view.shape, view.strides


# --- cast: the format alone ---------------------------------------------------------------

data = b"abcdefgh"
mv = memoryview(data)
print("basic", heads(mv), mv.tolist())

for code in ("B", "b", "c", "?", "H", "h", "I", "i", "L", "l", "Q", "q", "f", "d", "e"):
    try:
        view = mv.cast(code)
    except Exception as refusal:
        print("cast", code, type(refusal).__name__, str(refusal))
        continue
    print("cast", code, heads(view), view.tobytes() == data, view.nbytes)

for code in ("@B", "@H", "@I", "@P", "@n"):
    view = mv.cast(code)
    print("native", code, heads(view), view.nbytes)

print("unsigned", mv.cast("B").tolist())
print("short", mv.cast("H").tolist())
print("float", memoryview(b"\x00\x00\x80\x3f").cast("f").tolist())
print("double", memoryview(b"\x00\x00\x00\x00\x00\x00\xf0\x3f").cast("d").tolist())
print("half", memoryview(b"\x00\x3c").cast("e").tolist())
print("bool", memoryview(b"\x00\x01").cast("?").tolist())
print("char", memoryview(b"ab").cast("c").tolist())
print("signed", memoryview(b"\xff").cast("b").tolist(), memoryview(b"\xff").cast("B").tolist())


# --- cast: the shape ----------------------------------------------------------------------

two = mv.cast("B", (2, 4))
print("2d", heads(two), len(two), two.tolist(), two[1, 2], two[0, 0])
print("2d-reverse", heads(mv.cast("B", [4, 2])), mv.cast("B", [4, 2]).tolist())
attempt("empty-shape", lambda: memoryview(b"").cast("B", ()))
attempt("empty-shape-buffer", lambda: mv.cast("B", ()))
print("flat", heads(two.cast("B")), two.cast("B").tolist())
print("flat-wide", heads(two.cast("H")), two.cast("H").tolist())
print("reshape", heads(mv.cast("H", (2, 2))), mv.cast("H", (2, 2)).tolist())
print("two-d", heads(mv.cast("I", (2,))), mv.cast("I", (2,)).tolist())


# --- cast: writes reach the run -----------------------------------------------------------

run = bytearray(b"abcdefgh")
wide = memoryview(run).cast("H")
wide[1] = 0x4241
print("write", run, wide.tolist())
wide2 = memoryview(run).cast("B", (2, 4))
wide2[1, 0] = ord("Z")
print("write-2d", run, wide2.tolist())


# --- cast: the refusals, in the reference's order -----------------------------------------

strided = memoryview(bytearray(b"abcdefgh"))[::2]
attempt("cast-strided", lambda: strided.cast("B"))
attempt("cast-both-formats", lambda: mv.cast("H").cast("I"))
attempt("cast-both-formats-2d", lambda: mv.cast("H").cast("H", (2, 2)))
attempt("cast-2d-to-2d", lambda: two.cast("B", (2, 4)))
print("cast-2d-to-1d", heads(two.cast("B", (8,))), two.cast("B", (8,)).tolist())
attempt("cast-zero", lambda: mv.cast("B", (0,)))
attempt("cast-negative", lambda: mv.cast("B", (-1,)))
attempt("cast-product", lambda: mv.cast("H", (3,)))
attempt("cast-multiple", lambda: memoryview(b"abc").cast("H"))
attempt("cast-empty-ok", lambda: heads(memoryview(b"").cast("H")))
attempt("cast-empty-shape", lambda: memoryview(b"").cast("B", (1,)))
attempt("cast-format-empty", lambda: mv.cast(""))
attempt("cast-format-long", lambda: mv.cast("BB"))
attempt("cast-format-double-at", lambda: mv.cast("@@B"))
attempt("cast-format-unknown", lambda: mv.cast("z"))
attempt("cast-shape-int", lambda: mv.cast("B", 3))
attempt("cast-shape-float", lambda: mv.cast("B", (1.5,)))
attempt("cast-shape-big", lambda: mv.cast("B", (2**70,)))
attempt("cast-shape-bool", lambda: heads(mv.cast("B", (True,))))
attempt("cast-missing", lambda: mv.cast())
attempt("cast-extra", lambda: mv.cast("B", (8,), 1))
attempt("cast-keyword", lambda: heads(mv.cast(format="H")))
attempt("cast-shape-keyword", lambda: heads(mv.cast("B", shape=(2, 4))))
attempt("cast-unknown", lambda: mv.cast("B", foo=1))
attempt("cast-format-type", lambda: mv.cast(1))


# --- toreadonly ---------------------------------------------------------------------------

print("ro-bytes", heads(mv.toreadonly()), mv.toreadonly().readonly, mv.readonly)
live = bytearray(b"abc")
writable = memoryview(live)
pinned = writable.toreadonly()
print("ro-new", pinned is writable, pinned.readonly, writable.readonly, pinned.obj is live)
print("ro-slice", pinned[1:].readonly, mv.toreadonly().toreadonly().readonly)
writable[0] = ord("A")
print("ro-write-through", pinned.tobytes(), live)
attempt("ro-refuse", lambda: pinned.__setitem__(0, 66))
attempt("ro-args", lambda: mv.toreadonly(1))
attempt("ro-keyword", lambda: mv.toreadonly(foo=1))
print("ro-class", memoryview.toreadonly(writable).readonly)


# --- __buffer__: the two shapes of header -------------------------------------------------

buffer_data = bytearray(b"abcdefgh")
parent = memoryview(buffer_data)
flat = parent.__buffer__(0)
print("buffer-flat", type(flat).__name__, flat.obj is parent, heads(flat), flat.tolist())
shaped = parent.cast("H").__buffer__(8)
print("buffer-shape", shaped.obj is parent.cast("H"), heads(shaped), shaped.tolist())
plain = parent.cast("H").__buffer__(0)
print("buffer-plain", heads(plain), plain.tolist())
print("buffer-bytes", b"abcd".__buffer__(0).readonly, b"abcd".__buffer__(0).tolist())
print("buffer-bytearray", bytearray(b"abcd").__buffer__(1).readonly)
attempt("buffer-writable", lambda: mv.__buffer__(1))
attempt("buffer-format", lambda: mv.__buffer__(4))
attempt("buffer-strided", lambda: parent[::2].__buffer__(8))
attempt(
    "buffer-strided-strides",
    lambda: heads(parent[::2].__buffer__(24)),
)
attempt(
    "buffer-strided-nd",
    lambda: heads(parent.cast("B", (2, 4)).__buffer__(8)),
)
attempt("buffer-flags-type", lambda: mv.__buffer__("x"))
attempt("buffer-flags-big", lambda: mv.__buffer__(2**70))
attempt("buffer-flags-cint", lambda: mv.__buffer__(2**40))
print("buffer-class", memoryview.__buffer__(parent, 0).tolist())


# --- release counting and __release_buffer__ ----------------------------------------------

host = bytearray(b"abcdefgh")
exporting = memoryview(host).cast("B", (2, 4))
first = exporting.__buffer__(8)
second = exporting.__buffer__(0)
attempt("release-two", lambda: exporting.release())
attempt("release-child", lambda: first.release())
attempt("release-one", lambda: exporting.release())
attempt("release-taken", lambda: exporting.__release_buffer__(first))
attempt("release-whole", lambda: exporting.release())
attempt("release-other", lambda: exporting.__release_buffer__(memoryview(host)))
attempt("release-not-view", lambda: exporting.__release_buffer__(b"x"))
attempt("release-spent-export", lambda: first.tolist())
print("buffer-release-returns", repr(exporting.__release_buffer__(second)))
attempt("release-after", lambda: exporting.release())
print("buffer-released", exporting.released if hasattr(exporting, "released") else "no-attr")


# --- _from_flags --------------------------------------------------------------------------

print("flags-bytes", heads(memoryview._from_flags(b"abc", 0)), memoryview._from_flags(b"abc", 0).readonly)
print("flags-bytes-format", heads(memoryview._from_flags(b"abc", 4)))
attempt("flags-bytes-writable", lambda: memoryview._from_flags(b"abc", 1))
buffer_run = bytearray(b"abc")
flags_writable = memoryview._from_flags(buffer_run, 1)
flags_writable[0] = ord("A")
print("flags-bytearray", flags_writable.readonly, buffer_run, heads(flags_writable))
source = memoryview(b"abc")
copied = memoryview._from_flags(source, 1)
print("flags-view", copied is source, copied.readonly, copied.obj == b"abc", heads(copied))
attempt("flags-str", lambda: memoryview._from_flags("abc", 0))
attempt("flags-int", lambda: memoryview._from_flags(1, 0))
attempt("flags-none", lambda: memoryview._from_flags(None, 0))
attempt("flags-missing", lambda: memoryview._from_flags())
attempt("flags-missing-one", lambda: memoryview._from_flags(b"a"))
attempt("flags-extra", lambda: memoryview._from_flags(b"a", 0, 1))
attempt("flags-type", lambda: memoryview._from_flags(b"a", "x"))
attempt("flags-float", lambda: memoryview._from_flags(b"a", 1.0))
attempt("flags-big", lambda: memoryview._from_flags(b"a", 2**70))
print("flags-keywords", memoryview._from_flags(object=b"a", flags=0).tolist())
print("flags-class", memoryview._from_flags(memoryview.__buffer__(memoryview(b"a"), 0), 0).readonly)


# --- signatures, docs and filing ----------------------------------------------------------

for name in ("cast", "toreadonly", "_from_flags", "__buffer__", "__release_buffer__"):
    row = getattr(memoryview, name)
    doc = row.__doc__
    print(
        "row",
        name,
        row.__text_signature__ if hasattr(row, "__text_signature__") else "-",
        doc.splitlines()[0] if doc else None,
        type(row).__name__,
    )

print(
    "order",
    [
        name
        for name in memoryview.__dict__
        if name in ("__buffer__", "__release_buffer__", "cast", "toreadonly", "_from_flags")
    ],
)
print("bytes", hasattr(bytes, "__release_buffer__"), hasattr(bytes, "__buffer__"))
print("bytearray", hasattr(bytearray, "__release_buffer__"), hasattr(bytearray, "__buffer__"))
