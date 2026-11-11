"""memoryview's second segment: subscripts, slices, and the shape and strides they report.

A slice of a view is another header over the same exporter, not a copy: the start folds into the
offset, the step multiplies the stride, and `strides` therefore reports the distance between the
elements the slice selects. The reference keeps that stride in a machine word, so a step wider than
the word saturates at the word's edge and a stride that overflows on a nested slice wraps.

Reads are refused in three shapes this view does not have: an empty tuple would be a sub-view, a
longer tuple names dimensions the view lacks, and a slice inside a tuple is multi-dimensional
slicing. Writes add the read-only refusal, which comes before the key is read at all, and deletion,
which is never allowed whatever the key. A slice assignment reads its source into a snapshot first,
so an overlapping assignment moves the run instead of smearing it.

Contents are read back one element at a time: `__iter__` and `tobytes` belong to later segments.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


def elements(view):
    return [view[i] for i in range(len(view))]


source = b"abcdefgh"
mv = memoryview(source)
run = bytearray(b"abcdefgh")
wv = memoryview(run)


def put(key, value):
    run[:] = b"abcdefgh"
    wv[key] = value
    return bytes(run)


class Index:
    def __index__(self):
        return 2


class Value:
    def __index__(self):
        return 68


# --- one element at a time -----------------------------------------------------------------

print("element", mv[0], mv[3], mv[-1], mv[-8])
print("bool-index", mv[True], mv[False])
print("index-object", mv[Index()])
attempt("index-past-end", lambda: mv[8])
attempt("index-past-start", lambda: mv[-9])
attempt("index-huge", lambda: mv[2**70])
attempt("index-huge-negative", lambda: mv[-(2**70)])
for label, key in (
        ("str", "a"),
        ("none", None),
        ("float", 1.5),
        ("list", [0]),
        ("bytes", b"a"),
        ("memoryview", memoryview(b"a"))):
    attempt("bad-key-" + label, lambda key=key: mv[key])


# --- a one-dimensional view spells the same key as a one-tuple -----------------------------

print("tuple-one", mv[(0,)], mv[(3,)], mv[(-1,)])
print("tuple-bool", mv[(True,)])
print("tuple-index-object", mv[(Index(),)])
attempt("tuple-empty", lambda: mv[()])
attempt("tuple-two", lambda: mv[0, 1])
attempt("tuple-three", lambda: mv[0, 1, 2])
attempt("tuple-slice", lambda: mv[0:2,])
attempt("tuple-bad", lambda: mv["a",])
attempt("tuple-nested", lambda: mv[((0,),)])
attempt("tuple-huge", lambda: mv[(2**70,)])


# --- slices are headers, and their geometry is the geometry of what they select ------------

print("slice-plain", elements(mv[2:5]), len(mv[2:5]))
print("slice-geometry", mv[2:5].shape, mv[2:5].strides, mv[2:5].nbytes)
print("slice-header", mv[2:5].readonly, mv[2:5].format, mv[2:5].ndim,
      mv[2:5].itemsize, mv[2:5].suboffsets)
print("slice-obj", mv[2:5].obj is source)
print("slice-step", elements(mv[::2]), mv[::2].shape, mv[::2].strides, mv[::2].nbytes)
print("slice-reverse", elements(mv[::-1]), mv[::-1].shape, mv[::-1].strides)
print("slice-reverse-window", elements(mv[5:1:-1]), mv[5:1:-1].shape, mv[5:1:-1].strides)
print("slice-empty", len(mv[5:1]), mv[5:1].shape, mv[5:1].strides)
print("slice-empty-reverse", len(mv[1:5:-1]), mv[1:5:-1].shape, mv[1:5:-1].strides)
print("slice-clipped", elements(mv[-100:100]), mv[-100:100].shape)
print("slice-open", elements(mv[:]), mv[:].strides)
print("slice-single", mv[3:4].shape, mv[3:4].strides, mv[3:4].c_contiguous)
print("slice-zero", mv[3:3].shape, mv[3:3].strides, mv[3:3].c_contiguous)
print("slice-slice", elements(mv[1:7][::2]), mv[1:7][::2].shape, mv[1:7][::2].strides)
print("slice-reverse-step", elements(mv[::-1][1:5:2]), mv[::-1][1:5:2].strides)
sliced = memoryview(mv[2:6])
print("slice-of-view", elements(sliced), sliced.shape, sliced.strides, sliced.obj is source)


# --- the three contiguity flags are one question in one dimension --------------------------

for label, view in (
        ("plain", mv),
        ("step-one", mv[1:5]),
        ("step-two", mv[::2]),
        ("reverse", mv[::-1]),
        ("single", mv[3:4]),
        ("empty", mv[3:3])):
    print("contiguous", label, view.c_contiguous, view.f_contiguous, view.contiguous)


# --- a step wider than a machine word saturates, and a nested stride wraps -----------------

print("huge-step", mv[::2**70].shape, mv[::2**70].strides, mv[::2**70].nbytes)
print("huge-step-negative", mv[::-(2**70)].shape, mv[::-(2**70)].strides)
print("huge-step-nested", mv[::2**70][::2**70].strides, mv[::2**70][::-1].strides)
print("huge-bound", mv[2**70:].shape, mv[:2**70].shape, mv[-(2**70):].shape)
attempt("step-zero", lambda: mv[::0])


# --- a view of bytes refuses every write and every deletion --------------------------------

for label, key, value in (
        ("index", 0, 65),
        ("negative", -1, 65),
        ("tuple", (0,), 65),
        ("slice", slice(0, 2), b"AB"),
        ("tuple-slice", (slice(0, 2),), b"AB"),
        ("bad-key", "a", 1)):
    attempt("readonly-store-" + label,
            lambda key=key, value=value: mv.__setitem__(key, value))
for label, key in (
        ("index", 0),
        ("slice", slice(0, 1)),
        ("tuple", ()),
        ("bad", "x")):
    attempt("readonly-del-" + label, lambda key=key: mv.__delitem__(key))


# --- one element of a writable view --------------------------------------------------------

print("write-element", put(0, 65), put(-1, 66), put(True, 67))
print("write-index-object", put(0, Value()))
attempt("write-bad-type", lambda: put(0, b"A"))
attempt("write-float", lambda: put(0, 1.0))
attempt("write-str", lambda: put(0, "A"))
attempt("write-value-256", lambda: put(0, 256))
attempt("write-value-negative", lambda: put(0, -1))
attempt("write-value-huge", lambda: put(0, 2**70))
attempt("write-index-past-end", lambda: put(8, 65))
attempt("write-index-huge", lambda: put(2**70, 65))
attempt("write-invalid-key", lambda: put("a", 65))


# --- the tuple form of a write -------------------------------------------------------------

print("write-tuple", put((0,), 65))
attempt("write-tuple-slice", lambda: put((slice(0, 2),), b"AB"))
attempt("write-tuple-slice-bad-value", lambda: put((slice(0, 2),), "AB"))
attempt("write-tuple-empty", lambda: put((), b""))
attempt("write-tuple-two", lambda: put((0, 1), b"AB"))
attempt("write-tuple-bad", lambda: put(("a",), 65))
attempt("write-tuple-value", lambda: put((0,), b"A"))


# --- a slice write copies a snapshot, and the source may be strided or reversed -------------

print("write-slice", put(slice(2, 5), b"XYZ"))
print("write-slice-step", put(slice(None, None, 2), b"XYZW"))
print("write-slice-reverse", put(slice(None, None, -1), b"12345678"))
print("write-slice-clipped", put(slice(-2, None), b"YZ"))
print("write-slice-empty", put(slice(2, 2), b""))
print("write-slice-empty-reverse", put(slice(2, 5, -1), b""))
print("write-slice-bytes-source", put(slice(0, 2), b"pq"))
print("write-slice-bytearray-source", put(slice(0, 2), bytearray(b"pq")))
print("write-slice-memoryview-source", put(slice(0, 2), memoryview(b"pq")))
print("write-slice-reversed-source", put(slice(1, 3), memoryview(b"PQ")[::-1]))
print("write-slice-strided-source", put(slice(1, 3), memoryview(b"PQRS")[::2]))
attempt("write-slice-short", lambda: put(slice(0, 4), b"abc"))
attempt("write-slice-long", lambda: put(slice(0, 2), b"abc"))
attempt("write-slice-str", lambda: put(slice(0, 2), "ab"))
attempt("write-slice-list", lambda: put(slice(0, 2), [65, 66]))
attempt("write-slice-int", lambda: put(slice(0, 2), 65))
attempt("write-slice-step-zero", lambda: put(slice(None, None, 0), b"abcdefgh"))
attempt("write-slice-step-zero-bad", lambda: put(slice(None, None, 0), "x"))


# --- overlapping assignments move the run, because the source is read first ---------------

run[:] = b"abcdefgh"
wv[1:4] = wv[0:3]
print("write-overlap", bytes(run))
run[:] = b"abcdefgh"
wv[:] = wv[::-1]
print("write-overlap-reverse", bytes(run))
run[:] = b"abcdefgh"
wv[::2] = wv[1::2]
print("write-overlap-stride", bytes(run))


# --- a slice is a view in its own right, and writing through it reaches the exporter -------

run[:] = b"abcdefgh"
part = wv[1:5]
part[0] = 88
part[-1] = 89
part[1:3] = b"MN"
print("write-through-slice", bytes(run), bytes(part.obj), part.readonly)

run[:] = b"abcdefgh"
again = memoryview(wv[1:5:2])
again[0] = 90
print("write-through-header", bytes(run), again.shape, again.strides, again.readonly)


# --- deletion is refused in every form, on a writable view too ------------------------------

for label, key in (
        ("index", 0),
        ("negative", -1),
        ("slice", slice(0, 1)),
        ("tuple", ()),
        ("bad", "x"),
        ("huge", 2**70)):
    attempt("del-" + label, lambda key=key: wv.__delitem__(key))


# --- the book, as far as this slice files it ------------------------------------------------

filled = [
    "__new__", "__repr__", "__len__", "__getitem__", "__setitem__", "__delitem__",
    "obj", "nbytes", "readonly", "itemsize", "format", "ndim",
    "shape", "strides", "suboffsets", "c_contiguous", "f_contiguous", "contiguous",
    "__doc__",
]
present = [name for name in memoryview.__dict__ if name in filled]
print("book-prefix", present == filled)
for name in ("__getitem__", "__setitem__", "__delitem__"):
    member = memoryview.__dict__[name]
    print("slot", name, repr(member.__text_signature__), repr(member.__doc__))
