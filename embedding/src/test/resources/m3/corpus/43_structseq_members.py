"""The members a named sequence files, and the three ways writing one of them is refused.

``sys.version_info``'s five names are not attributes a script could shadow: they are members CPython files
in the type, each reached as a ``member_descriptor`` — the same object every time, since the type's
dictionary holds one — with a doc of its own, the class it belongs to as its ``__objclass__``, and a
qualified name built from the type's *undotted* half. Reading ``version_info.major`` asks that descriptor
for the item underneath; reading ``type(version_info).major`` stops at the descriptor itself.

Two refusals of the receiver are worth telling apart, because they quote the type differently. Reaching a
member with a value of the wrong kind complains with the type's full ``tp_name``, ``sys.version_info``,
which is what the descriptor was built from; calling the same member without a receiver at all complains
with the undotted half, ``version_info.__replace__()``, because that sentence is assembled from the type's
``__qualname__``.

Writing is refused three ways, and which one a script hears depends on what was filed under the name. A
member with no setter gives ``readonly attribute``, from the descriptor. A plain value in the type's
dictionary — ``n_fields``, ``__match_args__`` — is refused by the generic setter, which names the value's
type and the name it could not write. A name nobody filed at all is refused twice over: once for the name,
and once for the fact that the value has no dictionary to put it in.

The patch number is the one item no line of this output may show, because the interpreter being tested and
the one it is tested against are not the same release. So a field read is printed as a question about
where it came from — the same item of the tuple underneath, the same value the attribute gives — and the
only descriptors shown whole are the ones whose repr names no number."""

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
FIELDS = ("major", "minor", "micro", "releaselevel", "serial")

for position, name in enumerate(FIELDS):
    filed = getattr(kind, name)
    print(name, "repr:", describe("repr", lambda: filed))
    print(name, "names:", describe("names", lambda: (filed.__name__, filed.__qualname__)))
    print(name, "doc:", describe("doc", lambda: filed.__doc__))
    print(name, "class:", describe("class", lambda: filed.__objclass__ is kind))
    print(name, "is the tuple item:", describe("read", lambda: filed.__get__(version, kind) == tuple(version)[position]))
    print(name, "is the attribute:", describe("attr", lambda: filed.__get__(version, kind) == getattr(version, name)))
    print(name, "through a value:", describe("bound", lambda: getattr(version, name) == filed.__get__(version)))

print("one descriptor:", describe("same", lambda: kind.major is kind.major))
print("one per name:", describe("distinct", lambda: kind.major == kind.minor))
print("descriptor type:", describe("type", lambda: (type(kind.major).__name__, type(kind.major))))
print("descriptor module:", describe("module", lambda: type(kind.major).__module__))
print("descriptor is not a method:", describe("family", lambda: (
    isinstance(kind.major, type(tuple.count)), type(kind.count).__name__)))
print("the tuple has no such member:", describe("base", lambda: tuple.major))
print("has it anyway:", describe("has", lambda: hasattr(tuple, "major")))

print("get one argument:", describe("g1", lambda: kind.major.__get__(version) == version.major))
print("get both:", describe("g2", lambda: kind.major.__get__(version, kind) == version.major))
print("owner ignored:", describe("g3", lambda: kind.major.__get__(version, tuple) == version.major))
print("get through a class:", describe("g4", lambda: kind.major.__get__(None, kind) is kind.major))
print("get nothing:", describe("g5", lambda: kind.major.__get__(None)))
print("get no arguments:", describe("g6", lambda: kind.major.__get__()))
print("get by name:", describe("g7", lambda: kind.major.__get__(obj=version)))
print("get too many:", describe("g8", lambda: kind.major.__get__(version, kind, kind)))
print("delete no arguments:", describe("d0", lambda: kind.major.__delete__()))
print("delete too many:", describe("d3", lambda: kind.major.__delete__(version, 1)))
print("delete by name:", describe("dn", lambda: kind.major.__delete__(obj=version)))
print("set no arguments:", describe("s0", lambda: kind.major.__set__()))
print("set one argument:", describe("s1", lambda: kind.major.__set__(kind)))
print("set too many:", describe("s3", lambda: kind.major.__set__(version, 1, 2)))
print("set by name:", describe("sn", lambda: kind.major.__set__(obj=version, value=1)))
print("set through a wrong value:", describe("s2", lambda: kind.major.__set__(4, 1)))
print("delete through a wrong value:", describe("d2", lambda: kind.major.__delete__(4)))
print("read a list:", describe("wrong", lambda: kind.major.__get__([1, 2])))

print("method names:", describe("m names", lambda: kind.__replace__.__qualname__))
print("replace doc:", describe("r doc", lambda: kind.__replace__.__doc__))
print("the bound method carries it:", describe("b doc", lambda: (
    version.__replace__.__doc__ == kind.__replace__.__doc__, version.__reduce__.__doc__)))
print("reduce names:", describe("r names", lambda: (kind.__reduce__.__name__, kind.__reduce__.__qualname__)))
print("inherited identity:", describe("inherited", lambda: kind.count is tuple.count))
print("inherited call:", describe("call", lambda: kind.count(version, version.major)))
print("inherited wrong receiver:", describe("bad", lambda: kind.count("abc", 3)))
print("inherited no receiver:", describe("unbound", lambda: kind.count()))
print("reduce wrong receiver:", describe("reduce bad", lambda: kind.__reduce__(1)))
print("replace wrong receiver:", describe("replace bad", lambda: kind.__replace__(1, major=2)))

print("written by hand:", describe("value", lambda: getattr(version, "major") == version[0]))
print("through the class:", describe("via", lambda: getattr(kind, "major").__get__(version) == version.major))
print("counts are ints:", describe("int", lambda: type(kind.n_fields).__name__))
print("match args is a tuple:", describe("tuple", lambda: type(kind.__match_args__).__name__))
print("match args are the fields:", describe("margs", lambda: kind.__match_args__ == FIELDS))

print("write a member:", describe("member", lambda: setattr(version, "major", 4)))
print("delete a member:", describe("member del", lambda: delattr(version, "major")))
print("write through the descriptor:", describe("descr set", lambda: kind.major.__set__(version, 4)))
print("delete through the descriptor:", describe("descr del", lambda: kind.major.__delete__(version)))
print("write a plain value:", describe("plain", lambda: setattr(version, "n_fields", 2)))
print("delete a plain value:", describe("plain del", lambda: delattr(version, "n_unnamed_fields")))
print("write a filed name spelled wrong:", describe("wrong", lambda: setattr(version, "majorr", 1)))
print("delete a name nobody filed:", describe("wrong del", lambda: delattr(version, "majorr")))
print("write on the type:", describe("type", lambda: setattr(kind, "major", 1)))
print("write the counts on the type:", describe("counts", lambda: setattr(kind, "n_fields", 2)))
print("write match args on the type:", describe("match", lambda: setattr(kind, "__match_args__", ())))
print("delete the doc on the type:", describe("doc", lambda: delattr(kind, "__doc__")))
