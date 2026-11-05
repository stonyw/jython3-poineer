"""The write half of a getset row, the gate in front of it and the gate behind that.

A `PyGetSetDef` is the third way a type files an attribute and the only one of the three whose answer is
behaviour rather than storage, so it can be filed with nothing on its writing side — and CPython still calls
it a *data* descriptor, because `PyDescr_IsData` asks the descriptor's own type for a `tp_descr_set` and
`getset_descriptor` always has one. The write then refuses in the field's own words, and the delete is the
same pointer handed nothing, so it says `is not writable` rather than `is not deletable`: sixty-one of those
sentences are printed below. Three of the twelve getset rows in `type`'s book are of that kind — `__mro__`,
`__dict__` and `__text_signature__` — and eleven more across the value types: four that `int` files, two of
them `bool` reads out of `int`'s book, two in `float`, and `__isabstractmethod__` in each of `property`,
`classmethod` and `staticmethod`.

Two gates stand in front of a missing writer. The receiver is checked first, so a row of `type` asked of a
number says `descriptor '__mro__' for 'type' objects doesn't apply to a 'int' object` and never reaches the
writer that is not there — twelve of those. And a name asked *through a class* rather than through the
descriptor takes a different road entirely: `type_setattro` refuses a non-heap type before it looks the name
up anywhere, which is why `int.__mro__ = 1` says `cannot set '__mro__' attribute of immutable type 'int'`
while `type.__dict__['__mro__'].__set__(int, 1)` says the writable sentence. Twenty-one of the immutable
sentences are printed: nine from the compiled-in types, ten from the five rows that *do* have a writer —
`set(int, 1)` for each and the delete of a class Python built for each, since `type_setattro` refuses that
too — and two from `__annotations__`, whose both halves ask the gate.

The row that breaks the pattern is `__abstractmethods__`, the only writer in `type`'s book that asks nothing
of the type it is handed: it files a value in `int`'s book, which grows from sixty-two rows to sixty-three,
and a read then answers that value for every class below, and the delete of a name never filed is the bare
`AttributeError: __abstractmethods__` rather than a sentence about writing. `__annotations__` is the row next
to it that does ask, so both its halves give the immutable type for `int`.

Nothing below prints the window over a book as a `repr()` of its own — `int.__dict__` is named by its kind
and counted, and a heap class's window is cut at fifty-eight characters — because those two books hold
functions and a function's `repr()` carries an address. `__dict__` is filed in a class Python built's own book
and in no compiled-in one, which is why the read of `Heap.__dict__` answers from the book while `Heap.__mro__`
walks to the metatype. The closing section is the contrast between the two kinds of row with no writer, five
lines before its `done`: a getset speaks in the field's words and a member says `readonly attribute`, four
times, whether it is reached as a row, as a name or with `setattr`.

The filing on `type` itself has a consequence no other name's has: the value lands under the very key the
row was filed at, so `type`'s book keeps eleven getsets instead of twelve, `type.__abstractmethods__` answers
the value, and every class below reads it out of the metatype's book while its own keeps none.
"""

import sys


def told(action):
    try:
        return "ok " + repr(action())
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def row_of(kind, name):
    """The row the book files under the name, without walking anywhere for it."""
    return vars(kind)[name]


def rows_named(kind):
    """The names of the getset rows of a book, in the order the book keeps them."""
    return [n for n, r in vars(kind).items() if type(r).__name__ == "getset_descriptor"]


NULL_ROWS = ("__mro__", "__dict__", "__text_signature__")


class Heap:
    pass


class Leaf(Heap):
    pass


def maker(self):
    return 1


print("== the twelve getset rows of type's book, and the three of them with no writer")
print("  " + repr(rows_named(type)))
print("  " + repr(list(NULL_ROWS)))
for name in NULL_ROWS:
    row = row_of(type, name)
    print(
        "  %-20s kind=%-18s name=%r objclass=%r doc=%r sig=%s"
        % (
            name,
            type(row).__name__,
            told(lambda r=row: r.__name__),
            told(lambda r=row: r.__objclass__),
            told(lambda r=row: r.__doc__),
            told(lambda r=row: r.__text_signature__),
        )
    )

print()
print("== none of the three is filed in the book of a type or of a class Python built")
for name in NULL_ROWS:
    print("  %-20s in int  -> %r   in Heap -> %r" % (name, name in vars(int), name in vars(Heap)))
print("  and the read of a row the book does not file walks to the metatype:")
print("    Heap.__mro__   -> " + told(lambda: Heap.__mro__))
print("    Heap.__text_signature__ -> " + told(lambda: Heap.__text_signature__))

print()
print("== asking a row with no writer of a class Python built, in the four ways")
for name in NULL_ROWS:
    row = row_of(type, name)
    print("  %-20s C.%s = 1  -> %s" % (name, name, told(lambda n=name: setattr(Heap, n, 1))))
    print("  %-20s del C.%s    -> %s" % (name, name, told(lambda n=name: delattr(Heap, n))))
    print("  %-20s set(C, 1)   -> %s" % (name, told(lambda r=row: r.__set__(Heap, 1))))
    print("  %-20s delete(C)   -> %s" % (name, told(lambda r=row: r.__delete__(Heap))))

print()
print("== the same four asks of a class below that one")
for name in NULL_ROWS:
    row = row_of(type, name)
    print("  %-20s Leaf = 1    -> %s" % (name, told(lambda n=name: setattr(Leaf, n, 1))))
    print("  %-20s set(Leaf,1) -> %s" % (name, told(lambda r=row: r.__set__(Leaf, 1))))

print()
print("== a type compiled in answers the immutable sentence by name and the writer's own by descriptor")
for name in NULL_ROWS:
    row = row_of(type, name)
    print("  %-20s set(int, 1)  -> %s" % (name, told(lambda r=row: r.__set__(int, 1))))
    print("  %-20s set(type, 1) -> %s" % (name, told(lambda r=row: r.__set__(type, 1))))
    print("  %-20s set(str, 1)  -> %s" % (name, told(lambda r=row: r.__set__(str, 1))))
    print("  %-20s int.x = 1    -> %s" % (name, told(lambda n=name: setattr(int, n, 1))))
    print("  %-20s del int.x    -> %s" % (name, told(lambda n=name: delattr(int, n))))
    print("  %-20s type.x = 1   -> %s" % (name, told(lambda n=name: setattr(type, n, 1))))

print()
print("== the receiver is asked before the writer is")
for name in NULL_ROWS:
    row = row_of(type, name)
    print("  %-20s set(3, 1)     -> %s" % (name, told(lambda r=row: r.__set__(3, 1))))
    print("  %-20s delete(3)     -> %s" % (name, told(lambda r=row: r.__delete__(3))))
    print("  %-20s set(None, 'x')-> %s" % (name, told(lambda r=row: r.__set__(None, "x"))))
    print("  %-20s set('text',1) -> %s" % (name, told(lambda r=row: r.__set__("text", 1))))

print()
print("== the read half of the same three rows, which every write refusal leaves alone")
for name in NULL_ROWS:
    row = row_of(type, name)
    if name == "__dict__":
        window = row.__get__(int)
        print("  %-20s read(int) -> ok %s of %d rows" % (name, type(window).__name__, len(vars(int))))
    else:
        print("  %-20s read(int) -> %s" % (name, told(lambda r=row: r.__get__(int))))
    print("  %-20s read(C)   -> %s" % (name, told(lambda n=name: getattr(Heap, n))[:58]))
print("  Heap.__dict__ is the window over the book, of " + repr(len(vars(Heap))) + " rows")

print()
print("== the other rows with no writer that this runtime files, and the name each sentence speaks")
VALUE_ROWS = [
    ("int", "real", lambda: 3),
    ("int", "imag", lambda: 3),
    ("int", "numerator", lambda: 3),
    ("int", "denominator", lambda: 3),
    ("bool", "real", lambda: True),
    ("bool", "denominator", lambda: True),
    ("float", "real", lambda: 1.5),
    ("float", "imag", lambda: 1.5),
    ("property", "__isabstractmethod__", lambda: property(maker)),
    ("classmethod", "__isabstractmethod__", lambda: classmethod(maker)),
    ("staticmethod", "__isabstractmethod__", lambda: staticmethod(maker)),
]
for label, name, make in VALUE_ROWS:
    value = make()
    holder = None
    row = None
    for base in type(value).__mro__:
        candidate = vars(base).get(name)
        if candidate is not None:
            holder = base.__name__
            row = candidate
            break
    print(
        "  %-11s %-20s kind=%-18s filed in %-11s"
        % (label, name, type(row).__name__, holder)
    )
    print("  %-11s %-20s set(v, 1) -> %s" % ("", name, told(lambda r=row: r.__set__(value, 1))))
    print("  %-11s %-20s delete(v) -> %s" % ("", name, told(lambda r=row: r.__delete__(value))))
    print("  %-11s %-20s v.x = 1   -> %s" % ("", name, told(lambda v=value, n=name: setattr(v, n, 1))))
    print("  %-11s %-20s reads     -> %s" % ("", name, told(lambda v=value, n=name: getattr(v, n))))
    print("  %-11s %-20s objclass  -> %s" % ("", name, told(lambda r=row: r.__objclass__)))

print()
print("== the rows that do have a writer, and the gates that stand behind it")
for name in ("__name__", "__qualname__", "__bases__", "__module__", "__doc__"):
    row = row_of(type, name)
    box = type("Box_" + name.strip("_"), (object,), {})
    print("  %-16s set(int, 1)  -> %s" % (name, told(lambda r=row: r.__set__(int, 1))))
    print("  %-16s set(Box, 1)  -> %s" % (name, told(lambda r=row, b=box: r.__set__(b, 1))))
    print("  %-16s delete(Box)  -> %s" % (name, told(lambda r=row, b=box: r.__delete__(b))))
    print("  %-16s set(Box, str)-> %s" % (name, told(lambda r=row, b=box: r.__set__(b, "text"))))
    print("  %-16s reads back   -> %r" % (name, getattr(box, name)))

print()
print("== the row whose writer asks no gate of the type it was handed, and the one that asks both")
print("  abstractmethods reads before  -> " + told(lambda: int.__abstractmethods__))
row = row_of(type, "__abstractmethods__")
print("  set(int, 1)  -> " + told(lambda r=row: r.__set__(int, 1)))
print("  int reads    -> " + told(lambda: int.__abstractmethods__))
print("  int book has -> " + repr(int.__dict__.get("__abstractmethods__", "MISSING")))
print("  len(int book)-> " + repr(len(rows_named(int))) + " getsets, " + repr(len(vars(int))) + " rows")
print("  delete(int)  -> " + told(lambda r=row: r.__delete__(int)))
print("  int reads    -> " + told(lambda: int.__abstractmethods__))
print("  set(type, 1) -> " + told(lambda r=row: r.__set__(type, 1)))
print("  Heap reads   -> " + told(lambda: Heap.__abstractmethods__))
print("  type book row -> " + repr(type(type.__dict__["__abstractmethods__"]).__name__))
print("  type getsets now -> " + repr(len(rows_named(type))) + " of 12")
print("  int reads now -> " + told(lambda: int.__abstractmethods__))
print("  int book has  -> " + repr(int.__dict__.get("__abstractmethods__", "MISSING")))
print("  annotations set(int, 1) -> " + told(lambda: row_of(type, "__annotations__").__set__(int, 1)))
print("  annotations del(int)    -> " + told(lambda: row_of(type, "__annotations__").__delete__(int)))

print()
print("== the two kinds of row with no writer, side by side")
print("  a getset:  float real  delete -> " + told(lambda: row_of(float, "real").__delete__(1.5)))
proxy = super(int, 3)
self_row = row_of(super, "__self__")
print("  a member:  super __self__ delete -> " + told(lambda: self_row.__delete__(proxy)))
print("  a member:  super __self__ set    -> " + told(lambda: self_row.__set__(proxy, 1)))
major = row_of(type(sys.version_info), "major")
print("  a member:  version_info major set -> " + told(lambda: major.__set__(sys.version_info, 1)))
print("  a member bound by name:  -> " + told(lambda: setattr(sys.version_info, "major", 1)))
print("done")
