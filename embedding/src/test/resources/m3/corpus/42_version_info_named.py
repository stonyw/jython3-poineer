"""The named sequence ``sys.version_info`` is, and the tuple it never stops being.

``sys.version_info`` is not a tuple with a name attached to it in the script: it is an instance of a type
the interpreter builds in C, derived from ``tuple``, whose ``tp_name`` is ``sys.version_info`` and whose
``__name__`` is the half after the dot. That type files five members and three counts in its own
dictionary, so the names are reachable from a value as well as from the class, and the repr writes itself
as ``name=value`` pairs instead of as a parenthesised run of items. Everything else about it is the tuple
underneath: a slice is a plain tuple, so is a concatenation, hashing is tuple hashing so a value is a key
for the equivalent tuple, comparison runs against a bare tuple in either direction, and ``count`` and
``index`` are the two ``tuple`` has.

What is filed is a *member*, not an attribute. The five names have no setter, so writing one complains
``readonly attribute`` rather than about a name that is not there, while a name nobody filed is refused by
the class and the value each in their own words. The type cannot be called: CPython leaves its ``tp_new``
empty, so the refusal is about creating an instance and not about the arguments handed over.

``__replace__`` is the one way to a second value of the type. It takes no positionals and only names it
recognises, and the copy it returns is a new object that compares equal — including when a field is given
something the release it describes would never hold, because the fields are never type checked.

The patch number is the one item no line of this output may show, because the interpreter being tested and
the one it is tested against are not the same release. Every display of a version is therefore printed
with that number replaced, and every other answer about it is a relationship — to the tuple under it, to
its own fields — rather than a number."""

import sys


def written(value):
    """The display of ``value``, with this release's patch number replaced."""
    return repr(value).replace("micro=" + repr(sys.version_info.micro), "micro=patch")


def describe(label, thunk):
    try:
        return label + ": " + written(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


version = sys.version_info
kind = type(version)

print("the type:", describe("type", lambda: kind))
print("name:", describe("name", lambda: kind.__name__))
print("qualname:", describe("qualname", lambda: kind.__qualname__))
print("module:", describe("module", lambda: kind.__module__))
print("doc on the type:", describe("doc", lambda: kind.__doc__))
print("doc on a value:", describe("value doc", lambda: version.__doc__))
print("bases:", describe("bases", lambda: kind.__bases__[0] is tuple))
print("mro:", describe("mro", lambda: [one.__name__ for one in kind.__mro__]))
print("subclass of tuple:", describe("subclass", lambda: issubclass(kind, tuple)))
print("instance of tuple:", describe("instance", lambda: isinstance(version, tuple)))
print("its own class:", describe("own", lambda: version.__class__ is kind))
print("one object:", describe("same", lambda: sys.version_info is sys.version_info))

print("repr:", describe("repr", lambda: version))
print("str is repr:", describe("str", lambda: str(version) == repr(version)))
print("format:", describe("format", lambda: format(version) == repr(version)))
print("interpolated:", describe("f", lambda: f"{version}" == repr(version)))
print("percent:", describe("pct", lambda: ("%s" % (version,)) == repr(version)))
print("bad format spec:", describe("spec", lambda: format(version, "x")))
print("percent needs a spec:", describe("d", lambda: "%d" % version))

print("fields:", describe("fields", lambda: [version.major, version.minor]))
print("micro is the third item:", describe("micro", lambda: version.micro == version[2]))
print("releaselevel:", describe("level", lambda: version.releaselevel))
print("serial:", describe("serial", lambda: version.serial))
print("counts:", describe("counts", lambda: (
    version.n_fields, version.n_sequence_fields, version.n_unnamed_fields)))
print("counts on the type:", describe("tcounts", lambda: kind.n_fields))
print("match args:", describe("match", lambda: kind.__match_args__))
print("match args from a value:", describe("vmatch", lambda: version.__match_args__))
print("no _fields:", describe("_fields", lambda: version._fields))
print("nothing filed:", describe("nope", lambda: version.nope))
print("no instance dict:", describe("dict", lambda: hasattr(version, "__dict__")))

print("indexing:", describe("at", lambda: (version[0], version[-1])))
print("length:", describe("len", lambda: len(version)))
print("truth:", describe("bool", lambda: bool(version)))
print("slice:", describe("slice", lambda: (version[:2], type(version[:2]).__name__)))
major, minor, micro, releaselevel, serial = version
print("unpacked:", describe("unpack", lambda: (major, minor, releaselevel, serial)))
print("unpacking is the whole value:", describe("all", lambda: (
    major, minor, micro, releaselevel, serial) == tuple(version)))
head, *tail = version
print("star unpack:", describe("star", lambda: (head, len(tail), type(tail).__name__)))
print("equals the tuple:", describe("eq", lambda: version == tuple(version)))
print("not a shorter tuple:", describe("ne", lambda: version != (version.major, version.minor)))
print("against a tuple:", describe("lt", lambda: version < (3, 15)))
print("a tuple against it:", describe("gt", lambda: (3, 14) < version))
print("compares to text:", describe("text", lambda: version > "x"))
print("hashes as a tuple:", describe("hash", lambda: hash(version) == hash(tuple(version))))
print("is a key for the tuple:", describe("key", lambda: {tuple(version): "x"}[version]))
print("membership:", describe("in", lambda: "final" in version))
print("concatenation type:", describe("add", lambda: type((version + (1,))).__name__))
print("repeat type:", describe("mul", lambda: type(version * 1).__name__))
print("inherited methods:", describe("methods", lambda: (version.count(3), version.index("final"))))
print("iterated:", describe("iter", lambda: (
    len(list(version)), list(version)[:2], list(version)[3:])))
print("reversed:", describe("rev", lambda: (
    len(list(reversed(version))), list(reversed(version))[:2])))
print("cast:", describe("cast", lambda: (tuple(version) == list(version), list(version)[:2])))

print("write a field:", describe("write", lambda: setattr(version, "major", 4)))
print("delete a field:", describe("delete", lambda: delattr(version, "major")))
print("write a name nobody filed:", describe("fresh", lambda: setattr(version, "brand_new", 1)))
print("delete a name nobody filed:", describe("fresh del", lambda: delattr(version, "brand_new")))
print("write on the type:", describe("type write", lambda: setattr(kind, "major", 1)))
print("delete on the type:", describe("type delete", lambda: delattr(kind, "serial")))
print("call the type:", describe("call", lambda: kind(tuple(version))))
print("call it empty:", describe("call0", lambda: kind()))

copy = version.__replace__()
print("replaced copy:", describe("copy", lambda: copy))
print("copy equals and is not:", describe("identity", lambda: (copy == version, copy is version)))
print("one field changed:", describe("one", lambda: version.__replace__(major=4)))
print("two fields:", describe("two", lambda: version.__replace__(serial=2, releaselevel="beta")))
print("no type check:", describe("any", lambda: version.__replace__(major="whatever")))
print("unpacked empty:", describe("star empty", lambda: version.__replace__(*[])))
print("by name through a mapping:", describe("mapping", lambda: version.__replace__(**{"micro": 9})))
print("the mapping arrived:", describe("mapping read", lambda: version.__replace__(micro=9).micro))
print("unknown name:", describe("unknown", lambda: version.__replace__(nope=1)))
print("two unknown names:", describe("two unknown", lambda: version.__replace__(a=1, b=2)))
print("positional:", describe("positional", lambda: version.__replace__(4)))
print("through the class:", describe("unbound", lambda: kind.__replace__(version, micro=1).micro))
print("through the class, no receiver:", describe("unbound0", lambda: kind.__replace__(micro=1)))
print("through the class, two values:", describe("unbound2", lambda: kind.__replace__(version, version)))
print("reduce:", describe("reduce", lambda: (
    version.__reduce__()[0] is kind,
    tuple(version.__reduce__()[1][0]) == tuple(version),
    version.__reduce__()[1][1])))
print("reduce is a pair:", describe("reduce type", lambda: (
    type(version.__reduce__()) is tuple, len(version.__reduce__()))))
print("reduce extra:", describe("reduce extra", lambda: kind.__reduce__(version, 1)))
print("reduce named:", describe("reduce named", lambda: version.__reduce__(x=1)))


def classify(value):
    match value:
        case kind(3, 14):
            return "3.14 by position"
        case kind(major=3):
            return "some other 3.x"
        case kind():
            return "a version_info of its own"
    return "not a version_info"


print("pattern by position:", describe("p1", lambda: classify(version)))
print("pattern by name:", describe("p2", lambda: classify(version.__replace__(major=4))))
print("pattern of a bare tuple:", describe("p3", lambda: classify(tuple(version))))
