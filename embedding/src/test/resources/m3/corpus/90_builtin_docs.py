import builtins

bfom = type(builtins.all)

# The forty names this runtime files in builtins as builtin_function_or_method. Ten of CPython's
# forty-five are absent here, so the list is pinned rather than read out of the namespace.
NAMES = [
    "__build_class__", "abs", "all", "any", "bin", "callable", "chr", "delattr", "dir",
    "divmod", "enumerate", "filter", "format", "getattr", "globals", "hasattr", "hash",
    "hex", "id", "isinstance", "issubclass", "iter", "len", "locals", "map", "max", "min",
    "next", "oct", "ord", "pow", "print", "repr", "reversed", "round", "setattr", "sorted",
    "sum", "vars", "zip",
]


def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "->", type(e).__name__, e)


class Point:
    pass


def set_del():
    p = Point()
    builtins.setattr(p, "x", 1)
    got = builtins.getattr(p, "x")
    builtins.delattr(p, "x")
    return got, hasattr(p, "x")


print("== the two halves of one text ==")
print("names", len(NAMES), "kind", bfom.__name__)
for name in NAMES:
    f = getattr(builtins, name)
    print(name, "|", repr(f.__text_signature__), "|", type(f.__doc__).__name__, "|", repr(f.__doc__))

print("== the eight with no clinic signature ==")
for name in ["__build_class__", "dir", "getattr", "iter", "max", "min", "next", "vars"]:
    f = getattr(builtins, name)
    print(name, "|", repr(f.__text_signature__), "|", repr(f.__doc__.split("\n")[0]))

print("== the signature a wrapped line carries ==")
for name in ["format", "pow", "print", "round", "sorted", "sum", "hash", "id", "abs"]:
    f = getattr(builtins, name)
    print(name, "|", repr(f.__text_signature__), "|", repr(f.__doc__.split("\n")[0]),
          "|", repr(f.__doc__.split("\n")[-1]))

print("== the five that name themselves, not $module ==")
for name in ["enumerate", "filter", "map", "reversed", "zip"]:
    f = getattr(builtins, name)
    print(name, "|", repr(f.__text_signature__), "|", f.__name__, "|", f.__qualname__, "|",
          repr(f.__doc__.split("\n")[0]))

print("== what the text does not change ==")
for name in ["abs", "print", "sorted"]:
    f = getattr(builtins, name)
    show(name + " repr", lambda: repr(f))
    show(name + " module", lambda: f.__module__)
    show(name + " self kind", lambda: type(f.__self__).__name__)
    show(name + " dict?", lambda: hasattr(f, "__dict__"))
    show(name + " callable", lambda: callable(f))

print("== the doc half is a read, not a field ==")
show("write all.__doc__", lambda: setattr(builtins.all, "__doc__", "x"))
show("delete all.__doc__", lambda: delattr(builtins.all, "__doc__"))
show("write len.__text_signature__", lambda: setattr(builtins.len, "__text_signature__", "()"))
show("the row is the same read", lambda: builtins.__dict__["len"].__doc__ == len.__doc__)
show("a second read of the doc", lambda: getattr(abs, "__doc__") == abs.__doc__)

print("== the same text on the other shapes ==")
show("abs through builtins", lambda: builtins.abs.__doc__ == getattr(builtins, "abs").__doc__)
show("min called", lambda: min([3, 1, 2]))
show("max with key", lambda: max([1, 2, 3], key=lambda x: -x))
show("next called", lambda: next(iter([7])))
show("iter called", lambda: list(iter("ab")))
show("enumerate called", lambda: list(enumerate("ab")))
show("filter called", lambda: list(filter(None, [0, 1, 2])))
show("map called", lambda: list(map(str, [1, 2])))
show("zip called", lambda: list(zip("ab", "cd")))
show("reversed called", lambda: list(reversed([1, 2, 3])))
show("sorted called", lambda: sorted([3, 1, 2]))
show("sum called", lambda: sum([1, 2], 10))
show("divmod called", lambda: divmod(7, 2))
show("pow called", lambda: pow(2, 10, 1000))
show("round called", lambda: round(2.5))
show("format called", lambda: format(1, ""))
show("vars called", lambda: vars(Point()))
show("dir called", lambda: dir(builtins.all) == sorted(dir(builtins.all)))
show("hasattr called", lambda: hasattr(1, "real"))
show("getattr called", lambda: getattr(1, "real"))
show("setattr and delattr", lambda: set_del())
