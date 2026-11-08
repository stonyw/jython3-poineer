"""What the two hook names of one module answer, and what printing an answer saves.

`sys.displayhook` is the showing half of the interactive loop, and `sys.__displayhook__` is the same
object filed a second time: `COPY_SYS_ATTR` copies the entry rather than building another, so a program
that replaces the working name still reaches the original under the doubled one. Six reads describe the
pair before any call is made — its type, its name, its qualifier, the module it names itself in, the
module that owns it, and the two halves of the sentence its table row carries — and this case reads all
six of them, because `exit`, `exc_info` and `getdefaultencoding` are rows of the same book that had
nothing at all to say until now.

A call then does five things, in an order a program can watch. It reads the builtins module first. A
`None` value is not a result worth showing, and leaves both the stream and the saved name untouched. Any
other value is saved as `None` before anything is written — the C comment gives the reason, "to avoid
recursion", for a value whose own repr reads that name back — then `sys.stdout` is required, with a
stream that is gone or is `None` answered by one refusal rather than by silence, then the repr and the
newline arrive as two separate writes, and the value itself is saved last. So a value whose repr refuses
is the one value the saved name never gets, which is measurable by asking what it holds at each step.

The refusals come from two places and are therefore worded in two ways: the count of a `METH_O` row and
the keyword refusal of a row that takes nothing are the calling convention's, and name the function as
module and name, while `exit` counts itself with the legacy wording after the convention has refused the
keyword. `sys.stdout` is a name a program may delete, replace with an integer, or fill with an object
whose own write refuses, and each of those is a different complaint about the same call.
"""

import sys

import builtins as B

ORIGINAL = sys.stdout


def told(action):
    try:
        return "ok " + describe(action())
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def describe(value):
    if value is None:
        return "None"
    if isinstance(value, str):
        return repr(value)
    if isinstance(value, tuple):
        return "tuple of %d" % len(value)
    return type(value).__name__


def saved():
    return "missing" if not hasattr(B, "_") else describe(B._)


class Sink:
    def __init__(self):
        self.lines = []

    def write(self, text):
        self.lines.append(text)
        self.lines.append(saved())

    def flush(self):
        self.lines.append("<flush>")


class Silent:
    def __repr__(self):
        return "SILENT"


class NoRepr:
    def __repr__(self):
        raise ValueError("nope")


class NoWrite:
    pass


class BadWrite:
    def write(self, text):
        raise OSError("disk full")


print("== the two names, and the one entry behind them ==")
print("in the book", "displayhook" in vars(sys), "__displayhook__" in vars(sys))
print("one object", sys.displayhook is sys.__displayhook__)
print("type", type(sys.displayhook).__name__)

print("== the seven rows of that book, and what each carries ==")
for name in (
    "displayhook",
    "__displayhook__",
    "exc_info",
    "excepthook",
    "__excepthook__",
    "exit",
    "getdefaultencoding",
):
    member = getattr(sys, name)
    print("--", name, type(member).__name__, repr(member))
    print("   name", repr(member.__name__), "qual", repr(member.__qualname__))
    print("   module", repr(member.__module__), "self", type(member.__self__).__name__)
    print("   sig", repr(member.__text_signature__))
    print("   doc", repr(member.__doc__))

print("== what one call writes, and what it holds at each step ==")
sink = Sink()
sys.stdout = sink
B._ = "SENTINEL"
answer = describe(sys.displayhook(Silent()))
sys.stdout = ORIGINAL
print("return", answer, "writes", sink.lines, "then", saved())

print("== none is not a result ==")
sink = Sink()
sys.stdout = sink
B._ = "SENTINEL"
answer = describe(sys.displayhook(None))
sys.stdout = ORIGINAL
print("return", answer, "writes", sink.lines, "then", saved())

print("== the value saved is the value, not its text ==")
B._ = "SENTINEL"
item = [1, 2]
sys.displayhook(item)
print("same object", B._ is item, describe(B._))
B._ = "SENTINEL"
sys.displayhook(42)
print("the number itself", type(B._).__name__, B._ + 1)

print("== a repr that refuses, and what is saved then ==")
sys.stdout = Sink()
B._ = "SENTINEL"
refused = told(lambda: sys.displayhook(NoRepr()))
sys.stdout = ORIGINAL
print("call", refused, "then", saved())

print("== the calling convention of a METH_O row ==")


def call(count):
    return sys.displayhook(*[None] * count)


for count in (0, 2, 3):
    print(count, told(lambda: call(count)))
print("keyword", told(lambda: sys.displayhook(object=1)))
print("another name", told(lambda: sys.displayhook(x=1)))
print("empty mapping", told(lambda: sys.displayhook(**{})))

print("== which names refuse, and how ==")
for name in ("exc_info", "getdefaultencoding"):
    member = getattr(sys, name)
    print("--", name)
    print("   zero", told(lambda: member()))
    print("   one", told(lambda: member(1)))
    print("   two", told(lambda: member(1, 2)))
    print("   keyword", told(lambda: member(x=1)))
    print("   empty mapping", told(lambda: member(**{})))
print("-- exit")
print("   keyword", told(lambda: sys.exit(status=1)))
print("   empty mapping", told(lambda: sys.exit(**{})))
print("   two", told(lambda: sys.exit(1, 2)))
print("   three", told(lambda: sys.exit(1, 2, 3)))
print("-- excepthook")
print("   keyword", told(lambda: sys.excepthook(object=1)))

print("== the stream in the states a program can leave it in ==")


def drop():
    del sys.stdout


STATES = [
    ("none", lambda: setattr(sys, "stdout", None)),
    ("gone", drop),
    ("integer", lambda: setattr(sys, "stdout", 1)),
    ("no write", lambda: setattr(sys, "stdout", NoWrite())),
    ("write refuses", lambda: setattr(sys, "stdout", BadWrite())),
]
for label, state in STATES:
    B._ = "SENTINEL"
    state()
    answer = told(lambda: sys.displayhook(1))
    sys.stdout = ORIGINAL
    print(label, "|", answer, "| saved", saved())
    B._ = "SENTINEL"
    state()
    quiet = told(lambda: sys.displayhook(None))
    sys.stdout = ORIGINAL
    print(label, "with none |", quiet, "| saved", saved())

print("== the name is emptied before the stream is asked for ==")
B._ = "SENTINEL"
sys.stdout = None
refused = told(lambda: sys.displayhook(7))
sys.stdout = ORIGINAL
print("refusal", refused, "| saved", saved())
print("what the name holds", describe(B._))
