"""memoryview's third segment: comparison, hashing and iteration.

A view compares by the octets its window selects, not by the object it came from: `view == b"abc"`
is true, a strided slice equals the contiguous run spelling the same elements, and a reversed slice
equals the reversed run. The six rich comparisons all come from one `tp_richcompare`, which answers
only the two equalities and declines the four orderings, so `<` raises the `TypeError` the fallback
invents. A value that exports no buffer is declined rather than compared, which is what makes
`view == "abc"` false through the reverse attempt while `view.__eq__("abc")` reads `NotImplemented`.

A read-only view hashes exactly as the octets it covers, which puts it in the same dictionary slot
as those `bytes`; a writable view is refused, because a key that can change under its own hash would
break the dictionary holding it. A strided or reversed view hashes its elements in the order it
presents them, since the reference copies a non-contiguous window into a scratch buffer first.

`iter(view)` answers a `memory_iterator`: the view, the length the walk started with and the index
reached so far. Each step reads through the window's stride, so a slice walks in slice order and a
writable view shows a write that lands after the cursor was made. The cursor carries no docstring, no
`__length_hint__` and no pickle pair, and its type cannot be instantiated. The released view's
identity-only comparisons and refusals belong to the segment that makes `release` reachable.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


mv = memoryview(b"abcdef")
source = bytearray(b"abcdef")
wv = memoryview(source)


# --- comparison is by selected octets, against any buffer --------------------------------

print("eq-bytes", mv == b"abcdef", b"abcdef" == mv)
print("eq-bytearray", mv == source, source == mv)
print("eq-memoryview", mv == memoryview(b"abcdef"), memoryview(b"abcdef") != mv)
print("eq-writable-view", mv == wv, wv == memoryview(bytearray(b"abcdef")))
print("eq-length", memoryview(b"ab") == memoryview(b"abc"), mv == memoryview(b"ab"))
print("eq-empty", memoryview(b"") == b"", memoryview(b"") == memoryview(b"")[0:0])
print("eq-self", mv == mv, mv.__eq__(mv))

sliced = mv[1:5]
print("eq-sliced", sliced == b"bcde", sliced == mv[1:5][:], sliced == b"bcdf")
print("eq-strided", mv[::2] == b"ace", mv[::2] == memoryview(b"ace"), mv[::2] == mv[1::2])
print("eq-reversed", mv[::-1] == b"fedcba", mv[::-1] == memoryview(b"fedcba"))
print("eq-mixed-stride", mv[1:5:2] == b"bd", mv[1:5:2] != b"bd")


# --- the slot declines what is not a buffer, and the orderings decline everything --------

for label, other in (("str", "abc"), ("none", None), ("list", [97, 98, 99]), ("int", 3)):
    print("eq-declined", label, repr(mv.__eq__(other)), repr(mv.__ne__(other)))
print("eq-slot-buffer", repr(mv.__eq__(b"abcdef")), repr(mv.__ne__(b"abcdef")))
attempt("lt-view", lambda: mv < memoryview(b"ab"))
attempt("lt-bytes", lambda: mv < b"abc")
attempt("ge-int", lambda: mv >= 3)


# --- only a read-only view has a hash, and it is the hash of its octets -----------------

print("hash-bytes", hash(mv) == hash(b"abcdef"), hash(memoryview(b"m")) == hash(b"m"))
print("hash-empty", hash(memoryview(b"")) == hash(b""))
print("hash-strided", hash(mv[::2]) == hash(b"ace"), hash(mv[::-1]) == hash(b"fedcba"))
print("hash-dict", {mv: "x"}[memoryview(b"abcdef")], memoryview(b"abcdef") in {mv: "x"})
print("hash-dict-size", len({memoryview(b"a"): 1, memoryview(b"a"): 2}))
print("hash-set-size", len({memoryview(b"a"), memoryview(b"a"), memoryview(b"b")}))
attempt("hash-writable", lambda: hash(wv))
attempt("hash-writable-key", lambda: {wv: 1})


# --- iter(view) walks the elements in the order the window presents them ----------------

it = iter(mv)
print("iter-type", type(it).__name__, type(it).__qualname__, type(it).__module__)
print("iter-repr", repr(it).startswith("<memory_iterator object at 0x"),
      repr(it).endswith(">"))
print("iter-self", it.__iter__() is it, iter(it) is it)
print("iter-order", next(it), it.__next__(), next(it))
print("iter-rest", list(it))
attempt("iter-stop", lambda: next(it))
attempt("iter-stop-again", lambda: next(it))
print("iter-slices", list(mv[::2]), list(mv[::-1]), list(mv[1:5:2]), list(memoryview(b"")))
print("iter-loop", [value for value in mv[1:5:2]])
print("iter-default", next(iter(memoryview(b"")), "end"))

print("iter-dict", list(type(it).__dict__))
print("iter-doc", type(it).__doc__)
print("iter-iter-doc", repr(type(it).__dict__["__iter__"].__doc__))
print("iter-next-doc", repr(type(it).__dict__["__next__"].__doc__))
attempt("iter-instantiate", lambda: type(it)())
attempt("iter-new", lambda: type(it).__new__(type(it)))


# --- a step reads the exporter, so a later write is seen, and a spent walk stays spent --

live = bytearray(b"abcdef")
walk = memoryview(live)
cursor = iter(walk)
print("iter-live-first", next(cursor))
live[1] = 122
print("iter-live-rest", list(cursor))

clipped = memoryview(bytearray(b"abcdef"))[1:5:2]
write = memoryview(clipped.obj)
write[3] = 90
print("iter-window-write", list(clipped))

spent = iter(memoryview(b"ab"))
next(spent)
next(spent)
attempt("iter-spent", lambda: next(spent))
attempt("iter-spent-default", lambda: next(spent, "default"))


# --- the dictionary rows this segment files ---------------------------------------------

rows = [name for name in ("__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__",
                          "__iter__", "__len__") if name in memoryview.__dict__]
print("dict-rows", rows)
print("dict-docs", repr(memoryview.__dict__["__hash__"].__doc__),
      repr(memoryview.__dict__["__eq__"].__doc__),
      repr(memoryview.__dict__["__iter__"].__doc__),
      repr(memoryview.__dict__["__len__"].__doc__))
attempt("dict-eq-call", lambda: memoryview.__eq__(mv, b"abcdef"))
attempt("dict-eq-declined", lambda: memoryview.__eq__(mv, "abc"))
