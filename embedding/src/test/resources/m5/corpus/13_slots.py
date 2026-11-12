"""`__slots__`: the storage a class declares, and the layout it fixes.

A class that names its fields in `__slots__` gives its values no dictionary, so a read or a write
of a declared name goes through the descriptor the class files, a name the class did not declare
is refused instead of stored, and the two refusals of an empty slot read as they do.  Fields are
inherited by merging, a second declaration of a field in a subclass opens a slot of its own beside
the base's, `__slots__ = ()` is the empty case, and `__dict__` and `__weakref__` may be named to
keep either storage beside the fields.  The layout a declaration fixes is also what makes
`__bases__ assignment: ... object layout differs ...` reachable, the refusal
`12_mutable_bases.py` could not reach.
"""


def refusal(label, action):
    try:
        action()
    except Exception as exc:
        print(label, type(exc).__name__, str(exc))
    else:
        print(label, "accepted")


class Slotted:
    __slots__ = ("a", "b")


s = Slotted()
print("slots", Slotted.__slots__, type(Slotted.__slots__).__name__)
print("descriptors", type(Slotted.__dict__["a"]).__name__, type(Slotted.__dict__["b"]).__name__)
print("no dict", hasattr(s, "__dict__"), "__dict__" in vars(Slotted))
s.a = 1
s.b = 2
print("write", s.a, s.b, s.a + s.b)
del s.b
print("deleted", hasattr(s, "b"))
refusal("read empty", lambda: s.b)
refusal("delete empty", lambda: delattr(s, "b"))
refusal("undeclared", lambda: setattr(s, "c", 3))
print("still", s.a, "__dict__" in vars(Slotted))


class Sub(Slotted):
    __slots__ = ("c",)


sub = Sub()
sub.a = "a"
sub.b = "b"
sub.c = "c"
print("merged", sub.a, sub.b, sub.c, hasattr(sub, "__dict__"), Sub.__slots__)
print("inherited descriptor", type(Sub.__dict__["c"]).__name__, "a" in Sub.__dict__)


class Dup(Slotted):
    __slots__ = ("a",)


d = Dup()
d.a = "sub"
print("dup own", d.a)
refusal("dup base", lambda: Slotted.__dict__["a"].__get__(d, Dup))
Slotted.__dict__["a"].__set__(d, "base")
print("dup both", d.a, Slotted.__dict__["a"].__get__(d, Dup))


class Empty:
    __slots__ = ()


e = Empty()
print("empty", Empty.__slots__, hasattr(e, "__dict__"), "__dict__" in vars(Empty))
refusal("empty write", lambda: setattr(e, "x", 1))


class WithDict:
    __slots__ = ("a", "__dict__")


wd = WithDict()
wd.a = 1
wd.extra = 2
print("with dict", hasattr(wd, "__dict__"), wd.a, wd.extra, vars(wd), hasattr(wd, "__weakref__"))


class WithWeak:
    __slots__ = ("b", "__weakref__")


ww = WithWeak()
print("with weak", hasattr(ww, "__weakref__"), hasattr(ww, "__dict__"), WithWeak.__slots__)
refusal("dict twice", lambda: type("TwiceDict", (WithDict,), {"__slots__": ("__dict__",)}))
refusal("weak twice", lambda: type("TwiceWeak", (WithWeak,), {"__slots__": ("__weakref__",)}))


class Other:
    pass


class BaseSlots:
    __slots__ = ("x",)


refusal("layout", lambda: setattr(BaseSlots, "__bases__", (Other,)))


class Layout:
    __slots__ = ("x",)


class Plain:
    pass


class Kid(Layout):
    pass


layout = Layout()
refusal("bases layout", lambda: setattr(Kid, "__bases__", (Plain,)))
refusal("class layout", lambda: setattr(layout, "__class__", Plain))


refusal("invalid name", lambda: type("Bad", (), {"__slots__": ("a b",)}))
refusal("not string", lambda: type("Bad", (), {"__slots__": (42,)}))
refusal("not iterable", lambda: type("Bad", (), {"__slots__": 42}))
refusal("duplicate", lambda: type("Bad", (), {"__slots__": ("a", "a")}))


class Priv:
    __slots__ = ("__a",)

    def __init__(self):
        self.__a = "private"


p = Priv()
print("mangled", sorted(vars(Priv)), p._Priv__a)
refusal("mangled read", lambda: p.__a)
