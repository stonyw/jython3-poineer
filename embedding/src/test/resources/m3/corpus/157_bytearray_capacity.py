"""bytearray's second segment: the allocation and the methods that change it.

__alloc__ and __sizeof__ report the capacity the sequence operations grow and shrink, which
makes the arithmetic behind append, extend, insert, pop, remove, clear, resize and copy
visible. The refusals are here too: which type of index each of the three position-taking
methods reads, the order in which pop reads it and asks whether the run is empty, the
all-or-nothing walk extend makes, and the one call that fails for a reason of its own -- a
non-empty bytearray added to itself.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


class Index:
    def __index__(self):
        return 2


class Wide:
    def __index__(self):
        return 2 ** 100


class Push:
    def __iter__(self):
        for value in (65, 300):
            print("  Push yields", value)
            yield value


print("size-empty", bytearray().__alloc__(), bytearray().__sizeof__())
print("size-three", bytearray(3).__alloc__(), bytearray(3).__sizeof__())
print("size-bytes", bytearray(b"ab").__alloc__(), bytearray(b"ab").__sizeof__())
print("nonzero", bytearray(3))

growing = bytearray()
print("grow-start", growing.__alloc__(), growing.__sizeof__())
for value in range(10):
    growing.append(65 + value)
    print("grow", len(growing), growing.__alloc__(), growing)
print("grow-sizeof", growing.__sizeof__())

extended = bytearray(b"abc")
extended.extend(b"de")
print("extend-alloc", extended, extended.__alloc__())
extended.extend(b"f")
print("extend-alloc-more", extended, extended.__alloc__())
extended.extend(bytearray(b"g"))
print("extend-bytearray-alloc", extended, extended.__alloc__())

inserted = bytearray(b"ab")
inserted.insert(1, 65)
print("insert-alloc", inserted, inserted.__alloc__())
inserted = bytearray(b"abc")
inserted.insert(3, 65)
print("insert-end-alloc", inserted, inserted.__alloc__())

popped = bytearray(b"abc")
popped.pop()
print("pop-alloc", popped, popped.__alloc__())
popped = bytearray(b"abc")
popped.pop(0)
print("pop-front-alloc", popped, popped.__alloc__())
removed = bytearray(b"abc")
removed.remove(98)
print("remove-alloc", removed, removed.__alloc__())
reversed_run = bytearray(b"abc")
reversed_run.reverse()
print("reverse-alloc", reversed_run, reversed_run.__alloc__())
cleared = bytearray(b"abc")
cleared.clear()
print("clear-alloc", cleared, cleared.__alloc__())
cleared = bytearray()
cleared.clear()
print("clear-empty-alloc", cleared, cleared.__alloc__())

print("copy", bytearray(b"ab").copy())
print("copy-alloc", bytearray(b"ab").copy().__alloc__())
print("copy-empty", bytearray().copy(), bytearray().copy().__alloc__())
print("copy-three", bytearray(3).copy(), bytearray(3).copy().__alloc__())
source = bytearray(b"ab")
clone = source.copy()
print("copy-equal", source == clone, source is clone, type(clone).__name__)

written = bytearray(b"abc")
written[0] = 65
print("setitem-alloc", written, written.__alloc__())
written = bytearray(b"abc")
del written[0]
print("delitem-alloc", written, written.__alloc__())
written = bytearray(b"abc")
del written[0:2]
print("delslice-alloc", written, written.__alloc__())
written = bytearray(b"abc")
written[0:1] = b"xyz"
print("setslice-alloc", written, written.__alloc__())
written = bytearray(b"ab")
written += b"cd"
print("iadd-alloc", written, written.__alloc__())
written = bytearray(b"ab")
written += written
print("iadd-self-error-state", written)

grow = bytearray(b"ab")
print("resize-return", grow.resize(6))
print("resize-grow", grow, grow.__alloc__())
print("resize-shrink", grow.__alloc__())
grow.resize(1)
print("resize-shrink-value", grow, grow.__alloc__())
grow.resize(0)
print("resize-zero", grow, grow.__alloc__())
grow = bytearray(b"abc")
grow.resize(1)
print("resize-keeps-capacity", grow, grow.__alloc__())
grow = bytearray(b"abc")
print("resize-same", grow.resize(3), grow, grow.__alloc__())
print("resize-bool", (lambda run: (run.resize(True), run, run.__alloc__()))(bytearray(b"abcd")))
attempt("resize-negative", lambda: bytearray().resize(-1))
attempt("resize-text", lambda: bytearray().resize("2"))
attempt("resize-float", lambda: bytearray().resize(1.0))
attempt("resize-wide", lambda: bytearray().resize(2 ** 63))
attempt("resize-wider", lambda: bytearray().resize(2 ** 100))
attempt("resize-index-class", lambda: (lambda run: (run.resize(Index()), run))(bytearray(b"ab")))
attempt("resize-wide-class", lambda: bytearray().resize(Wide()))
attempt("resize-no-argument", lambda: bytearray().resize())
attempt("resize-two-arguments", lambda: bytearray().resize(1, 2))
attempt("resize-keyword", lambda: bytearray().resize(size=1))

appended = bytearray(b"ab")
print("append-return", appended.append(99))
print("append", appended)
print("append-true", (lambda run: (run.append(True), run))(bytearray(b"a")))
attempt("append-too-large", lambda: bytearray().append(256))
attempt("append-negative", lambda: bytearray().append(-1))
attempt("append-float", lambda: bytearray().append(1.5))
attempt("append-text", lambda: bytearray().append("a"))
attempt("append-wide", lambda: bytearray().append(2 ** 100))
attempt("append-no-argument", lambda: bytearray().append())
attempt("append-two-arguments", lambda: bytearray().append(1, 2))
attempt("append-keyword", lambda: bytearray().append(item=65))

sources = bytearray(b"ab")
print("extend-return", sources.extend(b"cd"))
print("extend-bytes", sources)
print("extend-list", (lambda run: (run.extend([65, 66]), run))(bytearray(b"ab")))
print("extend-tuple", (lambda run: (run.extend((65, 66)), run))(bytearray(b"ab")))
print("extend-range", (lambda run: (run.extend(range(3)), run))(bytearray(b"ab")))
print("extend-generator", (lambda run: (run.extend(v for v in b"cd"), run))(bytearray(b"ab")))
print("extend-empty", (lambda run: (run.extend(b""), run))(bytearray(b"ab")))
print("extend-self", (lambda run: (run.extend(run), run, run.__alloc__()))(bytearray(b"xy")))
print("extend-self-empty", (lambda run: (run.extend(run), run, run.__alloc__()))(bytearray()))
print("extend-bytearray-self", (lambda run: (run.extend(run[:]), run))(bytearray(b"xy")))
failed = bytearray(b"ab")
attempt("extend-bad-list", lambda: failed.extend([65, 300]))
print("extend-bad-list-state", failed, failed.__alloc__())
failed = bytearray(b"ab")
attempt("extend-bad-generator", lambda: failed.extend(Push()))
print("extend-bad-generator-state", failed, failed.__alloc__())
attempt("extend-text", lambda: bytearray().extend("ab"))
attempt("extend-int", lambda: bytearray().extend(1))
attempt("extend-none", lambda: bytearray().extend(None))
attempt("extend-float", lambda: bytearray().extend(1.5))
attempt("extend-no-argument", lambda: bytearray().extend())
attempt("extend-keyword", lambda: bytearray().extend(iterable_of_ints=b"a"))

target = bytearray(b"abc")
print("insert-return", target.insert(1, 90))
print("insert", target)
print("insert-front", (lambda run: (run.insert(0, 65), run))(bytearray(b"ab")))
print("insert-end", (lambda run: (run.insert(2, 67), run))(bytearray(b"ab")))
print("insert-negative", (lambda run: (run.insert(-1, 65), run))(bytearray(b"ab")))
print("insert-negative-clamp", (lambda run: (run.insert(-100, 65), run))(bytearray(b"ab")))
print("insert-large-clamp", (lambda run: (run.insert(100, 65), run))(bytearray(b"ab")))
print("insert-index-class", (lambda run: (run.insert(Index(), 65), run))(bytearray(b"ab")))
attempt("insert-float-index", lambda: bytearray(b"ab").insert(1.5, 65))
attempt("insert-wide-index", lambda: bytearray(b"ab").insert(2 ** 100, 65))
attempt("insert-bad-item", lambda: bytearray(b"ab").insert(0, 300))
attempt("insert-index-first", lambda: bytearray(b"ab").insert(1.5, "x"))
attempt("insert-one-argument", lambda: bytearray(b"ab").insert(0))
attempt("insert-three-arguments", lambda: bytearray(b"ab").insert(0, 1, 2))
attempt("insert-keyword", lambda: bytearray(b"ab").insert(0, item=65))

run = bytearray(b"abc")
print("pop-return", run.pop())
print("pop", run)
print("pop-index", bytearray(b"abc").pop(1))
print("pop-negative", bytearray(b"abc").pop(-2))
print("pop-bool", bytearray(b"abc").pop(True))
print("pop-type", type(bytearray(b"a").pop()).__name__)
attempt("pop-empty", lambda: bytearray().pop())
attempt("pop-empty-negative", lambda: bytearray().pop(-1))
attempt("pop-out-of-range", lambda: bytearray(b"abc").pop(3))
attempt("pop-negative-out-of-range", lambda: bytearray(b"abc").pop(-4))
attempt("pop-float", lambda: bytearray(b"abc").pop(1.5))
attempt("pop-wide", lambda: bytearray(b"abc").pop(2 ** 100))
attempt("pop-index-first", lambda: bytearray().pop(1.5))
attempt("pop-two-arguments", lambda: bytearray(b"abc").pop(0, 1))
attempt("pop-keyword", lambda: bytearray(b"abc").pop(index=0))
attempt("pop-keyword-before-count", lambda: bytearray(b"abc").pop(0, 1, index=2))

run = bytearray(b"abcb")
print("remove-return", run.remove(98))
print("remove", run)
print("remove-first-only", (lambda value: (value.remove(97), value))(bytearray(b"aab")))
print("remove-true", (lambda value: (value.remove(True), value))(bytearray(b"aab")))
attempt("remove-missing", lambda: bytearray(b"abc").remove(97))
attempt("remove-too-large", lambda: bytearray(b"abc").remove(300))
attempt("remove-negative", lambda: bytearray(b"abc").remove(-1))
attempt("remove-text", lambda: bytearray(b"abc").remove("a"))
attempt("remove-float", lambda: bytearray(b"abc").remove(1.5))
attempt("remove-wide", lambda: bytearray(b"abc").remove(2 ** 100))
attempt("remove-no-argument", lambda: bytearray(b"abc").remove())
attempt("remove-keyword", lambda: bytearray(b"abc").remove(value=97))

print("clear-return", bytearray(b"ab").clear())
print("reverse-return", bytearray(b"ab").reverse())
print("reverse", (lambda run: (run.reverse(), run))(bytearray(b"abc")))
print("reverse-empty", (lambda run: (run.reverse(), run))(bytearray()))
print("reverse-single", (lambda run: (run.reverse(), run))(bytearray(b"a")))
print("reverse-palindrome", (lambda run: (run.reverse(), run))(bytearray(b"aba")))
attempt("clear-argument", lambda: bytearray(b"ab").clear(1))
attempt("reverse-argument", lambda: bytearray(b"ab").reverse(1))
attempt("copy-argument", lambda: bytearray(b"ab").copy(1))
attempt("alloc-argument", lambda: bytearray(b"ab").__alloc__(1))
attempt("sizeof-argument", lambda: bytearray(b"ab").__sizeof__(1))

empty = bytearray()
same = empty
empty += empty
print("iadd-self-empty", empty, empty.__alloc__(), empty is same)
value = bytearray(b"xy")
attempt("iadd-self", lambda: value.__iadd__(value))
print("iadd-self-state", value, value.__alloc__())
other = bytearray(b"xy")
attempt("iadd-equal-other", lambda: value.__iadd__(other))
print("iadd-equal-other-state", value)

for name in (
    "__alloc__",
    "__sizeof__",
    "copy",
    "clear",
    "reverse",
    "append",
    "extend",
    "insert",
    "pop",
    "remove",
    "resize",
):
    member = getattr(bytearray, name)
    print("member", name, member.__text_signature__, member.__doc__)
