"""The `raw-unicode-escape` codec's two halves, and the two offsets `type` reports about a layout.

The codec writes a backslash escape only where a character does not fit one octet, so every ASCII
and latin-1 character is itself and everything above is `\\uXXXX` or `\\UXXXXXXXX`.  Decoding is the
same grammar read backwards, with two differences that are easy to get wrong: a lone backslash is
copied through rather than refused, and a surrogate pair written as two escapes stays two
characters rather than becoming one.  The refusals are the shape `unicode-escape` uses — the octets
that did arrive, with the reason naming what ran out.

`__dictoffset__` and `__weakrefoffset__` say where a value's dictionary and weak-reference list
live.  A class written in Python keeps both in managed slots, which the reference reports as the
sentinel -1 and -32 rather than as byte offsets; the families that brought a real field keep it when
subclassed, and a class that declared `__slots__` without naming the storage answers nought.
"""


def decode(data, errors="strict"):
    try:
        return repr(data.decode("raw-unicode-escape", errors))
    except UnicodeDecodeError as error:
        return "%s: %s" % (type(error).__name__, error)


# The normal path: octets that are not a backslash are already characters, so only the escapes do
# anything, and a backslash that introduces nothing is copied through with its neighbour.
for data in (
    b"abc",
    b"\xc3\xa9",
    b"\xff",
    b"\\u00e9",
    b"\\u0041\\u0042",
    b"\\u0000",
    b"\\U0001f600",
    b"\\U0010ffff",
    b"\\ud800",
    b"\\udc00",
    b"\\U0000d800",
    b"\\x41",
    b"\\n",
    b"\\",
    b"a\\",
    b"\\\\u0041",
    b"b\\u00e9c",
):
    print(repr(data), decode(data))


# The refusal path: a `u` escape wants four digits and a capital `U` eight, and the walk stops at
# the first octet that is not one.  The four handlers then answer for that range.
for data in (
    b"\\u12",
    b"\\uZZZZ",
    b"\\U0011ZZZZ",
    b"\\U00110000",
    b"\\U0000004",
    b"b\\u",
    b"\\u00e9\\u12",
):
    for errors in ("strict", "replace", "ignore", "backslashreplace"):
        print(repr(data), errors, decode(data, errors))


# The encode half: one octet below 0x100, `\\uXXXX` up to 0xffff and `\\UXXXXXXXX` above it, with
# surrogates escaped rather than refused.
for text in (
    "abc",
    "é",
    "ÿ",
    "\u0100",
    "\u0100\u0101",
    "\uffff",
    "\U0001f600",
    "\U0010ffff",
    "\ud800",
    "\udc00",
    "a\\u0041",
    "\n\t",
):
    print(repr(text), repr(text.encode("raw-unicode-escape")))


print("roundtrip", "aé\u0100\U0001f600".encode("raw-unicode-escape").decode("raw-unicode-escape")
      == "aé\u0100\U0001f600")


def offsets(label, cls):
    print(label, cls.__basicsize__, cls.__dictoffset__, cls.__weakrefoffset__)


# The compiled-in roots: the exception family carries a real dictionary field and no weak
# references, `type` carries both as real fields, and `set`/`frozenset` bring a real weak-reference
# field that a subclass inherits.
for builtin in (
    object,
    type,
    int,
    str,
    list,
    dict,
    tuple,
    BaseException,
    Exception,
    ValueError,
    ExceptionGroup,
    set,
    frozenset,
):
    offsets(builtin.__name__, builtin)


class Plain:
    pass


class Slotted:
    __slots__ = ()


class Slot2:
    __slots__ = ("a", "b")


class WithDict:
    __slots__ = ("a", "__dict__")


class WithWeak:
    __slots__ = ("a", "__weakref__")


class WeakDict:
    __slots__ = ("__dict__", "__weakref__")


class Inherit(Plain):
    pass


class InheritSlot(Slot2):
    pass


class SubInt(int):
    pass


class SubExc(Exception):
    pass


class SubType(type):
    pass


# A written class takes a managed dictionary and managed weak references unless its slots take the
# storage away; a subclass of `int` gets a dictionary but no weak references, and a subclass of an
# exception keeps the real field the family brought.
for written in (
    Plain,
    Slotted,
    Slot2,
    WithDict,
    WithWeak,
    WeakDict,
    Inherit,
    InheritSlot,
    SubInt,
    SubExc,
    SubType,
):
    offsets(written.__name__, written)
