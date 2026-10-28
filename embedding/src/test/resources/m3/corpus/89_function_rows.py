import builtins
import sys

all_ = builtins.all
any_ = builtins.any
bfom = type(all_)
rows = bfom.__dict__


def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "->", type(e).__name__, e)


print("== the sixteen rows ==")
print("kind", bfom.__name__, "rows", len(rows), "mro", [k.__name__ for k in bfom.mro()])
for name in rows:
    row = rows[name]
    print(name, "|", type(row).__name__, "|", repr(row), "|", repr(row.__doc__))

print("== what they add to dir ==")
print("dir count", len(dir(all_)))
print("dir", dir(all_))

print("== the three shapes ==")
new = int.__dict__["__new__"]
show("all is builtins.all", lambda: all_ is builtins.all)
show("all self kind and identity", lambda: (type(all_.__self__).__name__, all_.__self__ is builtins))
show("all name qual module", lambda: (all_.__name__, all_.__qualname__, all_.__module__))
show("all signature", lambda: all_.__text_signature__)
show("all doc", lambda: all_.__doc__)
show("all reduce and repr", lambda: (all_.__reduce__(), repr(all_)))
show("any signature", lambda: any_.__text_signature__)
show("any doc and reduce", lambda: (any_.__doc__, any_.__reduce__()))
show("abs shape", lambda: (abs.__name__, abs.__qualname__, abs.__module__, abs.__reduce__()))
show("getattr signature", lambda: builtins.getattr.__text_signature__)
show("mro name qual module self", lambda: (int.mro.__name__, int.mro.__qualname__, int.mro.__module__, int.mro.__self__))
show("mro reduce doc", lambda: (int.mro.__reduce__(), int.mro.__doc__))
show("new is int.new", lambda: new is int.__new__)
show("new name qual self module", lambda: (new.__name__, new.__qualname__, new.__self__, new.__module__))
show("new doc head reduce", lambda: (new.__doc__[:34], new.__reduce__()))
show("maketrans self module reduce", lambda: (bytes.maketrans.__self__, bytes.maketrans.__module__, bytes.maketrans.__reduce__()))
show("b.maketrans is bytes.maketrans", lambda: b"".maketrans is bytes.maketrans)
show("fromkeys self reduce", lambda: (dict.fromkeys.__self__, dict.fromkeys.__reduce__()))
show("excepthook module self kind", lambda: (sys.excepthook.__module__, type(sys.excepthook.__self__).__name__))
show("excepthook signature", lambda: sys.excepthook.__text_signature__)
show("excepthook doc and name", lambda: (sys.excepthook.__doc__, sys.excepthook.__qualname__))
show("exit module", lambda: sys.exit.__module__)

print("== the rows called through the dictionary ==")
show("repr row", lambda: rows["__repr__"](all_))
show("repr row one extra", lambda: rows["__repr__"](all_, 1))
show("hash row kind", lambda: type(rows["__hash__"](all_)).__name__)
show("call row no argument", lambda: rows["__call__"]())
show("call row wrong receiver", lambda: rows["__call__"](3))
show("call row calls", lambda: rows["__call__"](all_, [1, 0]))
show("call row keywords", lambda: rows["__call__"](all_, (), x=1))
show("eq row other kind", lambda: rows["__eq__"](all_, 1))
show("eq row same", lambda: rows["__eq__"](all_, all_))
show("ne row other kind", lambda: rows["__ne__"](all_, 1))
show("lt row two", lambda: rows["__lt__"](all_, any_))
show("lt row one argument", lambda: rows["__lt__"](all_))
show("reduce row no argument", lambda: rows["__reduce__"]())
show("reduce row wrong receiver", lambda: rows["__reduce__"](1))
show("reduce row of all", lambda: rows["__reduce__"](all_))

print("== the rows read through the class ==")
for name in ("__doc__", "__name__", "__qualname__", "__self__", "__text_signature__"):
    print(name, "|", repr(rows[name]), "|", repr(rows[name].__doc__), "|", rows[name].__objclass__.__name__)
print("__module__ |", repr(rows["__module__"]), "|", rows["__module__"].__objclass__.__name__)
show("doc row is not callable", lambda: rows["__doc__"]())
show("module row qualname", lambda: rows["__module__"].__qualname__)

print("== the comparing the rows hand to ==")
show("all == all", lambda: all_ == builtins.all)
show("all == any", lambda: all_ == any_)
show("all != any", lambda: all_ != any_)
show("all == 'all'", lambda: all_ == "all")
show("mro == mro", lambda: int.mro == int.mro)
show("mro is mro", lambda: int.mro is int.mro)
show("fromhex == fromhex", lambda: bytes.fromhex == bytes.fromhex)
show("int.from_bytes == bool.from_bytes", lambda: int.from_bytes == bool.from_bytes)
show("hash kind", lambda: type(hash(all_)).__name__)
show("set of two reads", lambda: len({int.mro, int.mro}))
show("dict lookup", lambda: {all_: 1}[builtins.all])
show("all < any", lambda: all_ < any_)
show("all >= any", lambda: all_ >= any_)

print("== the one row that can be written ==")
show("set all module", lambda: setattr(all_, "__module__", "everywhere"))
show("read after set", lambda: all_.__module__)
show("set to an int", lambda: setattr(all_, "__module__", 7))
show("read that int", lambda: all_.__module__)
show("del the module", lambda: delattr(all_, "__module__"))
show("read after del", lambda: all_.__module__)
show("the shared new row", lambda: setattr(new, "__module__", "renamed") or new.__module__)
show("int.new reads it too", lambda: int.__new__.__module__)
show("set it back", lambda: setattr(new, "__module__", None))
for name in ("__doc__", "__name__", "__qualname__", "__self__", "__text_signature__"):
    show("write " + name, lambda n=name: setattr(all_, n, 1))
    show("delete " + name, lambda n=name: delattr(all_, n))
