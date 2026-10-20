"""Exceptions a program writes: the two halves {@code BaseException} hands down, and what a value of
that class answers when it is read, raised, chained or compared.

Nothing here lets an exception escape. A traceback is what CPython prints around an uncaught one, and
the runtime has no traceback object yet, so the display of an escaped exception — and the third
argument `__exit__` is handed — stays in the plan instead of being printed here as an answer."""


def describe(label, thunk):
    try:
        return label + " -> " + str(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


class Bare(Exception):
    pass


class Deep(Bare):
    pass


class FromBase(BaseException):
    pass


class Named(ValueError):
    def __init__(self, code, note=""):
        super().__init__("code " + str(code))
        self.code = code
        self.note = note


class Quiet:
    pass


print("--- a class that derives from an exception is raisable")


def raise_it(value):
    raise value


for label, thrown in (
    ("an instance", lambda: raise_it(Bare("boom"))),
    ("the class itself", lambda: raise_it(Bare)),
    ("no arguments", lambda: raise_it(Bare())),
    ("two arguments", lambda: raise_it(Bare("a", "b"))),
    ("a base class", lambda: raise_it(BaseException("plain"))),
    ("a built-in one", lambda: raise_it(KeyError("missing key"))),
    ("three deep", lambda: raise_it(Deep("deep"))),
    ("not an exception", lambda: raise_it(5)),
    ("text", lambda: raise_it("text")),
    ("none", lambda: raise_it(None)),
    ("a plain class", lambda: raise_it(Quiet())),
    ("an instance of a plain class", lambda: raise_it(Quiet)),
):
    print("  ", describe(label, thrown))

print("--- which handler takes it")


def caught_by(clause):
    try:
        raise Deep("deep")
    except clause as error:
        return [type(error).__name__, str(error)]
    return "not caught"


for label, clause in (
    ("Deep", Deep),
    ("Bare", Bare),
    ("Exception", Exception),
    ("BaseException", BaseException),
    ("ValueError", ValueError),
    ("a tuple of two", (IndexError, Bare)),
    ("the wrong one", KeyError),
):
    print("  ", describe(label, lambda clause=clause: caught_by(clause)))

print("  matching by class alone:", [Bare.__mro__[i].__name__ for i in range(3)])
print("  isinstance:", isinstance(Deep("x"), Bare), isinstance(Deep("x"), (Quiet, ValueError)))
print("  issubclass:", issubclass(Named, Exception), issubclass(FromBase, Exception))
print("  type of the value:", type(Deep("x")) is Deep, type(Deep("x")).__name__)

print("--- what the value keeps")

one = Bare("only")
none = Bare()
two = Bare("a", 3)
print("  repr:", repr(one), repr(none), repr(two))
print("  str:", repr(str(one)), repr(str(none)), repr(str(two)))
print("  args:", one.args, none.args, two.args)
print("  args is a tuple:", type(two.args).__name__, len(two.args))
print("  the same tuple twice:", two.args is two.args)
print("  key error keeps its quotes:", str(KeyError("k")), repr(KeyError("k")))
print("  int argument reads as an int:", str(Bare(3)), repr(Bare(3)))
print("  module:", one.__module__, Bare.__module__, type(one).__name__)

print("  the dictionary a value writes into")
reader = Bare("r")
print("   starts empty:", repr(reader.__dict__))
reader.extra = 7
setattr(reader, "another", [1, 2])
print("   after two writes:", list(reader.__dict__.keys()), reader.extra, reader.another)
print("   one dictionary:", reader.__dict__ is reader.__dict__)
del reader.extra
print("   after a delete:", list(reader.__dict__.keys()))
print("   ", describe("read what is not there", lambda: reader.missing))


def delete_missing():
    del reader.missing


print("   ", describe("delete what is not there", delete_missing))


def delete_arguments():
    del one.args


print("  ", describe("the arguments cannot be taken away", delete_arguments))
print("  ", describe("set from text", lambda: (setattr(one, "args", "xy"), one.args, str(one))[1:]))
print("  ", describe("set from a list", lambda: (setattr(one, "args", [1, 2]), one.args)[1]))
print("  ", describe("set from an int", lambda: setattr(one, "args", 5)))
print("  the value the arguments were taken over from:", repr(one), str(one))

print("--- the two halves a class inherits without writing them")

print("  ", describe("the creator, no arguments", lambda: repr(Bare.__new__(Bare))))
print("  ", describe("the creator, two", lambda: repr(Bare.__new__(Bare, 1, "z"))))
print("  ", describe("the creator, keywords only", lambda: repr(Bare.__new__(Bare, flag=1))))
print("  ", describe("the creator kept nothing of a keyword",
                     lambda: repr(Bare.__new__(Bare, flag=1).args)))
print("  ", describe("the creator, keyword after a positional",
                     lambda: repr(Bare.__new__(Bare, "a", flag=1))))
print("  ", describe("the creator of a non class", lambda: repr(BaseException.__new__(3))))
print("  ", describe("the creator of a plain class", lambda: repr(BaseException.__new__(Quiet))))
print("  ", describe("the creator with nothing", lambda: repr(BaseException.__new__())))
print("  ", describe("the initialiser, no arguments", lambda: repr(Bare.__init__(Bare()))))
print("  ", describe("the initialiser, two", lambda: repr(Bare.__init__(Bare(), 1, 2))))
print("  ", describe("the initialiser, keywords", lambda: repr(Bare().__init__(flag=1))))
print("  ", describe("the initialiser with no receiver", lambda: repr(Bare.__init__())))
print("  ", describe("the initialiser of a text", lambda: repr(ValueError.__init__("x"))))
print("  ", describe("the initialiser of another exception",
                     lambda: repr(Bare.__init__(Quiet(), "x"))))
print("  ", describe("call the class with a keyword", lambda: repr(Bare(flag=1))))
print("  ", describe("call the class with a keyword after a positional",
                     lambda: repr(Bare(1, flag=2))))
print("  ", describe("call a built-in with a keyword", lambda: repr(ValueError("a", flag=1))))

print("  ", "the names of the two", [Bare.__init__.__name__, Bare.__new__.__name__])
print("  ", "qualified", [Bare.__init__.__qualname__, Bare.__new__.__qualname__])
print("  ", "kinds", [type(Bare.__init__).__name__, type(Bare.__new__).__name__])
print("  ", "of a value", type(Bare().__init__).__name__)
print("  ", "who declares them", Bare.__init__.__objclass__.__name__)
print("  ", "the unbound initialiser:", repr(Bare.__init__))
print("  ", "a built-in's initialiser:", repr(ValueError.__init__))
print("  ", "a built-in's creator is a method of a class:",
      repr(BaseException.__new__.__self__ is BaseException))

print("--- an __init__ and a __new__ of its own")

print("  ", "through super", repr(Named(7)), Named(7).args, Named(7).code, Named(7).note)
print("  ", "the note still belongs to the value:", repr(Named(7, "second").__dict__))
print("  ", "and str follows what super was given:", str(Named(7)))
print("  ", describe("too few", lambda: Named()), describe("one too many", lambda: Named(1, 2, 3)))


class Made(Exception):
    def __new__(cls, code):
        self = super().__new__(cls, "made " + str(code))
        self.code = code
        return self

    def __init__(self, code):
        super().__init__("made " + str(code))


print("  ", "a class that makes itself:", repr(Made(3)), Made(3).args, Made(3).code)
print("  ", describe("made with nothing", lambda: Made()))


class SilentInit(Exception):
    def __init__(self):
        pass


print("  ", describe("an initialiser that takes nothing", lambda: repr(SilentInit("x"))))
print("  ", "and the arguments the creator kept:", SilentInit.__new__(SilentInit, "y").args)


def caught_init():
    try:
        raise Named(1)
    except Named as error:
        return [error.code, str(error), error.args, error.note]


print("  ", "caught with its own fields", caught_init())

print("--- the chain, read off the value")


class Outer(Exception):
    pass


def explicit_cause():
    try:
        raise IndexError("inner")
    except IndexError as error:
        try:
            raise Outer("outer") from error
        except Outer as raised:
            return [
                type(raised).__name__,
                type(raised.__cause__).__name__,
                str(raised.__cause__),
                raised.__suppress_context__,
                raised.__context__ is error,
            ]


print("  ", "raise from", explicit_cause())


def cause_none():
    try:
        raise IndexError("inner")
    except IndexError:
        try:
            raise Outer("outer") from None
        except Outer as raised:
            return [raised.__cause__, raised.__suppress_context__, raised.__context__ is not None]


print("  ", "raise from None", cause_none())


def implicit_context():
    try:
        raise ValueError("first")
    except ValueError:
        try:
            raise Outer("second")
        except Outer as raised:
            return [type(raised.__context__).__name__, str(raised.__context__),
                    raised.__cause__, raised.__suppress_context__]


print("  ", "one inside a handler", implicit_context())


def nested_context():
    try:
        try:
            raise KeyError("k")
        except KeyError:
            raise IndexError("i")
    except IndexError as outer:
        return [type(outer.__context__).__name__, type(outer.__context__.__context__).__name__]


print("  ", "a chain of three", nested_context())

print("  ", describe("a cause that is not an exception", lambda: setattr(Outer("x"), "__cause__", 5)))
print("  ", describe("a context that is not an exception", lambda: setattr(Outer("x"), "__context__", "s")))
print("  ", describe("a traceback that is not one", lambda: setattr(Outer("x"), "__traceback__", 5)))
print("  ", describe("a traceback of none",
                     lambda: (setattr(Outer("x"), "__traceback__", None), Outer("x").__traceback__)[1]))

kept = Outer("kept")
print("  ", "with_traceback answers the same value:", kept.with_traceback(None) is kept)
print("  ", "and keeps none in the field:", repr(kept.__traceback__))
print("  ", describe("with_traceback with no argument", lambda: kept.with_traceback()))
print("  ", describe("with_traceback with two", lambda: kept.with_traceback(None, None)))
print("  ", describe("with_traceback of an int", lambda: kept.with_traceback(5)))
print("  ", describe("unbound with_traceback", lambda: repr(Outer.with_traceback())))
print("  ", describe("unbound with_traceback on a text", lambda: repr(Outer.with_traceback("x"))))
print("  ", "the method a value answers with:", type(kept.with_traceback).__name__,
      kept.with_traceback.__name__, kept.with_traceback.__self__ is kept)
print("  ", "its kind:", type(Outer.with_traceback).__name__)

print("--- the operations a class can write for itself")


class Declared(Exception):
    def __repr__(self):
        return "declared"


class Written(Exception):
    def __str__(self):
        return "written"


class Both(Exception):
    def __repr__(self):
        return "the repr"

    def __str__(self):
        return "the str"


class Broken(Exception):
    def __repr__(self):
        return 5


class Outer2:
    class Nested(Exception):
        pass


print("  ", describe("a declared repr", lambda: repr(Declared("x"))))
print("  ", describe("a declared repr, str", lambda: str(Declared("x"))))
print("  ", describe("a declared str", lambda: str(Written("x"))))
print("  ", describe("a declared str, repr", lambda: repr(Written("x"))))
print("  ", describe("both halves", lambda: [repr(Both()), str(Both())]))
print("  ", describe("a repr that is not text", lambda: repr(Broken())))
print("  ", describe("a str through a bad repr", lambda: str(Broken())))
print("  ", describe("a nested class names itself plainly", lambda: repr(Outer2.Nested("x"))))
print("  ", describe("and str follows its arguments", lambda: str(Outer2.Nested("x"))))

print("  ", "two like values are two:", Bare("x") == Bare("x"), Bare("x") != Bare("x"))
same = Bare("x")
print("  ", "one value is itself:", same == same, same is same)
print("  ", "so a set of two like exceptions holds two:", len({Bare(), Bare()}))
print("  ", "and a dictionary keys by identity:", len({Bare("k"): 1, Bare("k"): 2}))
print("  ", "hashing a value twice agrees:", hash(same) == hash(same))
keyed = Bare("key")
print("  ", "an exception as a key:", {keyed: 1}[keyed], keyed in {keyed})


class Equal(Exception):
    def __eq__(self, other):
        return isinstance(other, Equal)

    def __hash__(self):
        return 7


class OnlyEqual(Exception):
    def __eq__(self, other):
        return True


print("  ", "a class that writes both:", Equal() == Equal(), hash(Equal()), len({Equal(), Equal()}))
print("  ", "a class that writes only one:", Equal() == Bare(), OnlyEqual.__hash__ is None)
print("  ", describe("and cannot be hashed", lambda: hash(OnlyEqual())))
print("  ", describe("and cannot be a set member", lambda: {OnlyEqual()}))
print("  ", describe("or a dictionary key", lambda: {OnlyEqual(): 1}))


class IntHash(Exception):
    def __hash__(self):
        return "seven"


print("  ", describe("a hash that is not a number", lambda: hash(IntHash())))

print("--- a class statement that ends up with an exception base")


class Middle(ValueError, TypeError):
    pass


def wrong_order():
    class Conflict(Exception, ValueError):
        pass


print("  ", "several bases:", [kind.__name__ for kind in Middle.__mro__])
print("  ", "which handler takes it:", describe("middle", lambda: raise_it(Middle("m"))))
print("  ", "from the base's side:", issubclass(Middle, BaseException), Middle("m").args)
print("  ", describe("the bases in the wrong order", wrong_order))
