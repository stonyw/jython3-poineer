"""The nine rows `super` files, and the one of them that rebuilds the proxy it is handed.

`super` is the fourth book of the singleton shape and the only one of the four whose rows do not stand far
above `object`: nine names filed, of which only `__get__` and the three fields are ones `object`'s twenty-four
does not already keep, so `dir()` reaches twenty-eight. Four of the nine are `wrapper_descriptor`s, three are
`member_descriptor`s, one is a `builtin_function_or_method` and the last is the sentence itself. Its `tp_doc`
is the only one of these four that no knife cuts — `find_signature` looks for the `tp_name` straight followed
by a bracket and the text opens with `super() -> same as super(__class__, <first argument>)` instead, so
`__text_signature__` is `None` and `__doc__` is the whole four hundred and seventy-one characters, first line
and all. `object`'s rows sit behind those nine, and a proxy with a value walks into `object` before it
falls back, which is why `super(int, 3).__doc__` is `object`'s one hundred and seventy-three characters while
`super(int).__doc__` — with no order to walk — is `super`'s own.

`__init__` is the row that does not return a new proxy. CPython's `super_init` refills the object it was
handed, so `row(p, Base, self)` moves a proxy that has been held since before it had a value, and a refusal
leaves every field where it was. Its gates are the same three the creator's, in the creator's words except
for one: `super_new` counts what it was given as `expected at most 2 arguments, got 3` because it is called
with the whole tuple, while `super_init` unpacks first and says `takes at most 2 arguments (3 given)`. The
no-argument read is the third way to a class: the row asks the frame, takes `self` off its arguments and the
class from the `__class__` cell, and overwrites the class the proxy already held.

`super.__new__` is the row that answers for neither half: it is a `builtin_function_or_method`, so it files
no `__objclass__`, and it hands back a proxy with both fields empty — `<super: <class 'NULL'>, NULL>` —
without running the gates that its own call runs, because `super_new` only asks the frame when it was given
no class at all.

Nothing here prints a `repr()` that names an address — a bound `__repr__` reached by the walk, a bound method
of a class defined below — and nothing prints the *call* of a row the walk took from `object`: the reference
names such a call after the value's own type, `int.__reduce__() takes no arguments (1 given)`, where jy3
names it after the type the row was filed in. The reference itself refuses a `__get__` asked of a proxy with
no value by crashing, so that read is left out on both sides. Every signature below is asked with a default,
because the three field rows file no `__text_signature__` at all there and answer `None` here.
"""

ROW_NEW = vars(super)["__new__"]
ROW_INIT = vars(super)["__init__"]
ROW_GET = vars(super)["__get__"]
ROW_SELF = vars(super)["__self__"]
ROW_REPR = vars(super)["__repr__"]
ROW_ATTR = vars(super)["__getattribute__"]


def told(action):
    """What a read answers, or the complaint it makes instead — most of this file is the complaints."""
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


def names(proxy):
    """The three fields a proxy reports, with the value named by identity and not printed."""
    return (
        repr(proxy),
        proxy.__thisclass__ is None and "None" or proxy.__thisclass__.__name__,
        proxy.__self_class__ is None and "None" or proxy.__self_class__.__name__,
    )


def assign(mapping, name, value):
    """Item assignment, whose refusal is a slot's answer and not a missing method."""
    mapping[name] = value


print("== the book, and how far it stands above object")
print("  vars   =%s" % (list(vars(super)),))
print("  sorted =%s" % (sorted(vars(super)),))
print("  dir    =%s  gained=%s" % (len(dir(super)), sorted(set(dir(super)) - set(dir(object)))))
print("  bases=%s  mro=%s  name=%r" % (
    [k.__name__ for k in super.__bases__], [k.__name__ for k in super.__mro__], super.__name__))

print()
print("== the one text, read by both knives and cut by neither")
print("  __text_signature__=%r  __doc__ len=%s" % (super.__text_signature__, len(super.__doc__)))
print("  first 20=%r" % (super.__doc__[:20],))
print("  dict row: type=%r is the read=%s equal=%s" % (
    type(vars(super)["__doc__"]).__name__,
    vars(super)["__doc__"] is super.__doc__,
    vars(super)["__doc__"] == super.__doc__))
print("  doc=%r" % (super.__doc__,))

print()
print("== every row of the book, in the order the book keeps them")
for row_name in vars(super):
    held = vars(super)[row_name]
    print("  %-18s %-24s qual=%-26r name=%-18r objclass=%r" % (
        row_name, type(held).__name__,
        getattr(held, "__qualname__", None),
        getattr(held, "__name__", None),
        getattr(held, "__objclass__", None)))
    print("      doc=%r" % (getattr(held, "__doc__", None),))
    print("      sig=%r" % (getattr(held, "__text_signature__", None),))

print()
print("== the value, and the three fields it reports")
proxy = super(int, 3)
print("  type=%r isinst=%s str is repr=%s" % (
    type(proxy).__name__, isinstance(proxy, super), str(proxy) == repr(proxy)))
print("  repr=%r  self=%r self_class=%r thisclass=%r" % (
    proxy, proxy.__self__, proxy.__self_class__, proxy.__thisclass__))
print("  class form=%r" % (super(int, int),))
print("  __class__ of the proxy=%r" % (proxy.__class__ is super,))

print()
print("== the half that has no value")
valueless = super(int)
print("  repr=%r" % (valueless,))
print("  names=%s" % (names(valueless),))
print("  __doc__ len=%s  of a value=%s  is object's=%s" % (
    len(valueless.__doc__), len(proxy.__doc__), proxy.__doc__ == object.__doc__))
print("  a name of its own=%r  a name nowhere=%r" % (
    told(lambda: valueless.__thisclass__), told(lambda: valueless.real)))
print("  proxy.__dict__=%r" % (told(lambda: proxy.__dict__),))

print()
print("== writing, in the two wordings")
for label, action in [
    ("value.__self__ = 1", lambda: setattr(proxy, "__self__", 3)),
    ("del value.__self__", lambda: delattr(proxy, "__self__")),
    ("value.__thisclass__ = 1", lambda: setattr(proxy, "__thisclass__", int)),
    ("del value.__self_class__", lambda: delattr(proxy, "__self_class__")),
    ("value.__doc__ = 1", lambda: setattr(proxy, "__doc__", "x")),
    ("value.volume = 1", lambda: setattr(proxy, "volume", 1)),
    ("del value.volume", lambda: delattr(proxy, "volume")),
    ("type.__self__ = 1", lambda: setattr(super, "__self__", 3)),
    ("del type.__self__", lambda: delattr(super, "__self__")),
    ("type.__doc__ = 1", lambda: setattr(super, "__doc__", "x")),
    ("type.volume = 1", lambda: setattr(super, "volume", 1)),
    ("dict['__doc__'] = 1", lambda: assign(vars(super), "__doc__", 1)),
    ("dict['volume'] = 1", lambda: assign(vars(super), "volume", 1)),
]:
    print("  %-24s -> %s" % (label, told(action)))

print()
print("== the creator, and the four ways it refuses")
for label, action in [
    ("super()", lambda: super()),
    ("super(int)", lambda: super(int)),
    ("super(int, 3)", lambda: super(int, 3)),
    ("super(int, None)", lambda: super(int, None)),
    ("super(int, 3, 4)", lambda: super(int, 3, 4)),
    ("super(1)", lambda: super(1)),
    ("super(None)", lambda: super(None)),
    ("super(int, 'x')", lambda: super(int, "x")),
    ("super(int, super)", lambda: super(int, super)),
    ("super(int, 3, x=1)", lambda: super(int, 3, x=1)),
    ("super(x=1)", lambda: super(x=1)),
]:
    print("  %-22s -> %s" % (label, told(action)))

print()
print("== the rows called without a proxy")
for label, action in [
    ("__new__()", lambda: ROW_NEW()),
    ("__new__(super)", lambda: ROW_NEW(super)),
    ("__new__(super, int, 3)", lambda: ROW_NEW(super, int, 3)),
    ("__new__(int)", lambda: ROW_NEW(int)),
    ("__repr__()", lambda: ROW_REPR()),
    ("__repr__(int, 3)", lambda: ROW_REPR(int, 3)),
    ("__getattribute__(int)", lambda: ROW_ATTR(int)),
    ("__getattribute__(int, 3, 'real')", lambda: ROW_ATTR(int, 3, "real")),
    ("__init__(int)", lambda: ROW_INIT(int)),
    ("__init__(int, 3)", lambda: ROW_INIT(int, 3)),
    ("__get__()", lambda: ROW_GET()),
    ("__get__(int)", lambda: ROW_GET(int)),
    ("__get__(int, 3)", lambda: ROW_GET(int, 3)),
    ("__self__(proxy) is not a call", lambda: ROW_SELF(proxy)),
]:
    print("  %-30s -> %s" % (label, told(action)))

print()
print("== the binding row, whose gates come in CPython's own order")
for label, action in [
    ("row(proxy)", lambda: ROW_GET(proxy)),
    ("row(proxy, 1)", lambda: ROW_GET(proxy, 1)),
    ("row(proxy, None, int)", lambda: ROW_GET(proxy, None, int)),
    ("row(proxy, 1, 2)", lambda: ROW_GET(proxy, 1, 2)),
    ("row(proxy, None, None)", lambda: ROW_GET(proxy, None, None)),
    ("row(proxy, x=1)", lambda: ROW_GET(proxy, x=1)),
    ("type of the answer", lambda: type(ROW_GET(proxy, 1)).__name__),
    ("row.__objclass__", lambda: ROW_GET.__objclass__ is super),
    ("bound self is proxy", lambda: ROW_GET.__get__(proxy, super).__self__ is proxy),
]:
    print("  %-26s -> %s" % (label, told(action)))

print()
print("== the init row refills the proxy it is handed")
holder = super(int)
print("  before=%s" % (names(holder),))
print("  two args -> %r" % (told(lambda: ROW_INIT(holder, int, 3)),))
print("  after=%s  self=%r" % (names(holder), holder.__self__))
print("  one arg -> %r" % (told(lambda: ROW_INIT(holder, str)),))
print("  after=%s  self=%r" % (names(holder), holder.__self__))
print("  three args -> %r" % (told(lambda: ROW_INIT(holder, int, 3, 4)),))
print("  still=%s" % (names(holder),))
print("  keywords -> %r" % (told(lambda: ROW_INIT(holder, int, type=1)),))
print("  still=%s" % (names(holder),))
print("  bad class -> %r" % (told(lambda: ROW_INIT(holder, 3)),))
print("  supercheck -> %r" % (told(lambda: ROW_INIT(holder, int, "x")),))
print("  still=%s" % (names(holder),))
print("  no args at module level -> %r" % (told(lambda: ROW_INIT(holder)),))


class Base:
    def sound(self):
        return "base"

    kind = "Base"


class Middle(Base):
    def middle(self):
        return "middle"


class Loud(Middle, Base):
    def fromAbove(self):
        proxy = super(Middle, self)
        before = names(proxy)
        answered = ROW_INIT(proxy)
        return (before, answered, names(proxy), proxy.sound(), type(proxy.middle).__name__)

    def fromLeaf(self):
        proxy = super(Loud, self)
        before = names(proxy)
        ROW_INIT(proxy, Middle)
        return (before, names(proxy))

    def valueless(self):
        proxy = super(Base)
        before = names(proxy)
        ROW_INIT(proxy)
        return (before, names(proxy))


leaf = Loud()
print()
print("== the frame is the third way to a class")
print("  no args: %s" % (leaf.fromAbove(),))
print("  one arg: %s" % (leaf.fromLeaf(),))
print("  valueless in a frame: %s" % (Loud().valueless(),))

print()
print("== the walk still answers, and the rows sit behind it")
print("  walk=%r  through type=%r  valueless=%r" % (
    super(Middle, leaf).sound(),
    super(Middle, Middle).kind,
    told(lambda: super(Base).sound)))
print("  past object=%r  name nowhere=%r" % (
    told(lambda: super(Base, leaf).sound), told(lambda: super(Middle, leaf).volume)))
print("  a built-in table is part of an order=%r" % (told(lambda: super(bool, True).bit_length()),))
print("  the walk's row binds the value=%s  the book's binds the proxy=%s" % (
    proxy.__reduce__.__self__ is proxy.__self__,
    ROW_REPR.__get__(proxy, super).__self__ is proxy))
print("  type(super.__dict__)=%r" % (type(vars(super)).__name__,))
print("done")
