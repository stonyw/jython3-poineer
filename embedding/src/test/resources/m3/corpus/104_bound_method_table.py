import builtins


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


bound = "text".join
kind = type(bound)

# What a value read out of a built-in type's method table is made of, measured against CPython 3.14.8.
#
# `method_get` (Objects/descrobject.c:137) answers a read of a `method_descriptor` through a value with
# `PyCFunction_NewEx(descr->d_method, obj, NULL)` — the very record a builtin function is, with `m_self`
# filled and `m_module` left empty. So `"text".join` is a `builtin_function_or_method` and not a `method`,
# which is what `PyMethod_Type` reserves for the `__get__` of a function Python wrote, and not a
# `method-wrapper`, which is what the slot rows of the same family hand out. The sixteen names it reads are
# the ones of slice 39, `dir()` the thirty of that type, and the row it was read out of answers five of
# them: the name, that name qualified with the class of the receiver rather than with the one that declares
# it — `True.bit_length` says `bool.bit_length` — and the sentence and the table cut out of the same
# `ml_doc` by the same two knives.
#
# The emptiness is the other half of that `NULL`. `__module__` reads `None` on a method and `'builtins'` on
# `len`, and it is the one name here a program can write, because `meth_members` files it over the field
# while `meth_getsets` files five reads with no setter — so a write of `__name__` or `__doc__` is refused by
# the getset, and `del` of it by the same setter handed nothing.
print("== the kind a value read hands out")
print("   kind", kind.__name__, "| rows", len(vars(kind)), "| dir", len(dir(bound)))
print("   rows", list(vars(kind)))
print("   dir", sorted(dir(bound)))

print("== the six names a read answers")
for name in ("__name__", "__qualname__", "__self__", "__module__", "__doc__", "__text_signature__"):
    print("   ", name, "|", said_masked(lambda n=name: getattr(bound, n)))
print("   qualified by the receiver", True.bit_length.__qualname__, "|", (1.5).is_integer.__qualname__)
print("   the row keeps its doc", said(lambda: bound.__doc__ == str.join.__doc__))
print("   and its table", said(lambda: bound.__text_signature__ == str.join.__text_signature__))

print("== the three names a read has no record to answer")
for name in ("__objclass__", "__func__", "__get__", "__dict__"):
    print("   ", name, said(lambda n=name: getattr(bound, n)))
print("   vars", said(lambda: vars(bound)))

print("== the one name written, the five refused")
print("   module", said(lambda: setattr(bound, "__module__", "written")))
print("   read", said(lambda: bound.__module__), "|", said_masked(lambda: bound.__module__))
print("   del", said(lambda: delattr(bound, "__module__")))
print("   read again", said(lambda: bound.__module__))
print("   del twice", said(lambda: delattr(bound, "__module__")))
for name in ("__name__", "__qualname__", "__self__", "__doc__", "__text_signature__"):
    print("   ", name, [said(lambda n=name: setattr(bound, n, 1)), said(lambda n=name: delattr(bound, n))])
print("   a name out of the sixteen", said(lambda: bound.junk), "|", said(lambda: setattr(bound, "junk", 1)))

print("== the receiver and the row decide equality")
text = "text"
again = text.join
print("   two reads", said(lambda: bound is again), "|", said(lambda: bound == again))
print("   hash alike", said(lambda: hash(bound) == hash(again)), "| of another",
      said(lambda: bound == "abc".join))
print("   against a value", said(lambda: bound == 3), "|", said(lambda: bound != 3))
for op in ("__lt__", "__le__", "__gt__", "__ge__"):
    print("   ranks", op, said(lambda n=op: getattr(kind, n)(bound, again)))
print("   the operator", said(lambda: bound < again))
print("   equality falls back", said(lambda: kind.__dict__["__eq__"](bound, again)),
      "|", kind.__dict__["__ne__"](bound, again))

print("== the reduce row names the receiver, the unbound one names nothing")
print("   bound", said(lambda: (bound.__reduce__()[0] is builtins.getattr, bound.__reduce__()[1])))
print("   found again", said(lambda: bound.__reduce__()[0](*bound.__reduce__()[1]) == bound))
print("   plain", said(lambda: len.__reduce__()))
print("   class", said(lambda: int.mro.__reduce__()))
print("   tuple", said(lambda: (1,).count.__reduce__()))

print("== who binds what")


class Plain:
    def shout(self):
        return "hi"


def make():
    yield 1


print("   a table row", said_kind(lambda: bound), "| a slot", said_kind(lambda: (3).__add__))
print("   a written function", said_kind(lambda: Plain().shout), "| its class",
      said_kind(lambda: Plain.shout))
print("   through the descriptor", said_kind(lambda: str.__dict__["join"].__get__(text)))
print("   self is the value", said(lambda: str.__dict__["join"].__get__(text).__self__ is text))
print("   a classmethod over a value", said_kind(lambda: {}.fromkeys), "|",
      said(lambda: {}.fromkeys(1, 2)))
print("   a staticmethod", said_kind(lambda: bytes.maketrans), "|",
      said(lambda: bytes.maketrans is b"".maketrans))
print("   a generator's row", said_kind(lambda: make().send))
print("   the builtin itself", said_kind(lambda: len), "|", said_kind(lambda: int.mro))
print("   the kind cannot be made", said(lambda: kind()))

print("== the call and the sentences of its refusals")
print("   call", said(lambda: bound(["a", "b"])), "|", said(lambda: text.join(["a", "b"])))
print("   none", said(lambda: bound()))
print("   two", said(lambda: bound(["a"], ["b"])))
print("   keyword", said(lambda: bound(x=["a"])))
print("   empty keywords", said(lambda: bound(["a"], **{})))
print("   the row itself", said(lambda: str.join()))
print("   the row over another", said(lambda: str.join(3)))
print("   the call row", said(lambda: kind.__dict__["__call__"](bound)))
print("   a read row", said_masked(lambda: kind.__dict__["__repr__"](bound)), "|",
      said(lambda: kind.__dict__["__repr__"](3)))
print("   a getset row", said(lambda: kind.__dict__["__name__"](3)))

print("== the table a flag answers, read through the value")
row = b"ab".__getnewargs__
print("   signature", said(lambda: row.__text_signature__), "| doc", said(lambda: row.__doc__))
print("   as the row files it", said(lambda: bytes.__dict__["__getnewargs__"].__text_signature__))
print("   and it still runs", said(lambda: row()))
print("   conjugate", said(lambda: (5).conjugate.__text_signature__), "|",
      said(lambda: (5).conjugate.__qualname__))
