"""The table a type's sentence opens with, and the two reads that cut it and keep it.

A built-in type's `tp_doc` is often two texts welded into one: an argument table, then the rule `--` that
clinic prints under it, then the sentence a person reads. `type_get_text_signature`
(`Objects/typeobject.c:1972`) hands that one string to `_PyType_GetTextSignatureFromInternalDoc` and answers
nothing but the table; `type_get_doc` (`:1955`) hands the same string to `_PyType_DocWithoutSignature` (`:863`)
and answers everything under the rule. The table is only a table when `find_signature` finds the type's own
name straight followed by a bracket and `skip_signature` reaches the rule before the first blank line, and
`__text_signature__` is the bracketed half with the name cut off too.

Eleven of the names in `builtins` are written that way, and this runtime now stores the welded text for all
eleven: four clinic constructors — `float`, `complex`, `list` and `tuple`, whose tables come from `float_new`,
`complex_new`, `list.__init__` and `tuple_new`; three hand-written ones — `bool`, `set` and `frozenset`, whose
sentences open with a table someone wrote beside the `PyTypeObject` in exactly the shape clinic would have
used; the descriptor trio — `property` from `property_init`'s clinic text and `classmethod` and `staticmethod`
hand-written in `Objects/funcobject.c`; and `object`, whose table is the empty pair of brackets because it
takes nothing. The five cursors `reversed`, `enumerate`, `zip`, `map` and `filter` carry theirs the same way,
so sixteen names in `builtins` answer a table here; the reference answers seventeen, and the one it has and
this does not is `memoryview`, whose type is not built yet.

Eight more names open with a line that looks exactly like a table and is not one, because a `->` follows the
bracket and no rule follows that: `int`, `dict`, `slice`, `str`, `bytes`, `type`, `range` and `super`. Each
answers `None` here and in the reference, and that is what `skip_signature`'s blank-line guard and the missing
`--` are for. `super` joins the rows now that the slice after this one transcribes its fourteen lines: the
recipe reads the same on both sides and the table is still `None` on both.

A class Python wrote keeps no `tp_doc` of its own — until `type_new_impl` looks in the namespace it was handed,
copies a `__doc__` that holds a string into the field, and leaves it empty for `None` and for every other
value. The copy happens once, at the build, and `type_set_doc` never writes the field again. So the sentence a
class reads at `__doc__` — which CPython takes from the namespace, running it through its descriptor, and does
not cut — and the sentence a table is cut from are the same text only until a program rewrites the name. Two
rows below read one class before and after such a write, and one more after a rename, because the name a heap
type is read under is `__name__` and not `__qualname__`: `class Outer: class In: "In(a)... "` reports `(a)`
and the same body written `"Outer.In(a)... "` reports nothing at all.
"""

import builtins

ELEVEN = [
    "bool",
    "float",
    "complex",
    "list",
    "tuple",
    "set",
    "frozenset",
    "property",
    "classmethod",
    "staticmethod",
    "object",
]

# The five cursors of the two slices before this one, read the same way to show the rule is not new.
CURSORS = ["reversed", "enumerate", "zip", "map", "filter"]

# Sentences that open with a bracket and end with an arrow, and so carry no table at all.
RECIPES = ["int", "dict", "slice", "str", "bytes", "type", "range", "super"]


def halves(kind):
    """The two reads of a type's one text, and what its own dictionary files under the name.

    The dictionary row tells the last half of the story: a built-in type files the sentence with its table cut
    away, a class Python wrote files the string its body wrote, and `property` files neither — it files a
    member over the docstring of the values it makes, which has nothing to do with its own sentence.
    """
    doc = kind.__doc__
    filed = kind.__dict__["__doc__"]
    return (
        kind.__name__
        + " sig="
        + repr(kind.__text_signature__)
        + " len="
        + str(len(doc) if isinstance(doc, str) else -1)
        + " filed="
        + (repr(filed[:20]) if isinstance(filed, str) else type(filed).__name__)
    )


print("== the eleven tables, and the sentence under each ==")
for name in ELEVEN:
    kind = getattr(builtins, name)
    print(halves(kind))
    print("   " + repr(kind.__doc__[:60]) + " ... " + repr(kind.__doc__[-24:]))

print()
print("== the same two reads of the five cursors ==")
for name in CURSORS:
    kind = getattr(builtins, name)
    print(halves(kind))
    print("   " + repr(kind.__doc__[:44]))

print()
print("== eight sentences that open like a table and are not one ==")
for name in RECIPES:
    kind = getattr(builtins, name)
    print(
        name
        + " sig="
        + repr(kind.__text_signature__)
        + " first="
        + repr(kind.__doc__.split("\n")[0])
    )


class Plain:
    """Plain(a, b)
    --

    A docstring the compiler dedented, with a table above its rule.
    """


class Written:
    "Written(v)\n--\n\nThe same shape, written as one string with the newlines named."


class NoDoc:
    pass


class Misnamed:
    "Other(a)\n--\n\nA table for a class nobody called Other.\n"


class NoRule:
    "NoRule(a)\nA bracket and no rule under it.\n"


class BlankFirst:
    "BlankFirst(a)\n\n--\n\nA blank line reaches the reader before the rule does.\n"


class Emptied:
    "Emptied()\n--\n\nNothing to pass, and a table that says so.\n"


class NothingUnder:
    "NothingUnder(x)\n--\n\n"


class Holder:
    class Nested:
        "Nested(q)\n--\n\nRead under the short name, not the qualified one.\n"


class Dotted:
    class In:
        "Dotted.In(x)\n--\n\nWritten under a name the type is not read by.\n"


class NotText:
    __doc__ = 42


class Described:
    __doc__ = property(lambda self: "Described(a)\n--\n\nNever a string at the build.\n")


Dynamic = type("Dynamic", (), {"__doc__": "Dynamic(x, /)\n--\n\nBuilt by the three-argument call.\n"})


class Late:
    "Late(a)\n--\n\nThe sentence the build saw.\n"


class Renamed:
    "Gaze(a)\n--\n\nA table the name only matches later.\n"


class Sub(Plain):
    pass


print()
print("== a class's own sentence, read by both ==")
for kind in [
    Plain,
    Written,
    NoDoc,
    Misnamed,
    NoRule,
    BlankFirst,
    Emptied,
    NothingUnder,
    Dynamic,
    Sub,
]:
    print(halves(kind))
    print("   " + repr(kind.__doc__))

print()
print("== the short name, not the qualified one ==")
for kind in [Holder.Nested, Dotted.In]:
    print(
        kind.__qualname__
        + " name="
        + kind.__name__
        + " sig="
        + repr(kind.__text_signature__)
    )

print()
print("== what the name holds is not what the table was cut from ==")
print("nottext", halves(NotText), "doc=" + repr(NotText.__doc__))
print("described", halves(Described), "doc=" + type(Described.__doc__).__name__)
Late.__doc__ = "Late(z)\n--\n\nThe sentence written afterwards.\n"
print("late", halves(Late), "doc=" + repr(Late.__doc__))
print("renamed before", repr(Renamed.__text_signature__))
Renamed.__name__ = "Gaze"
print("renamed after", repr(Renamed.__text_signature__), "qualname=" + Renamed.__qualname__)
