"""t-strings (PEP 750): the two opcodes, the two types they build, and the text they keep.

`BUILD_TEMPLATE` and `BUILD_INTERPOLATION` are emitted by the front end, so what is measured here is the
interpreter's half of the pair: the tuple of strings, the tuple of interpolations, and the four fields each
interpolation carries. The module is reached through the dotted name alone, because this runtime's M3 register
answers `string.templatelib` and has no `string` package above it to open first.
"""

from string.templatelib import Interpolation, Template, _template_unpickle, convert


def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "->", type(e).__name__, "|", e)


print("== the literal ==")
plain = t"hello"
print(repr(plain), len(plain.strings), len(plain.interpolations), plain.values)
name, n = "world", 7
one = t"hi {name}!"
print(repr(one))
print(one.strings, one.values)
print(repr(one.interpolations[0]))
two = t"{name}{n}"
print(repr(two), two.strings, two.values)
print(repr(t"{name}{name}"), repr(t"{n}a{name}"))
empty = Template()
print(repr(empty), list(empty), empty.values)
print(repr(t""), repr(t"{{}} {n}"), t"{{}} {n}".values)

print("== what a conversion keeps and what it changes ==")
for piece in [t"{n}", t"{n!s}", t"{n!r}", t"{n!a}", t"{name!a}", t"{name!r}"]:
    print(repr(piece), "|", piece.values)
for piece in [t"{n:>5}", t"{n!r:>5}", t"{n=}", t"{n:0{n}d}"]:
    print(repr(piece))

print("== iteration skips the empty halves ==")
mixed = t"a{n}b"
print(list(mixed))
print(list(t"{n}{name}"), list(t"{n}"), list(t""))
it = iter(mixed)
print(type(it).__name__, iter(it) is it)
print(next(it), "|", next(it), "|", next(it))
try:
    next(it)
except StopIteration:
    print("stop")

print("== joining two templates ==")
print(repr(t"a{n}" + t"b{name}"))
print(repr(t"" + t""))
print(repr(Template() + Template("x")))
print(repr(Template("a", "b") + Template("c")))
print(repr(t"x" + t"{n}"), repr(t"{n}" + t"y"))

print("== identity, hash and equality ==")
a, b = t"a", t"a"
print(a == a, a == b, a != b, a is b, isinstance(hash(a), int), hash(a) == hash(a), len({a, b}))
print(Interpolation(1) == Interpolation(1), len({Interpolation(1), Interpolation(1)}))

print("== display surfaces ==")
show("format empty", lambda: format(a, ""))
show("format spec", lambda: format(a, "x"))
show("str", lambda: str(a))
show("bool", lambda: bool(Template()))
show("type", lambda: type(a) is Template)

print("== the words a template refuses ==")
show("Template(1)", lambda: Template(1))
show("Template(foo=1)", lambda: Template(foo=1))
show("+ str", lambda: a + "b")
show("+ int", lambda: a + 1)
show("+ None", lambda: a + None)
show("str +", lambda: "b" + a)

print("== the shape Template's own arguments make ==")
print(repr(Template()))
print(repr(Template("a")))
print(repr(Template("a", "b")))
print(repr(Template(Interpolation(1, "x"))))
print(repr(Template("a", Interpolation(1, "x"), "b")))
print(repr(Template(Interpolation(1, "x"), Interpolation(2, "y"))))
print(repr(Template("a", Interpolation(1, "x"), "b", Interpolation(2, "y"), "c")))
print(repr(Template("a", "b", Interpolation(1, "x"), "c", "d").strings))

print("== the four fields of an interpolation ==")
i = Interpolation(1, "x", "r", ">5")
print(repr(i), i.value, i.expression, i.conversion, i.format_spec)
print(repr(Interpolation(1)), repr(Interpolation(1, "x")), repr(Interpolation(value=1)))
print(repr(t"{n!r:>5}"))

print("== the words Interpolation refuses ==")
for label, fn in [
    ("none", lambda: Interpolation()),
    ("bad conversion type", lambda: Interpolation(1, "x", 2)),
    ("bad conversion word", lambda: Interpolation(1, "x", "z")),
    ("bad format type", lambda: Interpolation(1, "x", "r", 3)),
    ("bad expression", lambda: Interpolation(1, None)),
    ("bad keyword", lambda: Interpolation(1, foo=2)),
]:
    show(label, fn)

print("== convert ==")
for label, fn in [
    ("none", lambda: convert()),
    ("one", lambda: convert(1)),
    ("keyword only", lambda: convert(conversion="s")),
    ("keyword conversion", lambda: convert(1, conversion="s")),
    ("twice", lambda: convert(1, "s", conversion="r")),
    ("bad specifier", lambda: convert(1, 2)),
    ("bad keyword", lambda: convert(1, "s", foo=1)),
    ("obj as keyword", lambda: convert(obj=1, conversion="s")),
    ("too many", lambda: convert(1, "s", 2)),
    ("s", lambda: convert(1, "s")),
    ("r", lambda: convert(1, "r")),
    ("a", lambda: convert("\u00e9", "a")),
    ("none conversion", lambda: convert(1, None)),
]:
    show(label, fn)

print("== _template_unpickle ==")
for label, fn in [
    ("none", lambda: _template_unpickle()),
    ("three", lambda: _template_unpickle(1, 2, 3)),
    ("text only", lambda: _template_unpickle("a", ())),
    ("two texts", lambda: _template_unpickle(("a", "b"), ())),
    ("empty then one", lambda: _template_unpickle(("", ""), (1,))),
    ("two strings", lambda: _template_unpickle("ab", "cd")),
]:
    show(label, fn)

print("== __reduce__, and the pair it answers ==")
t3 = t"a{n}b"
print(t3.__reduce__() == (_template_unpickle, (t3.strings, t3.interpolations)))
print(repr(_template_unpickle(*t3.__reduce__()[1])))
print(i.__reduce__() == (Interpolation, (1, "x", "r", ">5")))

print("== the two books, row by row ==")
for kind in (Template, Interpolation):
    print("--", kind.__name__, "|", kind.__qualname__, "|", kind.__module__, "|", repr(kind))
    for row_name in vars(kind):
        row = vars(kind)[row_name]
        if isinstance(row, (str, tuple)):
            print(row_name, "|", type(row).__name__, "|", repr(row))
        else:
            print(row_name, "|", type(row).__name__, "|", repr(getattr(row, "__doc__", None)), "|",
                  repr(getattr(row, "__text_signature__", "gone")))

print("== the rows are reads, not fields ==")


def refuses(label, fn):
    try:
        fn()
        print(label, "-> accepted")
    except Exception as e:
        print(label, "->", type(e).__name__, "|", e)


refuses("set strings", lambda: setattr(a, "strings", ("x",)))
refuses("del strings", lambda: delattr(a, "strings"))
refuses("set values", lambda: setattr(a, "values", ()))
refuses("del values", lambda: delattr(a, "values"))
refuses("set value", lambda: setattr(i, "value", 2))
refuses("del format_spec", lambda: delattr(i, "format_spec"))
refuses("set new attribute", lambda: setattr(a, "zz", 1))
refuses("read __dict__", lambda: a.__dict__)
show("no __dict__", lambda: hasattr(a, "__dict__"))
