DESCRIPTOR = type(bytes.hex)

# The fourteen sentences `long_methods` files beside the integer type (Objects/longobject.c:6450), and the two
# halves CPython cuts from each. Ten of the fourteen read the table clinic wrote into
# `Objects/clinic/longobject.c.h`; four are written by hand inside the table row itself — `conjugate`,
# `__trunc__`, `__floor__`, `__ceil__`, the four ways of asking a number that is already whole for its whole —
# and those four carry a sentence and no table. `find_signature` therefore never starts on them, and the table
# the reference reads beside them is built from the METH_NOARGS flags rather than transcribed: this runtime
# leaves that one table uninvented, so those four rows are swept by sentence only and their tables are not
# pinned here. `__getnewargs__` is the other kind of silence: a table with nothing written under it, which is
# why its `__doc__` reads `None` in both. `__sizeof__` is the one row here whose answer is pinned as well as
# its table: an integer's bytes are the digits it holds, which this runtime counts the same way CPython does,
# so it is not one of the collection types' allocator answers named back in slice 31.
WITH_TABLE = ["bit_length", "bit_count", "to_bytes", "from_bytes", "as_integer_ratio", "__round__",
              "__getnewargs__", "__format__", "__sizeof__", "is_integer"]
SENTENCE_ONLY = ["conjugate", "__trunc__", "__floor__", "__ceil__"]
INT = WITH_TABLE + SENTENCE_ONLY


def table(book, names):
    for name in names:
        method = getattr(book, name)
        print(
            book.__name__, name, "|", repr(method.__text_signature__), "|",
            type(method.__doc__).__name__, "|", repr(method.__doc__),
        )


def sentence(names):
    for name in names:
        print(name, "|", repr(getattr(int, name).__doc__))


def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "->", type(e).__name__)


def refuses(label, fn):
    try:
        fn()
        print(label, "-> accepted")
    except Exception as e:
        print(label, "->", type(e).__name__)


print("== the two halves of one text ==")
print("int rows", len(INT), "kind", DESCRIPTOR.__name__)
print("rows in the dictionary", sum(1 for n in vars(int) if isinstance(vars(int)[n], DESCRIPTOR)))
table(int, WITH_TABLE)
sentence(SENTENCE_ONLY)

print("== the four sentences written without a table ==")
five, six = 5, 6
print("one C function, four names", "|", (five).__ceil__() is five, "|", (five).__floor__() is five, "|",
      (five).__trunc__() is five, "|", (five).conjugate() is five)
print("a bool answers as an int", "|", type((True).__floor__()).__name__, "|", (six).__floor__() is six)
print("the four sentences", [getattr(int, name).__doc__ for name in SENTENCE_ONLY])

print("== whose table names what ==")
tables = [getattr(int, name).__text_signature__ for name in WITH_TABLE]
print("int", "with $self", sum(1 for t in tables if t.startswith("($self")))
print("int", "with $type", sum(1 for t in tables if t.startswith("($type")))
print("int", "ending with /)", sum(1 for t in tables if t.endswith("/)")))
print("int", "with a default", sum(1 for t in tables if "=" in t))
print("int", "with a keyword-only star", sum(1 for t in tables if "*, " in t))
print("int", "naming bytes", sum(1 for t in tables if "bytes" in t))
print("int", "shorter than forty", sum(1 for t in tables if len(t) < 40))
print("int", "widest table", max(len(t) for t in tables))
print("int", "sentences with no table", len(SENTENCE_ONLY))
sentences = [getattr(int, name).__doc__ for name in INT]
print("int", "rows whose sentence is None", sum(1 for d in sentences if d is None))
print("int", "sentences of one line", sum(1 for d in sentences if d is not None and "\n" not in d))

print("== the row with a table and nothing after it ==")
row = int.__dict__["__getnewargs__"]
print("int", "|", type(row).__name__, "|", repr(row.__doc__), "|", repr(row.__text_signature__), "|",
      repr((255).__getnewargs__()))
big = 2 ** 70
print("it copies rather than hands back", big.__getnewargs__()[0] is big)
print("tuple's row is the same shape", "|", repr(tuple.__getnewargs__.__doc__), "|",
      repr(tuple.__getnewargs__.__text_signature__))

print("== the class row and the value rows ==")
for name in INT:
    row = int.__dict__[name]
    print(name, "|", type(row).__name__, "|", row.__qualname__)
print("read from the type", type(int.from_bytes).__name__)
print("read from a value", type((258).from_bytes).__name__)

print("== one text, two readers ==")
for name in ["to_bytes", "bit_length", "bit_count", "as_integer_ratio", "__round__", "__format__",
             "__sizeof__", "is_integer", "conjugate", "__ceil__"]:
    bound = getattr(258, name)
    print(name, "|", bound.__doc__ == getattr(int, name).__doc__, "|", bound.__name__, "|",
          bound.__qualname__, "|", type(bound.__self__).__name__)

print("== the doc half is a read, not a field ==")
refuses("write int.to_bytes.__doc__", lambda: setattr(int.to_bytes, "__doc__", "x"))
refuses("delete int.to_bytes.__doc__", lambda: delattr(int.to_bytes, "__doc__"))
refuses("write the dictionary row's doc", lambda: setattr(int.__dict__["bit_length"], "__doc__", "x"))
refuses("write (5).bit_count.__doc__", lambda: setattr((5).bit_count, "__doc__", "x"))
refuses("write int.is_integer.__text_signature__",
        lambda: setattr(int.is_integer, "__text_signature__", "()"))
refuses("delete int.is_integer.__text_signature__",
        lambda: delattr(int.is_integer, "__text_signature__"))
show("the row and the read are one text",
     lambda: int.__dict__["__round__"].__doc__ == int.__round__.__doc__)
show("a second read of the doc", lambda: getattr(int.to_bytes, "__doc__") == int.to_bytes.__doc__)
show("a second read of the table", lambda: getattr(int.__format__, "__text_signature__")
     == int.__format__.__text_signature__)

print("== what the text does not change ==")
show("call the dictionary row", lambda: int.__dict__["bit_count"](13))
show("call bit_length on a negative", lambda: (-37).bit_length())
show("call bit_count", lambda: (13).bit_count())
show("call to_bytes with nothing asked", lambda: (258).to_bytes())
show("call to_bytes with a length", lambda: (258).to_bytes(2))
show("call to_bytes by name", lambda: (258).to_bytes(length=2, byteorder="little"))
show("call to_bytes signed by keyword", lambda: (-1).to_bytes(1, "big", signed=True))
show("call to_bytes signed by place", lambda: (-1).to_bytes(1, "big", True))
show("call from_bytes on bytes", lambda: int.from_bytes(b"\x01\x02"))
show("call from_bytes by name", lambda: int.from_bytes(bytes=b"\x01\x02", byteorder="little"))
show("call from_bytes from a value", lambda: (258).from_bytes(b"\x02"))
show("call as_integer_ratio on a negative", lambda: (-10).as_integer_ratio())
show("call as_integer_ratio on zero", lambda: (0).as_integer_ratio())
show("call round with no place", lambda: (5).__round__())
show("call round before the point", lambda: (1234).__round__(-2))
show("call round by name", lambda: (5).__round__(ndigits=2))
show("call getnewargs", lambda: (255).__getnewargs__())
show("call format with a spec", lambda: (255).__format__("08x"))
show("call format with no spec", lambda: (255).__format__(""))
show("call is_integer", lambda: (5).is_integer())
show("call is_integer with an argument", lambda: (5).is_integer(1))
show("call __sizeof__ with an argument", lambda: (5).__sizeof__(1))
show("call __sizeof__", lambda: (5).__sizeof__())
show("call __sizeof__ of a number past the cache", lambda: (2 ** 70).__sizeof__())
show("a keyword finds no place", lambda: (5).bit_count(count=1))
show("one argument too many", lambda: (5).is_integer(1, 2))
show("the type's own sentence", lambda: int.__doc__.startswith("int([x]) -> integer"))
