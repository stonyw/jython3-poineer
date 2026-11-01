"""What a built-in dictionary's row asks of the value it is read through, and what it says when the
answer is not its own class.

Every row of a built-in type is a descriptor, and CPython binds one in two steps: the first looks only
at the argument list — no receiver at all is `__get__(None, None) is invalid`, a third argument is one
too many, and a keyword name the wrapper has no place for is refused outright — and only the second
looks at the receiver. That second step is `descr_check` (Objects/descrobject.c:80), one function
reached from four getters, and it asks a single question of a single thing: is this value an instance of
the class that files this row? It does not care which class the read was *addressed* to, so a wrong
second argument changes nothing; it does not ask a metaclass, so `__instancecheck__` has no part in it;
and it is not asked at all of the two shapes that bind to something else — a class method takes its
class, a static method takes nothing — nor of a read through the class, which hands back the row itself.

Nothing here prints a bound method, a function or a descriptor's repr, all of which carry an address.
"""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


def shape(row, value, *rest):
    """The four things a successful read can be told apart by, none of them an address."""
    got = row.__get__(value, *rest)
    return (type(got).__name__, getattr(got, "__name__", "-"), got is row,
            getattr(got, "__self__", None) is value, getattr(got, "__doc__", "-"))


class Meta(type):
    def __instancecheck__(cls, other):
        return True


class Always(metaclass=Meta):
    pass


CURSOR = type(iter(range(3)))
PROXY = type(vars(object))
TEXT = "text"

print("== the row copied out of a slot and the row a method table files, each read through a value")
for T, value, names in (
    (bytes, b"ab", ["__len__", "__repr__", "__iter__", "__eq__", "count", "index"]),
    (CURSOR, iter(range(3)), ["__iter__", "__next__", "__length_hint__", "__reduce__"]),
    (frozenset, frozenset({1}), ["__and__", "__len__", "copy"]),
    (PROXY, vars(object), ["__len__", "get", "keys", "__eq__"]),
):
    print(T.__name__ if T is not PROXY else "mappingproxy")
    for name in names:
        member = vars(T)[name]
        print("    %-18s %s" % (name, type(member).__name__))
        print("      %-14s %s" % ("own", said(lambda: shape(member, value))))
        print("      %-14s %s" % ("str", said(lambda: shape(member, TEXT))))
        print("      %-14s %s" % ("int", said(lambda: shape(member, 3))))
        print("      %-14s %s" % ("a class", said(lambda: shape(member, T))))
        print("      %-14s %s" % ("an unrelated value", said(lambda: shape(member, Always()))))

print("== the reads of one row, in the order the reference makes them")
for name in ("__len__", "count"):
    member = vars(bytes)[name]
    print("   ", name, type(member).__name__)
    print("      %-16s %s" % ("own value", said(lambda: shape(member, b"ab"))))
    print("      %-16s %s" % ("foreign", said(lambda: shape(member, TEXT))))
    print("      %-16s %s" % ("foreign, own owner", said(lambda: shape(member, TEXT, bytes))))
    print("      %-16s %s" % ("own, foreign owner", said(lambda: shape(member, b"ab", int))))
    print("      %-16s %s" % ("the class itself", said(lambda: shape(member, bytes))))
    print("      %-16s %s" % ("nothing", said(lambda: shape(member, None))))
    print("      %-16s %s" % ("nothing, own class", said(lambda: shape(member, None, bytes))))
    print("      %-16s %s" % ("nothing, other class", said(lambda: shape(member, None, int))))
    print("      %-16s %s" % ("no argument", said(lambda: member.__get__())))
    print("      %-16s %s" % ("three", said(lambda: member.__get__(b"ab", bytes, 1))))
    print("      %-16s %s" % ("by name", said(lambda: member.__get__(obj=b"ab"))))
    print("      %-16s %s" % ("both by name", said(lambda: member.__get__(b"ab", owner=bytes))))

print("== the rows that bind to a class, which never ask about the value")
fromkeys = vars(dict)["fromkeys"]
print("    fromkeys         ", type(fromkeys).__name__)
print("      %-16s %s" % ("own value", said(lambda: shape(fromkeys, {1: 2}))))
print("      %-16s %s" % ("foreign", said(lambda: shape(fromkeys, TEXT))))
print("      %-16s %s" % ("own class", said(lambda: shape(fromkeys, None, dict))))
print("      %-16s %s" % ("other class", said(lambda: shape(fromkeys, None, int))))
print("      %-16s %s" % ("nothing", said(lambda: shape(fromkeys, None))))
maketrans = vars(bytes)["maketrans"]
print("    maketrans        ", type(maketrans).__name__)
print("      %-16s %s" % ("own value", said(lambda: shape(maketrans, b"ab"))))
print("      %-16s %s" % ("foreign", said(lambda: shape(maketrans, TEXT))))
print("      %-16s %s" % ("nothing", said(lambda: type(maketrans.__get__(None, int)).__name__)))
new = vars(int)["__new__"]
print("    int.__new__      ", type(new).__name__)
print("      %-16s %s" % ("own value", said(lambda: shape(new, 3))))
print("      %-16s %s" % ("foreign", said(lambda: shape(new, TEXT))))

print("== the rows of a class every value answers to")
for name in ("__format__", "__sizeof__", "__dir__", "__reduce__", "__class__"):
    member = vars(object)[name]
    print("   ", name, type(member).__name__)
    print("      %-16s %s" % ("a str", said(lambda: shape(member, TEXT))))
    print("      %-16s %s" % ("an int", said(lambda: shape(member, 3))))
    print("      %-16s %s" % ("a class", said(lambda: shape(member, bytes))))

print("== the member and the getset, which check the same way")
import sys

major = vars(type(sys.version_info))["major"]
print("    major            ", type(major).__name__)
print("      %-16s %s" % ("own value", said(lambda: shape(major, sys.version_info))))
print("      %-16s %s" % ("foreign", said(lambda: shape(major, TEXT))))
args = vars(BaseException)["args"]
print("    args             ", type(args).__name__)
print("      %-16s %s" % ("own value", said(lambda: shape(args, Exception("x")))))
print("      %-16s %s" % ("foreign", said(lambda: shape(args, TEXT))))

print("== a subclass of the filing class is an instance of it")


class Sub(Exception):
    pass


sub = Sub("x")
str_row = vars(BaseException)["__str__"]
print("    __str__          ", type(str_row).__name__)
print("      %-16s %s" % ("subclass", said(lambda: shape(str_row, sub))))
print("      %-16s %s" % ("foreign", said(lambda: shape(str_row, TEXT))))
print("      %-16s %s" % ("args through subclass", said(lambda: shape(args, sub))))

print("== the same names reached by reading them, not by calling __get__")
print("    count is the row ", said(lambda: bytes.count is vars(bytes)["count"]))
print("    bound count      ", said(lambda: shape(vars(bytes)["count"], b"ab")))
print("    len is the row   ", said(lambda: bytes.__len__ is vars(bytes)["__len__"]))
print("    proxy get is row ", said(lambda: PROXY.get is vars(PROXY)["get"]))
print("    super wrong obj  ", said(lambda: super(bytes, TEXT)))
print("    super right obj  ", said(lambda: type(super(bytes, b"ab").count).__name__))
