DESCRIPTOR = type(bytes.hex)

# The thirteen sentences `float_methods` files beside the float type (Objects/floatobject.c:1780), and the
# one row `bytes_methods` files that this runtime had still left with only its sentence. Float is the first
# family swept here whose every row is clinic's work: `Objects/floatobject.c` writes no `ml_doc` by hand, so
# all thirteen texts open with the table `float.hex($self, /)`-shaped line and `find_signature` starts on
# each one — thirteen rows therefore close thirteen tables, where int's fourteen closed only ten. Three of
# the thirteen are `METH_CLASS` (`from_number`, `fromhex`, `__getformat__`), which is why `float.__dict__`
# holds a `classmethod_descriptor` under those names and the table reads its first argument as `$type`.
# `__getnewargs__` is the other kind of silence, a table with nothing written under it. Three of the
# sentences quote, and the C literals write those apostrophes escaped as `\'`; the transcribed ones carry a
# plain apostrophe, so a backslash surviving anywhere below is a transcription slip.
FLOAT = ["from_number", "conjugate", "__trunc__", "__floor__", "__ceil__", "__round__",
         "as_integer_ratio", "fromhex", "hex", "is_integer", "__getnewargs__",
         "__getformat__", "__format__"]
CLASS_ROWS = ["from_number", "fromhex", "__getformat__"]
QUOTING = ["hex", "fromhex", "__getformat__"]


def table(book, names):
    for name in names:
        method = getattr(book, name)
        print(
            book.__name__, name, "|", repr(method.__text_signature__), "|",
            type(method.__doc__).__name__, "|", repr(method.__doc__),
        )


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
print("float rows", len(FLOAT), "kind", DESCRIPTOR.__name__)
print("rows in the dictionary", sum(1 for n in vars(float) if isinstance(vars(float)[n], DESCRIPTOR)))
print("rows with a table", sum(1 for n in FLOAT if getattr(float, n).__text_signature__ is not None))
table(float, FLOAT)

print("== whose table names what ==")
tables = [getattr(float, n).__text_signature__ for n in FLOAT]
print("float", "with $self", sum(1 for t in tables if t.startswith("($self")))
print("float", "with $type", sum(1 for t in tables if t.startswith("($type")))
print("float", "ending with /)", sum(1 for t in tables if t.endswith("/)")))
print("float", "with a default", sum(1 for t in tables if "=" in t))
print("float", "with a keyword-only star", sum(1 for t in tables if "*, " in t))
print("float", "naming a str", sum(1 for t in tables if "str" in t))
print("float", "shorter than forty", sum(1 for t in tables if len(t) < 40))
print("float", "widest table", max(len(t) for t in tables))
sentences = [getattr(float, n).__doc__ for n in FLOAT]
print("float", "rows whose sentence is None", sum(1 for d in sentences if d is None))
print("float", "sentences of one line", sum(1 for d in sentences if d is not None and "\n" not in d))

print("== the three class rows ==")
for name in CLASS_ROWS:
    row = float.__dict__[name]
    print(name, "|", type(row).__name__, "|", row.__qualname__, "|", repr(row.__text_signature__))
print("read from the type", [type(getattr(float, n)).__name__ for n in CLASS_ROWS])
print("read from a value", [type(getattr(2.5, n)).__name__ for n in CLASS_ROWS])

print("== the row with a table and nothing after it ==")
row = float.__dict__["__getnewargs__"]
print("float", "|", type(row).__name__, "|", repr(row.__doc__), "|", repr(row.__text_signature__), "|",
      repr((2.5).__getnewargs__()))
print("int's row is the same shape", "|", repr(int.__getnewargs__.__doc__), "|",
      repr(int.__getnewargs__.__text_signature__))
# `str.__getnewargs__` and `bytes.__getnewargs__` are filed without an `ml_doc` at all, so the reference
# reads its `($self, /)` out of `signature_from_flags` rather than out of any text; only the sentence half
# is a fact about the table here, and this runtime leaves that one table uninvented.
print("str's row has no sentence either", "|", repr(str.__getnewargs__.__doc__))

print("== the three sentences that quote ==")
print("apostrophes", {n: getattr(float, n).__doc__.count("'") for n in QUOTING})
print("a backslash survives", any("\\" in getattr(float, n).__doc__ for n in QUOTING))
print("the table of each", {n: getattr(float, n).__text_signature__ for n in QUOTING})

print("== one text, two readers ==")
for name in FLOAT:
    bound = getattr(2.5, name)
    print(name, "|", bound.__doc__ == getattr(float, name).__doc__, "|", bound.__name__, "|",
          bound.__qualname__, "|", type(bound.__self__).__name__)

print("== the doc half is a read, not a field ==")
refuses("write float.hex.__doc__", lambda: setattr(float.hex, "__doc__", "x"))
refuses("delete float.hex.__doc__", lambda: delattr(float.hex, "__doc__"))
refuses("write the dictionary row's doc", lambda: setattr(float.__dict__["fromhex"], "__doc__", "x"))
refuses("write (2.5).is_integer.__doc__", lambda: setattr((2.5).is_integer, "__doc__", "x"))
refuses("write float.__round__.__text_signature__",
        lambda: setattr(float.__round__, "__text_signature__", "()"))
refuses("delete float.__format__.__text_signature__",
        lambda: delattr(float.__format__, "__text_signature__"))
show("the row and the read are one text",
     lambda: float.__dict__["__round__"].__doc__ == float.__round__.__doc__)
show("a second read of the doc", lambda: getattr(float.fromhex, "__doc__") == float.fromhex.__doc__)
show("a second read of the table", lambda: getattr(float.__getformat__, "__text_signature__")
     == float.__getformat__.__text_signature__)

print("== the row bytes files beside its __getnewargs__ ==")
table(bytes, ["__bytes__"])
octets = b"ab"
print("bytes", "|", type(bytes.__dict__["__bytes__"]).__name__, "|",
      repr(bytes.__dict__["__bytes__"].__text_signature__))
show("call it on a value", lambda: octets.__bytes__())
show("it answers the value asked on", lambda: octets.__bytes__() is octets)
show("call it with an argument", lambda: octets.__bytes__(1))
print("the neighbour has no sentence to read", "|", repr(bytes.__getnewargs__.__doc__))

print("== what the text does not change ==")
show("call the dictionary row", lambda: float.__dict__["is_integer"](2.5))
show("call from_number on an int", lambda: float.from_number(3))
show("call from_number on text", lambda: float.from_number("1.5"))
show("call from_number by place", lambda: float.from_number(1.5))
show("call conjugate", lambda: (2.5).conjugate())
show("call trunc on a negative", lambda: (-2.5).__trunc__())
show("call floor on a negative", lambda: (-2.5).__floor__())
show("call ceil on a negative", lambda: (-2.5).__ceil__())
show("call round with no place", lambda: (2.5).__round__())
show("call round half toward even", lambda: (3.5).__round__())
show("call round at a place", lambda: (2.675).__round__(2))
show("call round by name", lambda: (2.5).__round__(ndigits=1))
show("call as_integer_ratio", lambda: (0.5).as_integer_ratio())
show("call as_integer_ratio on an infinity", lambda: float("inf").as_integer_ratio())
show("call as_integer_ratio on a nan", lambda: float("nan").as_integer_ratio())
show("call fromhex", lambda: float.fromhex("0x1.ffffp10"))
show("call fromhex by name", lambda: float.fromhex(string="0x1p0"))
show("call hex", lambda: (0.1).hex())
show("call hex on a negative", lambda: (-0.1).hex())
show("call hex with an argument", lambda: (0.1).hex(1))
show("call is_integer", lambda: (3.0).is_integer())
show("call is_integer on a fraction", lambda: (2.5).is_integer())
show("call is_integer with an argument", lambda: (2.5).is_integer(1))
show("call getnewargs", lambda: (2.5).__getnewargs__())
show("call getformat from the type", lambda: float.__getformat__("double"))
show("call getformat from a value", lambda: (2.5).__getformat__("float"))
show("call getformat on an unknown name", lambda: float.__getformat__("bogus"))
show("call getformat by name", lambda: float.__getformat__(typestr="double"))
show("call format with a spec", lambda: (2.5).__format__("+.2e"))
show("call format with no spec", lambda: (2.5).__format__(""))
show("a keyword finds no place", lambda: (2.5).is_integer(x=1))
show("one argument too many", lambda: (2.5).conjugate(1))
# The type's own `__doc__` is the sentence half of clinic's `float_new__doc__`, a text no row of
# `float_methods` files; its table half belongs to the class rows this family has not reached.
show("the class's sentence is the new row's sentence",
     lambda: float.__doc__ == "Convert a string or number to a floating-point number, if possible.")
