"""bytearray's first segment: the skeleton and the sequence protocol.

The writable twin of bytes, as far as reading goes: the constructor's six sources, the repr
that quotes bytes rather than characters, length, indexing both ways, slicing, containment,
iteration, comparison with bytes in either direction, concatenation and repetition, and the
unhashable marker. __init__ is the same filling run over a value that already exists, which is
why a refused source leaves it empty and a generator source can leave half a run behind.

Not here yet, by design: __alloc__, __sizeof__, copy, resize, append, extend, insert, pop,
remove, clear, reverse, the forty-one read-only bytes methods, hex/fromhex/maketrans/translate,
int() conversion, __mod__, __buffer__ and __release_buffer__.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


class Octets:
    def __iter__(self):
        return iter([65, 66, 67])


class Push:
    def __iter__(self):
        for value in (65, 300):
            print("  Push yields", value)
            yield value


class Refuses:
    def __iter__(self):
        raise TypeError("not this one")


class Index:
    def __index__(self):
        return 3


class Wide:
    def __index__(self):
        return 2 ** 100


attempt("empty", lambda: bytearray())
attempt("int", lambda: bytearray(3))
attempt("int-true", lambda: bytearray(True))
attempt("int-false", lambda: bytearray(False))
attempt("int-index", lambda: bytearray(Index()))
attempt("int-negative", lambda: bytearray(-1))
attempt("int-wide", lambda: bytearray(2 ** 100))
attempt("int-wide-negative", lambda: bytearray(-(2 ** 100)))
attempt("bytes", lambda: bytearray(b"ab"))
attempt("bytes-empty", lambda: bytearray(b""))
attempt("bytearray", lambda: bytearray(bytearray(b"ab")))
attempt("list", lambda: bytearray([1, 2, 3]))
attempt("tuple", lambda: bytearray((1, 2, 3)))
attempt("range", lambda: bytearray(range(3)))
attempt("generator", lambda: bytearray((value for value in range(3))))
attempt("set", lambda: bytearray({1, 2, 3}))
attempt("dict", lambda: bytearray({1: 0, 2: 0, 3: 0}))
attempt("class-iter", lambda: bytearray(Octets()))
attempt("text", lambda: bytearray("ab", "ascii"))
attempt("text-replace", lambda: bytearray("\xff", "ascii", "replace"))
attempt("text-ignore", lambda: bytearray("\xff", "ascii", "ignore"))
attempt("text-keyword", lambda: bytearray(source="ab", encoding="ascii"))
attempt("text-name-only", lambda: bytearray("ab", "utf-8", "strict"))
attempt("float", lambda: bytearray(1.5))
attempt("class-refuses", lambda: bytearray(Refuses()))
attempt("item-too-large", lambda: bytearray([256]))
attempt("item-not-int", lambda: bytearray(["a"]))
attempt("text-without-encoding", lambda: bytearray("a"))
attempt("text-errors-only", lambda: bytearray("a", errors="strict"))
attempt("encoding-without-text", lambda: bytearray(encoding="ascii"))
attempt("encoding-without-text-bytes", lambda: bytearray(b"", encoding="ascii"))
attempt("errors-without-text", lambda: bytearray(errors="strict"))
attempt("encoding-not-str", lambda: bytearray("a", None))
attempt("too-many", lambda: bytearray(b"a", "ascii", "strict", "x"))
attempt("unexpected-keyword", lambda: bytearray(foo=1))

print("repr", repr(bytearray(b"abc")))
print("repr-empty", repr(bytearray()))
print("repr-single", repr(bytearray(b"a'b")))
print("repr-double", repr(bytearray(b'a"b')))
print("repr-both", repr(bytearray(b"a'\"b")))
print("repr-escapes", repr(bytearray(b"a\\b\t\n\r\x00\x7f\xff")))
print("str", str(bytearray(b"ab")))

print("len", len(bytearray(b"abc")))
print("index", bytearray(b"abc")[1], bytearray(b"abc")[-1])
attempt("index-out-of-range", lambda: bytearray(b"abc")[5])
attempt("index-negative-out-of-range", lambda: bytearray(b"abc")[-4])
attempt("index-not-int", lambda: bytearray(b"abc")["x"])
print("slice", bytearray(b"abcdef")[1:5:2])
print("slice-reverse", bytearray(b"abcdef")[::-1])
print("slice-type", type(bytearray(b"abc")[1:]).__name__)
print("index-type", type(bytearray(b"abc")[0]).__name__)
print("contains", b"ab" in bytearray(b"abc"), 98 in bytearray(b"abc"))
print("contains-empty", b"" in bytearray(b"abc"))
attempt("contains-out-of-range", lambda: 300 in bytearray(b"abc"))
attempt("contains-float", lambda: 2.0 in bytearray(b"abc"))
print("iteration", list(bytearray(b"ab")))
print("iterator", type(iter(bytearray(b"ab"))).__name__)
attempt("hash", lambda: hash(bytearray(b"a")))
print("hash-slot", bytearray.__dict__["__hash__"])

run = bytearray(b"abcd")
run[0] = 65
print("set-index", run)
run[-1] = 68
print("set-index-negative", run)
attempt("set-index-too-large", lambda: run.__setitem__(0, 300))
attempt("set-index-not-int", lambda: run.__setitem__(0, "x"))
attempt("set-index-out-of-range", lambda: run.__setitem__(9, 0))
run[1:3] = b"XY"
print("set-slice", run)
run[1:2] = b"long"
print("set-slice-grow", run)
run[0:4] = b""
print("set-slice-shrink", run)
run[0:0] = b"pq"
print("set-slice-insert", run)
run[::2] = b"ZZZ"
print("set-slice-step", run)
attempt("set-slice-step-size", lambda: run.__setitem__(slice(None, None, 2), b"Z"))
attempt("set-slice-number", lambda: run.__setitem__(slice(0, 1), 1.5))
attempt("set-slice-text", lambda: run.__setitem__(slice(0, 1), "x"))
attempt("set-slice-object", lambda: run.__setitem__(slice(0, 1), Refuses()))
run[0:2] = run
print("set-slice-self", run)
run[0:2] = (value for value in b"ok")
print("set-slice-generator", run)
del run[0]
print("del-index", run)
del run[-1]
print("del-index-negative", run)
attempt("del-index-out-of-range", lambda: run.__delitem__(9))
del run[::2]
print("del-slice-step", run)
del run[0:0]
print("del-slice-empty", run)

print("add-bytearray", bytearray(b"ab") + bytearray(b"cd"))
print("add-bytes", bytearray(b"ab") + b"cd")
print("radd-bytes", b"ab" + bytearray(b"cd"))
attempt("add-int", lambda: bytearray(b"a") + 1)
attempt("add-text", lambda: bytearray(b"a") + "x")
print("mul", bytearray(b"ab") * 2)
print("mul-zero", bytearray(b"ab") * 0)
print("mul-negative", bytearray(b"ab") * -1)
print("mul-bool", bytearray(b"ab") * True)
print("rmul", 2 * bytearray(b"ab"))

inplace = bytearray(b"ab")
inplace += b"cd"
print("iadd", inplace)
inplace += bytearray(b"ef")
print("iadd-bytearray", inplace)
attempt("iadd-int", lambda: inplace.__iadd__(1))
attempt("iadd-text", lambda: inplace.__iadd__("x"))
inplace *= 2
print("imul", inplace)
inplace *= 0
print("imul-zero", inplace)
inplace *= 3
print("imul-empty", inplace)

print("eq-bytes", bytearray(b"ab") == b"ab")
print("eq-bytearray", bytearray(b"ab") == bytearray(b"ab"))
print("eq-list", bytearray(b"ab") == [97, 98])
print("lt", bytearray(b"ab") < b"ac", bytearray(b"ab") > b"aa")
print("cmp-slot-bytes", bytearray(b"ab").__eq__(b"ab"))
print("cmp-slot-int", bytearray(b"ab").__eq__(3))
print("type-name", type(bytearray()).__name__)
print("bool", bool(bytearray()), bool(bytearray(b"a")))

print("new-empty", bytearray.__new__(bytearray, b"ab"))
attempt("new-wrong-class", lambda: bytearray.__new__(bytes))
attempt("new-too-many", lambda: bytearray.__new__(bytearray, 1, 2))

started = bytearray(b"ab")
print("init-returns", started.__init__(b"xy"))
print("init-content", started)
broken = bytearray(b"abcd")
attempt("init-bad-list", lambda: broken.__init__([65, 300]))
print("init-bad-list-content", broken)
partial = bytearray(b"abcd")
attempt("init-bad-generator", lambda: partial.__init__(Push()))
print("init-bad-generator-content", partial)
self_init = bytearray(b"ab")
print("init-self", self_init.__init__(self_init), self_init)
reset = bytearray(b"abcdef")
print("init-text", reset.__init__("pq", "ascii"), reset)
attempt("init-text-without-encoding", lambda: reset.__init__("a"))
print("init-text-without-encoding-content", reset)
attempt("init-too-many", lambda: reset.__init__(b"a", "ascii", "strict", "x"))
print("init-too-many-content", reset)

for name in (
    "__new__",
    "__repr__",
    "__str__",
    "__lt__",
    "__le__",
    "__eq__",
    "__ne__",
    "__gt__",
    "__ge__",
    "__iter__",
    "__init__",
    "__len__",
    "__getitem__",
    "__setitem__",
    "__delitem__",
    "__add__",
    "__mul__",
    "__rmul__",
    "__contains__",
    "__iadd__",
    "__imul__",
):
    member = getattr(bytearray, name)
    print(
        "member",
        name,
        member.__text_signature__,
        member.__doc__,
    )
print("doc", bytearray.__doc__)
