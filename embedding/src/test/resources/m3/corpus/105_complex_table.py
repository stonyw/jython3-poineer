def said(action):
    try:
        return "ok " + repr(action())[:78]
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:78]


def said_kind(action):
    try:
        return "ok " + type(action()).__name__
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:78]


def said_masked(action):
    """The repr a value answers, with the address the reference writes beside it taken back out."""
    try:
        made = action()
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)[:78]
    text = made if isinstance(made, str) else repr(made)
    before, at, after = text.rpartition(" at ")
    return repr(before + ">") if at else repr(text)


Z = 1 + 2j
ROWS = vars(complex)

# The thirty-one rows `complex` files for itself, measured against CPython 3.14.8.
#
# Two C lists and one dictionary entry, in `__dict__`'s order: the twenty-two slot wrappers behind
# `complex_as_number`, then the five of `complex_methods` (Objects/complexobject.c:1331) and the two of
# `complex_members`, and above all of them the `__new__` clinic writes. The number struct leaves four slots
# empty, so this type files no `__mod__`, no `__floordiv__` and no `__divmod__` where `float` and `int` both
# file nine arithmetic rows — and the four orderings are filed because `tp_richcompare` is, answering the
# decline it answers rather than the error the operator writes. `real` and `imag` are a `Py_T_DOUBLE` field
# marked `Py_READONLY`, which makes them `member_descriptor`s and not the `getset_descriptor`s the same two
# names are on `float` and `int`: the same two reads, the same refusal of a write, a different kind of row.
#
# The five sentences come out of `Objects/clinic/complexobject.c.h`, so all five carry their own argument
# table, and `__getnewargs__` is the silence of a table with nothing written under it. `from_number` is the
# one `$type` row, so the dictionary holds a `classmethod_descriptor` where the other four are plain
# `method_descriptor`s — and `complex.from_number` reads as a `builtin_function_or_method` whose `__self__`
# is the type, which is slice 54's rule reached from a new table.
print("== the thirty-one rows and the two counts beside them")
print("   vars", len(ROWS), "| dir kind", len(dir(complex)), "| dir value", len(dir(Z)))
print("   rows", list(ROWS))
print("   kinds", [type(ROWS[n]).__name__ for n in ROWS])
print("   new names over object", sorted(set(dir(complex)) - set(dir(object))))
print("   a value's own", said(lambda: vars(Z)))

print("== what each row says about itself")
for name in ROWS:
    row = ROWS[name]
    print("  %-16s %-24s %-24s %s" % (
        name, type(row).__name__,
        said(lambda n=name: getattr(ROWS[n], "__text_signature__")),
        said(lambda n=name: getattr(ROWS[n], "__qualname__"))))

print("== the five sentences of complex_methods and the two of complex_members")
for name in ("__new__", "from_number", "conjugate", "__complex__", "__getnewargs__", "__format__",
             "real", "imag", "__doc__", "__repr__", "__pow__"):
    print("   ", name, said(lambda n=name: ROWS[n].__doc__))
print("   the row of a table with nothing under it", said(lambda: ROWS["__getnewargs__"].__doc__ is None))
print("   the members objclass", said(lambda: ROWS["real"].__objclass__ is complex), "|",
      said_masked(lambda: ROWS["real"]))
print("   the members of a descriptor", said(lambda: sorted(dir(ROWS["imag"]))[:9]))

print("== the type sentence, read from the dictionary and from the class")
print("   equal", said(lambda: complex.__doc__ == complex.__dict__["__doc__"]), "| one object",
      said(lambda: complex.__doc__ is complex.__dict__["__doc__"]))
print("   head", said(lambda: complex.__doc__.splitlines()[0]))
print("   lines", said(lambda: len(complex.__doc__.splitlines())))
print("   no table cut out", said(lambda: "--" in complex.__doc__))
print("   over a value", said(lambda: Z.__doc__ == complex.__doc__))
print("   str row", said(lambda: type(complex.__dict__["__doc__"]).__name__))

print("== the two fields of the value")
print("   read", said(lambda: Z.real), said(lambda: Z.imag), "|",
      said_kind(lambda: Z.real), said_kind(lambda: Z.imag))
print("   through the class", said_masked(lambda: complex.real), "|", type(complex.imag).__name__)
print("   of a fresh zero", said(lambda: complex().real), "|", said(lambda: complex("1j").imag))
print("   get", said(lambda: ROWS["real"].__get__(Z)), "| get none",
      said_masked(lambda: ROWS["real"].__get__(None, complex)))
for what, action in (("write", lambda: setattr(Z, "real", 3.0)), ("delete", lambda: delattr(Z, "imag")),
                     ("set row", lambda: ROWS["real"].__set__(Z, 3.0)),
                     ("delete row", lambda: ROWS["imag"].__delete__(Z)),
                     ("wrong receiver", lambda: ROWS["real"].__get__(3)),
                     ("wrong class", lambda: ROWS["real"].__get__(None, int))):
    print("   ", what, said(action))
print("   a subclass field of the same name", said(lambda: Z.__dict__))
print("   outranks the dictionary", said(lambda: Z.real))

print("== the __new__ row and the two stages of the constructor")
print("   row", said_masked(lambda: ROWS["__new__"]), "| kind",
      said(lambda: type(complex.__new__).__name__))
print("   signature", said(lambda: complex.__new__.__text_signature__), "| doc",
      said(lambda: complex.__new__.__doc__))
print("   alone", said(lambda: complex.__new__(complex)), "| with parts",
      said(lambda: complex.__new__(complex, 1, 2)))
print("   over another", said(lambda: complex.__new__(int)), "|", said(lambda: complex.__new__(str)))
print("   not a type", said(lambda: complex.__new__(3)), "| nothing", said(lambda: complex.__new__()))
print("   a lone complex is the value", said(lambda: complex(Z) is Z), "|",
      said(lambda: complex.__new__(complex, Z) is Z))
print("   two", said(lambda: complex(1, 2)), "| keywords", said(lambda: complex(imag=2)))
print("   string", said(lambda: complex("1+2j")), "|", said(lambda: complex("(1+2j)")))
print("   a number", said(lambda: complex(2)), "|", said(lambda: complex(True, False)))
print("   nothing", said(lambda: complex()), "|", said(lambda: repr(complex())))
print("   refused", said(lambda: complex(None)), "|", said(lambda: complex(b"1j")))
print("   too many", said(lambda: complex(1, 2, 3)), "|", said(lambda: complex("1j", "2j")))
print("   malformed", said(lambda: complex("1 - 2j")), "|", said(lambda: complex("1+2j ")))

print("== the five methods called three ways")
for name, call in (("conjugate", lambda v: v.conjugate()), ("__complex__", lambda v: v.__complex__()),
                   ("__getnewargs__", lambda v: v.__getnewargs__())):
    row = ROWS[name]
    print("   ", name, "| value", said(lambda: call(Z)), "| row",
          said(lambda r=row: r(Z)), "| as new", said(lambda c=call: type(c(Z)).__name__))
print("   from_number over the type", said(lambda: complex.from_number(2)), "|",
      said(lambda: complex.from_number(2.5)), "|", said(lambda: complex.from_number(1 + 2j)))
print("   from_number of a bool", said(lambda: complex.from_number(True)), "| of a big int",
      said(lambda: complex.from_number(2 ** 70)))
for what, action in (("no args", lambda: complex.from_number()), ("two", lambda: complex.from_number(1, 2)),
                     ("keyword", lambda: complex.from_number(number=1)),
                     ("a string", lambda: complex.from_number("1j")),
                     ("none", lambda: complex.from_number(None)),
                     ("bytes", lambda: complex.from_number(b"1j"))):
    print("   ", what, said(action))
print("   read of the row", said_kind(lambda: complex.from_number), "| self",
      said(lambda: complex.from_number.__self__ is complex), "| name",
      said(lambda: complex.from_number.__name__))
print("   a bound row answers", said(lambda: Z.conjugate.__qualname__), "|",
      said(lambda: Z.conjugate.__text_signature__), "|", said(lambda: Z.conjugate.__self__ is Z))
print("   the silent one", said(lambda: Z.__getnewargs__.__doc__), "|",
      said(lambda: Z.__getnewargs__.__text_signature__))

print("== the twenty-two slot rows")
print("   bound kind", said_kind(lambda: Z.__add__), "| doc", said(lambda: Z.__add__.__doc__), "| self",
      said(lambda: Z.__add__.__self__ is Z))
print("   unbound row", said(lambda: ROWS["__add__"](Z, 1)), "| over another",
      said(lambda: ROWS["__add__"](Z, Z)), "| reflected", said(lambda: ROWS["__radd__"](Z, 1)))
print("   sub", said(lambda: Z - 1), "| mul", said(lambda: Z * 2), "| truediv", said(lambda: Z / 2))
print("   rtruediv", said(lambda: 2 / Z), "| row rtruediv", said(lambda: ROWS["__rtruediv__"](Z, 2)))
print("   neg", said(lambda: -Z), "| pos", said(lambda: +Z), "| the same value",
      said(lambda: +Z is Z), "| abs", said(lambda: abs(Z)))
print("   bool", said(lambda: bool(Z)), "| of zero", said(lambda: bool(complex())), "| hash",
      said(lambda: hash(Z)), "| repr", said(lambda: repr(Z)))
print("   eq", said(lambda: Z == 1), "| ne", said(lambda: Z != 1), "| row eq",
      said(lambda: ROWS["__eq__"](Z, 1)), "| row eq of itself",
      said(lambda: ROWS["__eq__"](Z, Z)))
print("   eq a string", said(lambda: ROWS["__eq__"](Z, "x")), "|", said(lambda: Z == "x"))
print("   the four orderings decline",
      [said(lambda n=n: ROWS[n](Z, 1)) for n in ("__lt__", "__le__", "__gt__", "__ge__")])
print("   and the operator names both", said(lambda: Z < 1), "|", said(lambda: Z >= 2))
print("   of a non-number", said(lambda: ROWS["__add__"](Z, "x")), "|",
      said(lambda: Z + "x"))
print("   the receiver is checked", said(lambda: ROWS["__neg__"](3)), "|",
      said(lambda: ROWS["__repr__"](True)), "|", said(lambda: ROWS["__hash__"]("x")))
for what, action in (("one arg", lambda: ROWS["__add__"](Z)), ("three", lambda: ROWS["__add__"](Z, 1, 2)),
                     ("keyword", lambda: ROWS["__add__"](Z, other=1)),
                     ("neg with one", lambda: ROWS["__neg__"](Z, 1)),
                     ("nothing", lambda: ROWS["__neg__"]())):
    print("   ", what, said(action))
print("   pow keeps the modulus for itself", said(lambda: ROWS["__pow__"](Z, 2, 3)), "|",
      said(lambda: ROWS["__pow__"](Z, 2, 1 + 1j)))
print("   rows the type does not file",
      [said(lambda n=n: getattr(complex, n)) for n in ("__mod__", "__floordiv__", "__divmod__")])

# Three measurements are deliberately missing from the end of this file, because this runtime answers them
# differently and PLAN.md records why: `format(z, ".2f")` reaches a spec writer that `complex` has not been
# given, `z ** 2` reaches the integer fast path `_Py_c_powi` and so answers -3+4.000000000000002j, and
# `z.__sizeof__()` answers the size of the Java object rather than the 32 bytes of the struct.
print("== the format row and the pow row over what this runtime does write")
print("   the empty spec is str(self)", said(lambda: format(Z, "")), "| the row over it",
      said(lambda: ROWS["__format__"](Z, "")))
print("   not text", said(lambda: format(Z, 1)), "|", said(lambda: ROWS["__format__"](Z, 1)))
print("   a complex exponent", said(lambda: Z ** (1 + 1j)), "| the row over it",
      said(lambda: ROWS["__pow__"](Z, 1 + 1j)))
print("   the row of the type and of the value", said(lambda: ROWS["__format__"].__qualname__), "|",
      said(lambda: Z.__format__.__name__))
