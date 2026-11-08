"""Who the printer asks for its stream, and how many times it asks for a write.

`print` looks like one action — a line appears — and is really a sequence of calls a program can count.
`builtin_print_impl` settles the stream first: a call that names no `file` reads `sys.stdout` at that
moment, so the name a program has deleted is refused as `lost sys.stdout` before anyone notices that
`sep` was an integer, and the name filled with `None` answers without writing anything at all. That
silence is the one place where `print` is quieter than `sys.displayhook`, which refuses the same state.
Naming a `file` skips the read, and `file=None` is the same call as naming nothing.

Then come the checks, `sep` before `end`, and then the writes: one per argument, one before each
argument after the first for the separator — written even when it is the empty string — and one for the
ending, with `flush` only after all of those. A value is turned into text as it is reached, so an
argument that refuses leaves everything before it, separator included, already on the stream. The text
is what `str` answers, never what `repr` answers, which is why a string argument arrives unquoted while
`sys.displayhook`, which writes the repr, shows the same string quoted.

The four parameters are keyword-only, so a positional is always something to print, and a keyword the
row does not carry is refused by the calling convention before the body is entered.
"""

import sys

ORIGINAL = sys.stdout


class Sink:
    """A stream that keeps every piece it was handed, and says how often it was flushed."""

    def __init__(self):
        self.pieces = []
        self.flushes = 0

    def write(self, text):
        self.pieces.append(text)
        return "the writer answers"

    def flush(self):
        self.flushes += 1


class NoFlush:
    def __init__(self):
        self.pieces = []

    def write(self, text):
        self.pieces.append(text)


class NoWrite:
    pass


class BadWrite:
    def write(self, text):
        raise OSError("disk full")


class Refuses:
    def __str__(self):
        raise ValueError("no text")


class Says:
    def __str__(self):
        return "the str"

    def __repr__(self):
        return "the repr"


def told(action):
    try:
        return "ok " + answer(action())
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def answer(value):
    return "None" if value is None else type(value).__name__


def recorded(sink):
    return "%r flushes=%d" % (sink.pieces, getattr(sink, "flushes", 0))


print("== the pieces one call leaves on the stream ==")
for label, action in [
    ("nothing", lambda s: print(file=s)),
    ("one", lambda s: print(1, file=s)),
    ("three", lambda s: print(1, "a", [2], file=s)),
    ("sep", lambda s: print(1, "a", [2], sep="|", file=s)),
    ("end", lambda s: print(1, end="!\n", file=s)),
    ("both", lambda s: print(1, "a", [2], sep="|", end="!\n", file=s)),
    ("empty sep", lambda s: print(1, 2, 3, sep="", file=s)),
    ("empty end", lambda s: print(1, 2, end="", file=s)),
    ("sep is none", lambda s: print(1, 2, sep=None, file=s)),
    ("end is none", lambda s: print(1, 2, end=None, file=s)),
    ("sep with no args", lambda s: print(sep="X", end="Y", file=s)),
    ("flush", lambda s: print("v", flush=True, file=s)),
]:
    sink = Sink()
    action(sink)
    print("%-16s %s" % (label, recorded(sink)))

print("== what each write is given ==")
sink = Sink()
print(7, [1, 2], (3,), {"k": 4}, 2.5, True, None, Says(), file=sink)
print("values", recorded(sink))
sink = Sink()
print("plain", "quoted", file=sink)
print("strings", recorded(sink))

print("== the text is str, and it is asked for as each value is reached ==")
sink = Sink()
print("refused", told(lambda: print("first", Refuses(), "last", file=sink)))
print("left behind", recorded(sink))
sink = Sink()
print("refused", told(lambda: print(Refuses(), "x", sep="|", file=sink)))
print("left behind", recorded(sink))

print("== the stream is settled before the separator is looked at ==")


def drop():
    del sys.stdout


ACTIONS = [
    ("plain", lambda: print(1)),
    ("bad sep", lambda: print(1, sep=3)),
    ("no args", lambda: print()),
    ("file=", lambda: print(1, sep=3, file=Sink())),
]
for label, state in [
    ("gone", drop),
    ("none", lambda: setattr(sys, "stdout", None)),
    ("integer", lambda: setattr(sys, "stdout", 1)),
    ("no write", lambda: setattr(sys, "stdout", NoWrite())),
    ("write refuses", lambda: setattr(sys, "stdout", BadWrite())),
]:
    # The answers are gathered while the name is in the state and reported after it is put back: the
    # line that reports them is itself a call, and a call is what is being asked about.
    state()
    answers = [(name, told(action)) for name, action in ACTIONS]
    sys.stdout = ORIGINAL
    print("--", label)
    for name, answer_text in answers:
        print("   %-8s %s" % (name, answer_text))

print("== a name read at the call, not at startup ==")
first, second = Sink(), Sink()
sys.stdout = first
print("one")
sys.stdout = second
print("two")
sys.stdout = ORIGINAL
print("first", recorded(first))
print("second", recorded(second))

print("== naming a file, and naming none ==")
sink, named = Sink(), Sink()
sys.stdout = sink
answers = [
    ("file given", told(lambda: print(1, 2, file=named))),
    ("file is none", told(lambda: print(3, 4, file=None))),
    ("bad sep, file given", told(lambda: print(5, sep=3, file=named))),
    ("bad sep, none given", told(lambda: print(5, sep=3))),
]
sys.stdout = ORIGINAL
for label, text in answers:
    print("  %-20s %s" % (label, text))
print("  the named file", recorded(named))
print("  the read stream", recorded(sink))

print("== the two checks, and which comes first ==")
sink = Sink()
for label, value in [
    ("int", 3),
    ("list", [1]),
    ("bytes", b"-"),
    ("tuple", ("a",)),
    ("dict", {"a": 1}),
]:
    print("sep %-6s %s" % (label, told(lambda: print(1, sep=value, file=sink))))
    print("end %-6s %s" % (label, told(lambda: print(1, end=value, file=sink))))
print("both wrong", told(lambda: print(1, sep=3, end=4, file=sink)))
print("nothing written", recorded(sink))

print("== the calling convention, which is not the body's ==")
print("unknown name", told(lambda: print(1, other=2)))
print("keyword first", told(lambda: print(sep=3, file=Sink())))
print("unpacked", told(lambda: print(1, **{})))
sink = Sink()
print("empty unpacking", told(lambda: print(1, 2, sep="|", **{"file": sink})), recorded(sink))

print("== flush is a truth test, and a second kind of call ==")
for value in ("", "x", [], [0], 0, 1, None, 0.0):
    sink = Sink()
    print("flush %-5r %s %s" % (value, told(lambda: print("v", flush=value, file=sink)), recorded(sink)))
bare = NoFlush()
print("no flush member", told(lambda: print(1, flush=True, file=bare)), recorded(bare))

# A stream that is `None` answers before the flush is reached, so the one call that would have asked
# for it is silent in the same way the one that would have written a separator is. `file=None` is the
# same call as naming nothing, so it is the name that decides.
sink = Sink()
sys.stdout = sink
answered = told(lambda: print(1, flush=True, file=None))
sys.stdout = None
quiet = told(lambda: print(1, flush=True, file=None))
refused = told(lambda: print(1, flush=True))
sys.stdout = ORIGINAL
print("file none, name live", answered, recorded(sink))
print("file none, name none", quiet)
print("no file, name none", refused)
