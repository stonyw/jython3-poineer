"""The five fields an ``OSError`` carries, and the error number that chooses its class.

``OSError`` is the one built-in exception whose value holds anything beside its arguments: CPython stores
``myerrno``, ``strerror``, ``filename``, ``filename2`` and the C ``ssize_t`` behind ``characters_written``,
and publishes four of them as ``PyMemberDef``s of type ``_Py_T_OBJECT`` plus one getset. Two of that is
worth pinning down. A member that was never filled reads as ``None`` but is not ``None``, and the format
``OSError_str`` picks tests the field for being there — so ``e.filename2 = None`` writes the object and
puts ``-> None`` into the text, while ``del e.filename2`` takes it away and the text says nothing. The
getset has no such answer: an unfilled ``characters_written`` refuses by name, which is the only one of
CPython's complaints here that is a bare attribute.

The class is not the one that was called. ``OSError_new`` looks the first argument up in the
interpreter's ``errnomap`` and builds the narrower class when it finds one — but only when the called
class is ``OSError`` exactly, never for a subclass of it, and only when the argument answers
``PyLong_Check``: so ``OSError(True, "m")`` is a ``PermissionError`` over the dictionary's equal key of
``1``, while ``OSError("1", "m")`` stays an ``OSError`` with a text in its number field. The numbers are
those of this platform — ``EAGAIN`` and ``EWOULDBLOCK`` share one, ``ENOTCAPABLE`` and ``ESHUTDOWN`` are
in the map because the header defines them, and ``OSError(11, "m")`` is an ordinary ``OSError`` because 11
is ``EINPROGRESS`` only where ``EAGAIN`` is 35.

The arguments follow a third rule. Only a call of two to five arguments is read as this signature, so
``OSError("only")`` has no number at all; a third argument that is not ``None`` is taken as a file name
and cut out of the arguments, which stay the first two; and ``BlockingIOError`` reads its third argument
as a count when it is a number, and then cuts nothing and files no file.

Then the fields are members, and members are reached through the lookup order. A written subclass that
files ``errno`` in its own dictionary is asked for it, so ``Filed(1, "m").errno`` reads ``42`` while
``str`` still says ``[Errno 1]``: the write lands in the dictionary and the field behind the name keeps
the number the call gave. A hook declared by such a class is asked before any field is written — a
``__setattr__`` that does nothing takes the write whole, so the field keeps what the call gave it and the
dictionary stays empty.

Deleting a name goes the same way, and seven names refuse before anything is looked up: ``args`` and the
three chain fields are getsets whose deleting half only says no, ``__dict__`` is a member and
``__suppress_context__`` a C ``char``, neither of which can be taken away, and ``__class__`` belongs to the
family that keeps a value's type. Each says so in its own words, and with a ``TypeError`` rather than the
``AttributeError`` an absent dictionary name gives.
"""

import builtins


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


def shape(value):
    return "%-46s %-20s %-16s %s" % (
        repr(value),
        describe("args", lambda: value.args),
        describe("errno", lambda: value.errno),
        describe("str", lambda: str(value)),
    )


def made(kind, given):
    return shape(kind(*given))


FIELDS = ("errno", "strerror", "filename", "filename2", "characters_written")
NUMBERS = (0, 1, 2, 3, 4, 10, 13, 17, 20, 21, 22, 32, 35, 36, 37, 53, 54, 58, 60, 61, 71, 107, 9999, -1)


class Filed(OSError):
    errno = 42


class Text(OSError):
    strerror = "class text"


class Loud(OSError):
    filename = None


class Counted(OSError):
    characters_written = 9


class Guarded(OSError):
    errno = property(lambda self: "prop")


class Deeper(Filed):
    pass


class Swallows(OSError):
    def __setattr__(self, name, value):
        pass

    def __delattr__(self, name):
        pass


print("== the number, and the class it makes ==")
for number in NUMBERS:
    print("%-6s %-24s %-24s %-24s %s"
          % (number,
             describe("OSError", lambda n=number: type(OSError(n, "m")).__name__),
             describe("IOError", lambda n=number: type(IOError(n, "m")).__name__),
             describe("EnvironmentError", lambda n=number: type(EnvironmentError(n, "m")).__name__),
             describe("str", lambda n=number: str(OSError(n, "m")))))

print("== what the number is not ==")
for given in (("1",), (1.0,), (True,), (False,), (2 ** 70,), (None,), ((2),), ([2],), (2 ** 7,), (7,)):
    print("%-12s %s" % (repr(given[0]), describe("call", lambda g=given: made(OSError, g))))
print(describe("subclass", lambda: type(Filed(2, "m")).__name__),
      describe("narrow", lambda: type(FileNotFoundError(2, "m")).__name__),
      describe("other", lambda: type(BlockingIOError(2, "m")).__name__),
      describe("written", lambda: type(Filed(2, "m")).__mro__[1].__name__))

print("== the arguments, and the third one that is a file ==")
for given in ((), (1,), (1, "a"), (1, "a", "f"), (1, "a", "f", "w"), (1, "a", "f", "w", "g"),
              (1, "a", None), (1, "a", None, None, "g"), (1, "a", "f", "w", "g", "h"), ("only",),
              (1, "a", 0), (1, "a", ""), (1, "a", "f", None, "g")):
    print(describe("call", lambda g=given: made(OSError, g)))

print("== BlockingIOError counts instead of filing ==")
for given in ((11, "m", 3), (11, "m", 3.5), (11, "m", "f"), (11, "m", None), (11, "m", 3, "f"),
              (11, "m", True), (11, "m", 2 ** 70), (11, "m", 3, "f", "g")):
    print(describe("blocking", lambda g=given: made(BlockingIOError, g)))
    print("  %-40s %-24s %s"
          % (describe("chars", lambda g=given: BlockingIOError(*g).characters_written),
             describe("filename", lambda g=given: BlockingIOError(*g).filename),
             describe("file2", lambda g=given: BlockingIOError(*g).filename2)))

print("== the five fields of a value nobody filled ==")
bare = OSError()
for name in FIELDS:
    print("%-20s %s" % (name, describe("read", lambda n=name: getattr(bare, n))))
print(describe("dict", lambda: bare.__dict__),
      describe("vars", lambda: vars(bare)))

print("== a filled field, then the same field taken away ==")
for name in FIELDS:
    value = OSError(1, "a", "f", "w", "g")
    print("%-20s %-26s %-30s %s"
          % (name, describe("before", lambda v=value: getattr(v, name)),
             describe("after del", lambda v=value, n=name: (delattr(v, n), getattr(v, n))),
             describe("del twice", lambda v=value, n=name: (delattr(v, n), "silent"))))
    print("%-20s %-26s %-30s %s"
          % ("", describe("str", lambda v=value: str(v)),
             describe("set 7", lambda v=value, n=name: (setattr(v, n, 7), getattr(v, n))),
             describe("set None", lambda v=value, n=name: (setattr(v, n, None), getattr(v, n)))))
    print("%-20s %-26s %-30s %s"
          % ("", describe("str", lambda v=value: str(v)),
             describe("dict", lambda v=value: v.__dict__),
             describe("del after set", lambda v=value, n=name: (delattr(v, n), getattr(v, n)))))

print("== the text, and which verbs each half uses ==")
for given in ((1, "a"), (1, "a", "f"), (1, "a", "f", None, "g"), (1, "a", "f", "w", "g"),
              ("1", "a"), (1, "a", 42), (1, "a", " quote "), (None, "a"), (1, None),
              ([1], "a"), (1, [2]), (2, "m", b"f")):
    print("%-22s %-40s %s"
          % (repr(given),
             describe("str", lambda g=given: str(OSError(*g))),
             describe("repr", lambda g=given: repr(OSError(*g)))))

print("== a class that claims one of the five names ==")
filed = Filed(1, "m")
print(describe("read", lambda: filed.errno),
      describe("class", lambda: Filed.errno),
      describe("other", lambda: filed.strerror),
      describe("str", lambda: str(filed)),
      describe("repr", lambda: repr(filed)))
print(describe("write", lambda: (setattr(filed, "errno", 5), filed.errno, Filed.errno)),
      describe("dict", lambda: filed.__dict__),
      describe("str after write", lambda: str(filed)))
print(describe("delete", lambda: (delattr(filed, "errno"), filed.errno)),
      describe("delete twice", lambda: delattr(filed, "errno")),
      describe("class keeps", lambda: Filed.errno))
print("%-8s %s" % ("Text", describe("read", lambda: Text(2, "m").strerror)),
      describe("str", lambda: str(Text(2, "m"))),
      describe("write", lambda: (setattr(Text(2, "m"), "strerror", 7), Text(2, "m").strerror)))
loud = Loud(17, "m", "f")
print("%-8s %s" % ("Loud", describe("filename", lambda: loud.filename)),
      describe("args", lambda: loud.args),
      describe("str", lambda: str(loud)),
      describe("del", lambda: (delattr(loud, "filename"), loud.filename)))
counted = Counted(11, "m", 3)
print("%-8s %s" % ("Counted", describe("read", lambda: counted.characters_written)),
      describe("args", lambda: counted.args),
      describe("str", lambda: str(counted)),
      describe("del", lambda: (delattr(counted, "characters_written"), "gone")))
guarded = Guarded(1, "m")
print("%-8s %s" % ("Guarded", describe("read", lambda: guarded.errno)),
      describe("str", lambda: str(guarded)),
      describe("write", lambda: setattr(guarded, "errno", 1)),
      describe("del", lambda: delattr(guarded, "errno")))
print(describe("inherits the shadow", lambda: Deeper(1, "m").errno),
      describe("and the display", lambda: str(Deeper(1, "m"))),
      describe("mro", lambda: [k.__name__ for k in Deeper.__mro__[:-1]]))

print("== a hook that takes the write away from the field ==")
quiet = Swallows(1, "m", "f")
print(describe("write", lambda: (setattr(quiet, "errno", 5), quiet.errno)),
      describe("dict", lambda: dict(quiet.__dict__)),
      describe("str", lambda: str(quiet)))
print(describe("delete", lambda: (delattr(quiet, "filename"), quiet.filename)),
      describe("write the count", lambda: (setattr(quiet, "characters_written", 3),
                                           quiet.characters_written)),
      describe("args", lambda: quiet.args))

print("== names a value will not let go of ==")
firm = OSError(1, "a", "f", "w", "g")
for name in ("args", "__dict__", "__class__", "__traceback__", "__cause__", "__context__",
             "__suppress_context__", "__notes__", "filename", "nope"):
    def taken(n=name):
        delattr(firm, n)
        return n in firm.__dict__

    print("%-22s %s" % (name, describe(name, taken)))
firm.add_note("second look")
print(describe("__notes__ once there", lambda: (delattr(firm, "__notes__"), firm.__dict__)))

print("== the two aliases are the same type ==")
print(describe("identity", lambda: IOError is OSError),
      describe("identity", lambda: EnvironmentError is OSError),
      describe("__name__", lambda: IOError.__name__),
      describe("module", lambda: EnvironmentError.__module__),
      describe("bases", lambda: [b.__name__ for b in IOError.__bases__]))
print(made(OSError, (2, "m", "f")))
print(describe("alias", lambda: made(IOError, (2, "m", "f"))))
print(describe("alias", lambda: made(EnvironmentError, (2, "m", "f"))))
print(describe("alias raise", lambda: type(IOError(2, "m")).__name__))

print("== raised as one class, caught as the narrower one ==")
for number in (2, 17, 21, 61):
    try:
        raise OSError(number, "m", "f")
    except OSError as error:
        print("%-6s %-24s %-30s %s"
              % (number, type(error).__name__, repr(error.args), str(error)))
try:
    raise IOError(2, "m")
except FileNotFoundError as error:
    print("caught:", describe("type", lambda: type(error).__name__),
          describe("str", lambda: str(error)))
try:
    raise EnvironmentError(21, "m", "d")
except IsADirectoryError as error:
    print("caught:", describe("type", lambda: type(error).__name__),
          describe("errno", lambda: error.errno),
          describe("file", lambda: error.filename))
try:
    raise BlockingIOError(35, "m", 4)
except BlockingIOError as error:
    print("caught:", describe("chars", lambda: error.characters_written),
          describe("args", lambda: error.args), describe("str", lambda: str(error)))

print("== a value that was never given the signature ==")
late = OSError("only")
print(describe("errno", lambda: late.errno),
      describe("strerror", lambda: late.strerror),
      describe("filename", lambda: late.filename),
      describe("chars", lambda: late.characters_written),
      describe("str", lambda: str(late)))
late.errno = 2
print(describe("promotes nothing", lambda: type(late).__name__),
      describe("errno", lambda: late.errno),
      describe("str", lambda: str(late)),
      describe("args", lambda: late.args))
print(describe("builtins files one type", lambda: builtins.IOError is builtins.OSError),
      describe("and reads its name", lambda: builtins.IOError.__name__))
