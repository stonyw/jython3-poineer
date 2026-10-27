"""The ten rows ``OSError`` files, and the five fields they hold out to a value.

``len(OSError.__dict__)`` is ten and ``len(dir(OSError))`` is thirty-eight — five more than ``BaseException``
and ``KeyError`` each report, and those five are exactly the fields: ``errno``, ``strerror``, ``filename``,
``filename2`` and ``characters_written``. ``OSError`` is the only one of the three that keeps anything beside
its arguments, so it is the only one that files a ``member_descriptor`` and a ``getset_descriptor`` and the
only one whose extra rows are its own rather than another class's. ``dir(OSError) - dir(BaseException)`` is
the five fields and nothing else.

The ten are written down in the same five registers as ``BaseException``'s and the same rule explains the
shape of the table: ``__new__`` first as a ``builtin_function_or_method``, then the two ``wrapper_descriptor``
rows ``__str__`` and ``__init__``, then ``__reduce__`` as a ``method_descriptor``, then the four members in
the order ``OSError_members`` lists them, then the one getset, then ``__doc__`` last. ``OSError`` runs a
``tp_str`` of its own and keeps ``BaseException``'s ``tp_repr``, so ``add_operators`` files the one and not
the other: ``OSError.__str__ is BaseException.__str__`` is false while the same read of ``__repr__`` is true.
Eight of the ten carry a sentence, and the four members are the only ones that say what they hold rather than
what they do — ``POSIX exception code``, ``exception strerror``, ``exception filename``, ``second exception
filename`` — while ``characters_written`` says nothing, because ``OSError_getset`` writes it with a null
``doc``.

Four ``_Py_T_OBJECT`` members and one getset is a difference a program can feel. A member that was never
filled reads as ``None``, all four of them on a bare ``OSError()``, while an unfilled ``characters_written``
refuses by name — the only such complaint of the five. A write is not a delete: ``e.filename2 = None`` writes
the object and puts ``-> None`` into ``str(e)``, while ``del e.filename2`` empties the field and the text
says nothing of it. Every one of the four takes its delete twice over and the second one is silent, because
writing the null pointer is an answer rather than a refusal; the getset deletes as willingly, and it is the
read after the delete that refuses.

``__new__`` and ``__init__`` share one decision, ``oserror_use_init``: a class that declares an ``__init__``
and no ``__new__`` is left empty by the creator — no arguments, no error number, no promotion to a narrower
class — and is filled only if that ``__init__`` asks for it, which is why ``Skip(1, "a", "f")`` has no
arguments at all while ``Def``, whose ``__init__`` calls ``super().__init__``, has the whole shape, and
``Both``, which writes ``__new__`` as well, parses in the creator it reached. A class that declares neither
is made whole by ``OSError_new`` and then ``OSError_init`` returns without touching it, so the five-argument
call of ``os init of neither`` answers ``None`` and leaves the pair the value was built with; ``OSError``
itself is refused the same way, and its file name is still there afterwards. The decision reaches the
keyword complaint too: it names the method for a class that writes an ``__init__`` — ``Skip.__init__() got
an unexpected keyword argument`` — and the class for one that does not — ``Filed() takes no keyword
arguments``.

What this case does not print is the repr of ``__new__``, which names an address, the size of an ``OSError``
as a number — ``__basicsize__`` and ``__flags__`` are answered here as zero — the error numbers of any other
platform, which decide the class a number promotes to, the other classes of the family that keep fields of
their own and are not filed here — ``SyntaxError`` with its nine getsets, ``UnicodeEncodeError`` with its
five members, ``ImportError`` with its four — and ``PyNumber_Check`` over a value with an ``__index__`` of
its own, which this runtime reads only for the four numeric builtins.
"""


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def line(name):
    """One row of the table: its kind, its qualified name and what the kind carries."""
    value = OWNED[name]
    return "%-22s %-24s %-24s set=%-5s get=%-5s doc=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__set__"),
        hasattr(value, "__get__"),
        repr(getattr(value, "__doc__", "-")),
    )


class Def(OSError):
    """A class that parses for itself, by asking the base to do it."""

    def __init__(self, *arguments):
        super().__init__(*arguments)


class Skip(OSError):
    """A class that takes the arguments and keeps nothing."""

    def __init__(self, *arguments):
        pass


class Both(OSError):
    """A class that writes the creator as well as the initialiser."""

    def __new__(cls, *arguments):
        return OSError.__new__(cls, *arguments)

    def __init__(self, *arguments):
        pass


class Filed(OSError):
    """A class that files one of the five names for itself."""

    errno = 42


OWNED = OSError.__dict__
ORDER = list(OWNED)
FIELDS = ("errno", "strerror", "filename", "filename2", "characters_written")
MEMBERS = ("errno", "strerror", "filename", "filename2")

DOCS = [
    "__new__",
    "__str__",
    "__init__",
    "__reduce__",
    "errno",
    "strerror",
    "filename",
    "filename2",
    "characters_written",
    "__doc__",
]


def kinds(name):
    return type(OWNED[name]).__name__


def of(kind):
    """Every row filed as one kind."""
    return [name for name in ORDER if kinds(name) == kind]


def offset(first, second):
    return ORDER.index(second) - ORDER.index(first)


print("== the shape of the table ==")
show("len", lambda: len(OWNED))
show("dir", lambda: len(dir(OSError)))
show("inherited count", lambda: len(dir(OSError)) - len(OWNED))
show("the ten in order", lambda: ORDER == DOCS)
show("new is first", lambda: ORDER[0])
show("doc is last", lambda: ORDER[-1])
show("every row follows by one", lambda: [offset(ORDER[i], ORDER[i + 1]) for i in range(len(ORDER) - 1)])
show("mro", lambda: [c.__name__ for c in OSError.__mro__])
show("doc", lambda: OSError.__doc__)
show("the five that make the count", lambda: sorted(set(dir(OSError)) - set(dir(BaseException))))
show("the fields are the only addition", lambda: sorted(set(dir(OSError)) - set(dir(BaseException))) == sorted(FIELDS))
show("no repr row", lambda: "__repr__" in OWNED)
show("and it is the base one", lambda: OSError.__repr__ is BaseException.__repr__)
show("str is the base one", lambda: OSError.__str__ is BaseException.__str__)
show("aliases are one type", lambda: [IOError is OSError, EnvironmentError is OSError])
show("and name it", lambda: [IOError.__name__, EnvironmentError.__name__])

print("== the table by kind ==")
show("new", lambda: of("builtin_function_or_method"))
show("wrappers", lambda: of("wrapper_descriptor"))
show("methods", lambda: of("method_descriptor"))
show("members", lambda: of("member_descriptor"))
show("getsets", lambda: of("getset_descriptor"))
show("str rows", lambda: of("str"))
show("the five kinds", lambda: sorted({kinds(name) for name in ORDER}))
show("rows that carry a sentence", lambda: [n for n in ORDER if getattr(OWNED[n], "__doc__", None) is not None])
show("the data half", lambda: [n for n in ORDER if hasattr(OWNED[n], "__set__")])
show("only the new carries a self", lambda: [n for n in ORDER if hasattr(OWNED[n], "__self__")])
show("every objclass is oserror", lambda: sorted({OWNED[n].__objclass__ is OSError
                                                  for n in ORDER if hasattr(OWNED[n], "__objclass__")}))
show("every qualname is oserror's", lambda: sorted({OWNED[n].__qualname__.split(".")[0]
                                                    for n in ORDER if hasattr(OWNED[n], "__qualname__")}))
show("the narrower classes file one row", lambda: [list(k.__dict__) for k in (FileNotFoundError, PermissionError, BlockingIOError)])
show("and it is a sentence", lambda: [kinds(n) for n in ORDER if kinds(n) == "str"])

print("== all ten rows, as the table files them ==")
for name in ORDER:
    print(line(name))

print("== what the four members say ==")
for name in MEMBERS:
    show("%s repr" % name, lambda n=name: repr(OWNED[n]))
    show("%s objclass" % name, lambda n=name: OWNED[n].__objclass__.__name__)
show("a member read through the class", lambda: OSError.errno is OWNED["errno"])
show("a member read off a value", lambda: OSError.__dict__["errno"].__get__(OSError(1, "a")))
show("a member read off the base", lambda: OSError.__dict__["errno"].__get__(BaseException("b")))
show("a member read off nothing", lambda: OWNED["errno"].__get__(None, OSError))
show("a getset read off nothing", lambda: OWNED["characters_written"].__get__(None, OSError))
show("equal members", lambda: OSError.errno == OWNED["errno"])
show("a member of a subclass field", lambda: type(Filed.__dict__["errno"]).__name__)

print("== the text a wrapper runs ==")
show("base str of an os error", lambda: BaseException.__str__(OSError(1, "a")))
show("os str of the same", lambda: OSError.__str__(OSError(1, "a")))
show("str of the same", lambda: str(OSError(1, "a")))
show("os str of a value error", lambda: OSError.__str__(ValueError("no")))
show("base reduce of an os error", lambda: BaseException.__reduce__(OSError(1, "a")))
show("os reduce of a value error", lambda: OSError.__reduce__(ValueError("no")))
show("os init of a value error", lambda: OSError.__init__(ValueError("no")))
show("os str unbound", lambda: OSError.__str__())
show("the display a subclass inherits", lambda: str(Def(1, "a", "f")))

print("== the reduction that puts the file back ==")
plain = OSError(9999, "x")
show("no file, two items", lambda: plain.__reduce__())
show("a file makes three", lambda: OSError(9999, "x", "f").__reduce__())
show("the fifth from a write", lambda: (lambda e: (setattr(e, "filename2", "g"), e.__reduce__()))(OSError(9999, "x", "f"))[1])
show("None as a file counts", lambda: (lambda e: (setattr(e, "filename", None), e.__reduce__(), str(e)))(OSError(9999, "x"))[1:])
show("one argument keeps it", lambda: OSError("only").__reduce__())
show("six keeps them all", lambda: OSError(1, 2, 3, 4, 5, 6).__reduce__())
show("a count keeps three", lambda: BlockingIOError(11, "m", 3).__reduce__())
show("a promoted class reports itself", lambda: FileNotFoundError(2, "noent", "p").__reduce__())
show("called through the class", lambda: OSError.__reduce__(OSError(9999, "x")))
show("unbound", lambda: OSError.__reduce__())
show("a keyword", lambda: OSError(1, "a").__reduce__(flag=1))
show("an argument", lambda: OSError(1, "a").__reduce__(1))

print("== the fields of a value, and their deletes ==")
filled = OSError(1, "a", "f", "w", "g")
for name in MEMBERS:
    show("%s before" % name, lambda n=name: getattr(filled, n))
    show("%s set" % name, lambda n=name: (lambda e: (setattr(e, n, 7), getattr(e, n), str(e)))(OSError(1, "a", "f", "w", "g")))
    show("%s none" % name, lambda n=name: (lambda e: (setattr(e, n, None), getattr(e, n), str(e)))(OSError(1, "a", "f", "w", "g")))
    show("%s deleted" % name, lambda n=name: (lambda e: (delattr(e, n), getattr(e, n), str(e)))(OSError(1, "a", "f", "w", "g")))
    show("%s deleted twice" % name, lambda n=name: (lambda e: (delattr(e, n), delattr(e, n), "silent"))(OSError(1, "a", "f", "w", "g")))
show("count of an unfilled", lambda: OSError(1, "a").characters_written)
show("count of a filled", lambda: BlockingIOError(11, "m", 3).characters_written)
show("count of a text", lambda: (lambda e: (setattr(e, "characters_written", "x"), e.characters_written))(OSError(1, "a")))
show("count deleted", lambda: (lambda e: (setattr(e, "characters_written", 3), delattr(e, "characters_written"), e.characters_written))(OSError(1, "a")))
show("count of a file", lambda: BlockingIOError(11, "m", "f").filename)
show("the fields of an unfilled value", lambda: [getattr(OSError(), n) for n in MEMBERS])
show("and the count refuses", lambda: OSError().characters_written)

print("== the one decision the creator and the initialiser share ==")
show("def", lambda: (lambda e: (e.args, e.errno, e.filename, str(e)))(Def(1, "a", "f")))
show("skip", lambda: (lambda e: (e.args, e.errno, e.filename, str(e)))(Skip(1, "a", "f")))
show("skip is not promoted", lambda: type(Skip(2, "m")).__name__)
show("def is not promoted", lambda: type(Def(2, "m")).__name__)
show("both", lambda: (lambda e: (e.args, e.errno, e.filename, str(e)))(Both(1, "a", "f")))
show("neither", lambda: (lambda e: (e.args, e.errno, e.filename, str(e)))(Filed(1, "a", "f")))
show("skip calls the base", lambda: (lambda e: (e.__init__(3, "three", "g"), e.args, e.errno, e.filename))(Skip()))
show("def empties", lambda: (lambda e: (e.__init__(), e.args, e.errno, e.filename))(Def(1, "a", "f")))
show("os init of a def", lambda: (lambda e: (OSError.__init__(e, 5, "five"), e.args, e.strerror))(Def(1, "a")))
show("os init of a skip", lambda: (lambda e: (OSError.__init__(e, 5, "five"), e.args, e.strerror))(Skip()))
show("os init of neither", lambda: (lambda e: (OSError.__init__(e, 5, "five"), e.args, e.strerror))(Filed(1, "a")))
show("os init of oserror", lambda: (lambda e: (OSError.__init__(e, 5, "five"), e.args, e.strerror))(OSError(1, "a")))
show("init of neither keeps it", lambda: (lambda e: (e.__init__(5, "five"), e.args, e.strerror))(Filed(1, "a")))
show("init keeps the file", lambda: (lambda e: (OSError.__init__(e, 5, "five"), e.filename))(OSError(1, "a", "f")))
show("skip refuses a keyword", lambda: Skip(1, "a", flag=2))
show("and names the method", lambda: Def(1, "a", flag=2))
show("neither names the class", lambda: Filed(1, "a", flag=2))
show("os new of a skip", lambda: (lambda v: (v.args, v.errno))(OSError.__new__(Skip, 1, "a", "f")))
show("os new of neither", lambda: (lambda v: (v.args, v.errno))(OSError.__new__(Filed, 1, "a", "f")))
show("os new of oserror", lambda: OSError.__new__(OSError, 1, "a", "f"))
show("and the class it made", lambda: type(OSError.__new__(OSError, 1, "a", "f")).__name__)

print("== a class that files one of the five ==")
shown = Filed(1, "a")
show("the class row reads", lambda: Filed.errno)
show("and the value reads", lambda: shown.errno)
show("the field the call filled", lambda: OSError.errno.__get__(shown))
show("a write goes to the dictionary", lambda: (lambda e: (setattr(e, "errno", 7), e.__dict__, e.errno, str(e)))(Filed(1, "a")))
show("and its delete goes back to the row", lambda: (lambda e: (setattr(e, "errno", 7), delattr(e, "errno"), e.errno))(Filed(1, "a")))
show("a delete of what it never held", lambda: delattr(Filed(1, "a"), "errno"))
show("the text still reads the field", lambda: str(Filed(1, "a", "f")))
show("the display it inherits is the base's", lambda: Filed.__str__ is OSError.__str__)

print("== the sentence each filed name carries ==")
for name in ORDER:
    print("%-22s %r" % (name, getattr(OWNED[name], "__doc__", "-")))
