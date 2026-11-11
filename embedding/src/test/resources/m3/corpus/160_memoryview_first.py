"""memoryview's first segment: the type, the constructor, and the facts of the window.

The reference files forty-one names in memoryview.__dict__. This slice files sixteen of them — the
three slots that make a view printable and sized (__new__, __repr__, __len__) and the twelve
read-only getsets over the buffer header — so the dictionary here is a prefix of the reference's
rather than a reordering of it. The comparisons, __iter__, the buffer protocol, the subscript and
delete halves, the context manager and the named methods are the later segments.

A view is a window: it holds the exporting value rather than a copy of its octets, so a view of a
bytearray writes into the run the program still holds and a view of a view shares the value
underneath instead of nesting. A repr carries an address, which the two interpreters can never agree
on, so every repr check reads the prefix and the closing bracket rather than the whole line.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


# --- the type itself ------------------------------------------------------------------------

print("name", memoryview.__name__, memoryview.__qualname__, memoryview.__module__)
print("signature", repr(memoryview.__text_signature__))
print("doc", repr(memoryview.__doc__))
print("mro", memoryview.__mro__)
print("type-of-view", type(memoryview(b"a")).__name__)
print("isinstance", isinstance(memoryview(b"a"), memoryview))


# --- the constructor takes one argument, called object, either way --------------------------

print("positional", len(memoryview(b"ab")), memoryview(b"ab").nbytes)
print("keyword", len(memoryview(object=b"abc")))
attempt("none", lambda: memoryview())
attempt("unknown-keyword", lambda: memoryview(foo=1))
attempt("wrong-keyword-name", lambda: memoryview(obj=b"ab"))
attempt("two-positions", lambda: memoryview(b"ab", b"cd"))
attempt("position-and-keyword", lambda: memoryview(b"ab", object=b"cd"))
attempt("two-keywords", lambda: memoryview(object=b"ab", foo=1))
attempt("three", lambda: memoryview(b"ab", object=b"cd", foo=1))


# --- what a view can be made of -------------------------------------------------------------

print("from-memoryview", len(memoryview(memoryview(b"abcd"))))
for label, value in (
        ("int", 3),
        ("none", None),
        ("str", "abc"),
        ("list", [1]),
        ("tuple", (1,)),
        ("float", 2.5),
        ("dict", {})):
    attempt("bad-" + label, lambda value=value: memoryview(value))


# --- __new__ is the constructor's first half ------------------------------------------------

print("new-length", len(memoryview.__new__(memoryview, b"ab")))
attempt("new-foreign-type", lambda: memoryview.__new__(bytes, b"ab"))
attempt("new-missing", lambda: memoryview.__new__(memoryview))


# --- a view of bytes is read-only, a view of bytearray is writable --------------------------

run = bytearray(b"abcd")
for label, view in (
        ("bytes", memoryview(b"abcd")),
        ("bytearray", memoryview(run)),
        ("empty", memoryview(b""))):
    print(label, "len", len(view), view.nbytes)
    print(label, "header", view.readonly, view.itemsize, view.format, view.ndim)
    print(label, "geometry", view.shape, view.strides, view.suboffsets)
    print(label, "contiguous", view.c_contiguous, view.f_contiguous, view.contiguous)
print("exporter", memoryview(b"abcd").obj == b"abcd", memoryview(run).obj is run)

source = b"abc"
print("unwrap", memoryview(memoryview(source)).obj is source)


# --- the window is one object, and its repr says so without an address ----------------------

text = repr(memoryview(b"abc"))
print("repr", text.startswith("<memory at 0x"), text.endswith(">"))
print("bool", bool(memoryview(b"a")), bool(memoryview(b"")))


# --- the twelve fields are read-only, and the descriptor refuses both ways ------------------

view = memoryview(b"ab")
for name in (
        "obj", "nbytes", "readonly", "itemsize", "format", "ndim",
        "shape", "strides", "suboffsets", "c_contiguous", "f_contiguous", "contiguous"):
    attempt("set-" + name, lambda name=name: setattr(view, name, 1))
    attempt("del-" + name, lambda name=name: delattr(view, name))


# --- the book, as far as this slice files it ------------------------------------------------

filled = [
    "__new__", "__repr__", "__len__",
    "obj", "nbytes", "readonly", "itemsize", "format", "ndim",
    "shape", "strides", "suboffsets", "c_contiguous", "f_contiguous", "contiguous",
    "__doc__",
]
present = [name for name in memoryview.__dict__ if name in filled]
print("book-prefix", present == filled)
print("book-last", list(memoryview.__dict__)[-1])
for name in ("__new__", "__repr__", "__len__"):
    member = memoryview.__dict__[name]
    print("slot", name, repr(member.__text_signature__), repr(member.__doc__))
for name in filled[3:-1]:
    print("field", name, repr(memoryview.__dict__[name].__doc__))
