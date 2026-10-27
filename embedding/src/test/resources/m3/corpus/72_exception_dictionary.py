"""The fifteen rows ``BaseException`` files, and the two ``KeyError`` files beside them.

``len(BaseException.__dict__)`` is fifteen and ``len(dir(BaseException))`` is thirty-three; the eighteen the
second has and the first does not are all ``object``'s, and none of the names ``BaseException`` files is in
that borrowed half. ``KeyError`` files two rows, ``__str__`` and ``__doc__``, and its ``dir`` is the same
thirty-three: a ``KeyError`` adds a display and nothing else, so ``set(dir(KeyError)) - set(dir(BaseException))``
is empty. The fifteen are written down in the order CPython's ``type_ready_fill_dict`` registers them —
``__new__`` first, then the three ``wrapper_descriptor`` rows ``__repr__``, ``__str__`` and ``__init__``, then
the four ``method_descriptor`` rows, then the one ``member_descriptor`` and the five ``getset_descriptor``
rows, then ``__doc__`` last — and nothing interleaves, so every row sits exactly one place after the row the
table wrote before it.

Two kinds differ in a way a program can see. ``__suppress_context__`` and the five getsets are <em>data</em>
descriptors, with a ``__set__``, and the three wrappers, the four methods and ``__new__`` are not. The write
of a data descriptor goes to the field rather than to the value's own dictionary — ``e.args = (3, 4)``
changes what ``e.args`` and ``str(e)`` read and leaves ``vars(e)`` empty — and a delete of one refuses in the
words of the field rather than of a missing name. A class that files one of those names for itself still gets
the last word on a read, because the lookup takes the first row in the order and a plain string is not a
descriptor: ``Shadow.args`` reads the string the class wrote while the getset the base files answers the field
when it is called outright. ``__new__`` is the only row that carries a ``__self__``, and the only one whose
``__objclass__`` is missing, because it is reached as a plain function of the class rather than as a
descriptor of it.

A ``wrapper_descriptor`` calls the function of the class that filed it, not the display of the value it is
handed: ``BaseException.__str__(OSError(1, "a"))`` reads ``"(1, 'a')"`` over the arguments where ``str`` of
the same value reads ``[Errno 1] a``, and the same holds of ``repr``. It is the reason ``KeyError`` files a
row at all — the one name it writes for itself is the display that puts a single argument the way a ``repr``
does, which is also the display that refuses a value of any other class in the words of a descriptor.

``__reduce__`` and ``__setstate__`` are the pair that writes a value back. The first answers with the class
and the arguments, and adds the dictionary only when the value has one: a write makes it three items, and a
read of ``e.args`` leaves it two, because a lookup never makes the dictionary. The second takes exactly one
argument and writes each key as an attribute, and has one sentence each for a state that is not a dictionary
and a key that is not text.

What this case does not print is the size of an exception as a number — ``__basicsize__`` and ``__flags__``
are answered here as zero, because a runtime value has no C layout to report — the repr of ``__new__``, which
names an address, and the fields of the ``OSError`` family, which are the case beside this one.
"""


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


class Displayed(BaseException):
    """A class that files the display the wrappers would have run."""

    def __str__(self):
        return "shown"


class Shadow(BaseException):
    """A class that tries to file a name the getsets already own."""

    args = "mine"


OWNED = BaseException.__dict__
KEYS = KeyError.__dict__
ORDER = list(OWNED)

BE = [
    "__new__",
    "__repr__",
    "__str__",
    "__init__",
    "__reduce__",
    "__setstate__",
    "with_traceback",
    "add_note",
    "__suppress_context__",
    "__dict__",
    "args",
    "__traceback__",
    "__context__",
    "__cause__",
    "__doc__",
]


def kinds(table, name):
    """The kind a row is filed as, which is what decides how it can be called."""
    return type(table[name]).__name__


def of(table, kind):
    """Every row of one table filed as one kind."""
    return [name for name in table if kinds(table, name) == kind]


def offset(first, second):
    """How far apart two rows are, which is the order the C file wrote them in."""
    return ORDER.index(second) - ORDER.index(first)


def line(table, name):
    """One row of the table: its kind, its qualified name and what the kind carries."""
    value = table[name]
    return "%-22s %-24s %-28s set=%-5s self=%-5s objclass=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__set__"),
        hasattr(value, "__self__"),
        hasattr(value, "__objclass__"),
    )


print("== the shape of the two tables ==")
show("len", lambda: len(OWNED))
show("dir", lambda: len(dir(BaseException)))
show("inherited count", lambda: len(dir(BaseException)) - len(OWNED))
show("the inherited half", lambda: sorted(set(dir(BaseException)) - set(OWNED)))
show("mro", lambda: [c.__name__ for c in BaseException.__mro__])
show("name", lambda: BaseException.__name__)
show("qualname", lambda: BaseException.__qualname__)
show("module", lambda: BaseException.__module__)
show("proxy kind", lambda: type(OWNED).__name__)
show("doc", lambda: BaseException.__doc__)
show("new is first", lambda: ORDER[0])
show("doc is last", lambda: ORDER[-1])
show("the fifteen in order", lambda: ORDER == BE)
show("every row follows by one", lambda: [offset(ORDER[i], ORDER[i + 1]) for i in range(len(ORDER) - 1)])
show("keys len", lambda: len(KEYS))
show("keys rows", lambda: list(KEYS))
show("keys dir", lambda: len(dir(KeyError)))
show("keys mro", lambda: [c.__name__ for c in KeyError.__mro__])
show("keys doc", lambda: KeyError.__doc__)
show("keys borrow every other name", lambda: sorted(set(dir(KeyError)) - set(dir(BaseException))))
show("keys str is base str", lambda: KeyError.__str__ is BaseException.__str__)
show("keys new is base new", lambda: KeyError.__new__ is BaseException.__new__)
show("a leaf of its own", lambda: list(ValueError.__dict__))
show("a leaf with a doc", lambda: list(FileNotFoundError.__dict__))

print("== the tables by kind ==")
show("new", lambda: of(OWNED, "builtin_function_or_method"))
show("wrappers", lambda: of(OWNED, "wrapper_descriptor"))
show("methods", lambda: of(OWNED, "method_descriptor"))
show("members", lambda: of(OWNED, "member_descriptor"))
show("getsets", lambda: of(OWNED, "getset_descriptor"))
show("str rows", lambda: of(OWNED, "str"))
show("the five kinds", lambda: sorted({kinds(OWNED, name) for name in ORDER}))
show("only the doc has no qualname", lambda: [n for n in ORDER if not hasattr(OWNED[n], "__qualname__")])
show("only the new carries a self", lambda: [n for n in ORDER if hasattr(OWNED[n], "__self__")])
show("only the new has no objclass", lambda: [n for n in ORDER if not hasattr(OWNED[n], "__objclass__")])
show("every objclass is base", lambda: sorted({OWNED[n].__objclass__ is BaseException
                                               for n in ORDER if hasattr(OWNED[n], "__objclass__")}))
show("every qualname is base's", lambda: sorted({OWNED[n].__qualname__.split(".")[0]
                                                 for n in ORDER if hasattr(OWNED[n], "__qualname__")}))
show("rows that say nothing", lambda: [n for n in ORDER if getattr(OWNED[n], "__doc__", "x") is None])
show("the data half", lambda: [n for n in ORDER if hasattr(OWNED[n], "__set__")])
show("the non-data half", lambda: [n for n in ORDER if not hasattr(OWNED[n], "__set__")])
show("new is the only row without get", lambda: [n for n in ORDER if not hasattr(OWNED[n], "__get__")])

print("== all fifteen rows, as the table files them ==")
for name in ORDER:
    print(line(OWNED, name))

print("== the two rows KeyError files ==")
for name in KEYS:
    print(line(KEYS, name))

print("== what the kinds answer when read back ==")
show("a wrapper reads as a wrapper", lambda: repr(OWNED["__str__"]))
show("a method reads by name", lambda: repr(OWNED["__reduce__"]))
show("a member reads by name", lambda: repr(OWNED["__suppress_context__"]))
show("a getset reads as an attribute", lambda: repr(OWNED["args"]))
show("reading a method off the class", lambda: BaseException.add_note is OWNED["add_note"])
show("reading a getset off the class", lambda: BaseException.args is OWNED["args"])
show("reading a wrapper off the class", lambda: BaseException.__str__ is OWNED["__str__"])
show("a method of a value carries it", lambda: BaseException("x").add_note.__self__ is not None)
show("a member of a value reads", lambda: OWNED["__suppress_context__"].__get__(BaseException("x")))
show("a member read through the class", lambda: BaseException.__suppress_context__ is OWNED["__suppress_context__"])
show("a member read off nothing", lambda: OWNED["__suppress_context__"].__get__(None, BaseException))
show("equal members", lambda: BaseException.__suppress_context__ == OWNED["__suppress_context__"])
show("a wrapper has no write half", lambda: not hasattr(OWNED["__str__"], "__set__"))

print("== the display a wrapper runs ==")
show("base str of an os error", lambda: BaseException.__str__(OSError(1, "a")))
show("str of the same value", lambda: str(OSError(1, "a")))
show("base repr of an os error", lambda: BaseException.__repr__(OSError(1, "a", "f")))
show("repr of the same value", lambda: repr(OSError(1, "a", "f")))
show("base str of a key error", lambda: BaseException.__str__(KeyError("k")))
show("str of the same value", lambda: str(KeyError("k")))
show("keys str of a key error", lambda: KeyError.__str__(KeyError("k")))
show("keys str of a base error", lambda: KeyError.__str__(BaseException("b")))
show("base str of a value", lambda: BaseException.__str__(ValueError(1, 2)))
show("base repr of a value", lambda: BaseException.__repr__(ValueError(1, 2)))
show("os str of an os error", lambda: OSError.__str__(OSError(1, "a", "f")))
show("os str of a value error", lambda: OSError.__str__(ValueError("no")))
show("str on a string", lambda: BaseException.__str__("not an exception"))
show("repr on a number", lambda: BaseException.__repr__(3))
show("keys str of one list", lambda: str(KeyError([1, 2])))
show("keys str of two", lambda: str(KeyError(1, 2)))
show("keys str of none", lambda: str(KeyError()))
show("a class that writes its own display", lambda: str(Displayed("x")))
show("and the wrapper still runs base", lambda: BaseException.__str__(Displayed("x")))
show("the display the class filed wins", lambda: Displayed.__str__ is not BaseException.__str__)

print("== the reduction a value hands back ==")
written = BaseException("a")
show("two items while nothing was written", lambda: written.__reduce__())
show("the class it reports", lambda: written.__reduce__()[0] is BaseException)
show("of a value", lambda: ValueError(1, 2).__reduce__())
show("of a key error", lambda: KeyError("k").__reduce__())
show("a write makes it three", lambda: (setattr(written, "z", 1), written.__reduce__())[1])
show("and a read does not", lambda: (lambda e: (e.args, e.__reduce__()))(BaseException("a")))
show("vars makes it too", lambda: (lambda e: (vars(e), e.__reduce__()))(BaseException("a")))
show("a note makes it too", lambda: (lambda e: (e.add_note("n"), e.__reduce__()))(BaseException("a"))[1])
show("called through the class", lambda: BaseException.__reduce__(BaseException("a")))
show("unbound", lambda: BaseException.__reduce__())
show("a keyword", lambda: BaseException("a").__reduce__(flag=1))
show("an argument", lambda: BaseException("a").__reduce__(1))
show("a wrong receiver", lambda: BaseException.__reduce__("x"))

print("== the state written back ==")
kept = BaseException("a")
show("a dictionary", lambda: (kept.__setstate__({"k": 2}), kept.__dict__))
show("none leaves it alone", lambda: (lambda e: (e.__setstate__(None), e.__dict__))(BaseException("a")))
show("a number", lambda: BaseException("a").__setstate__(5))
show("a bad key", lambda: BaseException("a").__setstate__({5: 1}))
show("the class as a key", lambda: BaseException("a").__setstate__({"__class__": 5}))
show("args as a number", lambda: BaseException("a").__setstate__({"args": 5}))
show("args as a tuple", lambda: (lambda e: (e.__setstate__({"args": (1, 2)}), e.args, str(e)))(BaseException("a")))
show("no argument", lambda: BaseException("a").__setstate__())
show("two arguments", lambda: BaseException("a").__setstate__({}, {}))
show("a keyword", lambda: BaseException("a").__setstate__(flag=1))
show("unbound", lambda: BaseException.__setstate__())
show("called through the class", lambda: (lambda e: (BaseException.__setstate__(e, {"z": 1}), e.z))(BaseException("a")))

print("== the fields, as data descriptors ==")
plain = BaseException("a")
show("suppress reads", lambda: plain.__suppress_context__)
show("args reads", lambda: plain.args)
show("dict starts empty", lambda: plain.__dict__)
show("traceback is none", lambda: plain.__traceback__)
show("context is none", lambda: plain.__context__)
show("cause is none", lambda: plain.__cause__)
show("a write to args", lambda: (lambda e: (setattr(e, "args", (3, 4)), e.args, str(e), e.__dict__))(BaseException("a")))
show("a write to suppress", lambda: (lambda e: (setattr(e, "__suppress_context__", True), e.__suppress_context__))(BaseException("a")))
show("a write to cause", lambda: setattr(BaseException("a"), "__cause__", 1))
show("a write to traceback", lambda: setattr(BaseException("a"), "__traceback__", 1))
show("a delete of args", lambda: delattr(BaseException("a"), "args"))
show("a delete of dict", lambda: delattr(BaseException("a"), "__dict__"))
show("a delete of suppress", lambda: delattr(BaseException("a"), "__suppress_context__"))
show("a class that files the name wins the read", lambda: Shadow.args)
shadow = Shadow("x")
show("and the write goes to the dictionary", lambda: (setattr(shadow, "args", (1, 2)), shadow.__dict__, shadow.args))
show("the base getset still reads through it", lambda: BaseException.args.__get__(Shadow("y")))
show("but it can hide a wrapper", lambda: Displayed.__str__ is not BaseException.__str__)

print("== the sentence each filed name carries ==")
for name in ORDER:
    print("%-22s %r" % (name, getattr(OWNED[name], "__doc__", "-")))
for name in KEYS:
    print("%-22s %r" % ("KeyError." + name, getattr(KEYS[name], "__doc__", "-")))
