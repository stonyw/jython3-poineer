"""Three names, one prompt, and the line a stream answers with.

`input` looks like a call that prints something and then waits, and it is really five steps whose order a
program can watch. `builtin_input_impl` first reads `sys.stdin`, then `sys.stdout`, then `sys.stderr` —
and a name that is missing, or set to `None`, is refused as `lost sys.<name>` with nothing else asked,
which is why the six rows of `the three names, in order` show an empty log. Then it flushes `stderr`,
drops whatever that refusal was, and asks `sys.stdin` for its `fileno` to find out whether a terminal is
on the other end. This runtime never reads a terminal through the `readline` library, so that answer
decides nothing here — but it is still read, and the refusals of *reading* it survive while the refusals
of *asking* are dropped: a stream with no `fileno`, or one that raises, is on the fallback path in peace,
while a float and a number too wide for a C long are answered in the words the conversion itself chose.

Then the prompt, if there was one. `input()` with no argument writes nothing yet still flushes, and
`input(None)` is not "no prompt" but a prompt whose `str` is the four characters `None` — the argument
was given, which is all the call asks. The prompt is written the way `print` writes, through the
stream's own `write`, so a `__str__` that refuses is heard as its own error, and a stream with no `write`
is refused before the prompt has been looked at at all.

What is left is one call of `readline` with no arguments, and exactly one character taken off the end of
what answers. A `'two\\r\\n'` comes back as `'two\\r'` because only the last newline counts, an answer
with no newline at all is handed back as the very object that answered (`kept is HELD` reads `True`), and
an answer that does have one is cut down to a fresh value of the plain type — `cut is HELD` reads `False`,
because the cutting makes a new object rather than shortening the one that answered. An empty answer is
the end of the descriptor and raises `EOFError`, and an answer that is neither text nor octets is refused
by the unpacking rather than by the caller.

`sys.stdin` is the reading half of the same stream the two writing ones are: `mode` reads `'r'`,
`readable()` and `writable()` swap places, `fileno()` is `0`, `flush()` answers `None` with nothing to
drain, and the writing half of the type is refused with `not writable`. That refusal is one of three the
reference calls `io.UnsupportedOperation`, a type that is both an `OSError` and a `ValueError`, and this
runtime has no `io` module to put it in — so those four rows read the sentence and the `OSError` half, and
leave the name out.

The last of the stream's face is in its dictionary, and the dictionary is where `mode` really is. No
`TextIOWrapper` files a member of that name — three rows read `mode in the book False`, one per stream —
so the entry the interpreter writes as it makes the stream is an ordinary attribute a program can rewrite.
A rewritten mode is read back both as `s.mode` and in the repr, and a deleted one leaves the repr with no
mode field at all, because the line is built out of the attributes rather than out of the descriptor.
`s.__dict__` is one dictionary, the same object each time it is asked, and a name a program invents lands
beside `mode` until it is deleted, when the read answers in the words of the type. The six members the type
*does* file refuse a write in two sentences, split by the kind of row each is: `name`, `closed` and
`errors` are filed with no setter and say which name they refused, while `encoding`, `line_buffering` and
`write_through` are read-only fields that name nothing at all. A seventh refusal, `__dict__` itself, is the
only one that quotes a different type — `_io._IOBase`, which is where that row is.

After `close()` the members divide three ways: `readable`, `fileno` and `isatty` raise `I/O operation on
closed file` with no full stop, `flush`, `readline`, `write` and `input` itself raise the same sentence with
one, and `closed`, `mode`, `name`, `errors`, `line_buffering`, `writable` and a second `close()` carry on
answering — the last of them `None`, the repr unchanged.

The calling convention is the count family's: `input expected at most 1 argument, got 2` for a second
argument, and `input() takes no keyword arguments` for any keyword, positional or not. All three of those
refusals show an empty log, so every one of them is answered before a name is read out of `sys`.
"""

import builtins
import sys

ORIGINAL = builtins.input
REAL_OUT = sys.stdout
REAL_ERR = sys.stderr
REAL_IN = sys.stdin
LOG = []


def say(text):
    """Write one row through the descriptor that was there at startup.

    Most of what this file measures happens with `sys.stdout` replaced by a recorder, and the answers
    cannot be written to the thing under test: `input` would flush it, `write` would be logged in it, and
    a row printed into it would never reach the comparison at all.
    """
    REAL_OUT.write(text + "\n")


class Out:
    """A stream that writes into the log, and can be asked to refuse."""

    def __init__(self, label="out", fail_flush=False, fail_write=False, fd=1):
        self.label = label
        self.fail_flush = fail_flush
        self.fail_write = fail_write
        self.fd = fd

    def write(self, text):
        LOG.append(self.label + ".write " + repr(text))
        if self.fail_write:
            raise RuntimeError("no write")
        return len(text)

    def flush(self):
        LOG.append(self.label + ".flush")
        if self.fail_flush:
            raise RuntimeError("no flush")

    def fileno(self):
        LOG.append(self.label + ".fileno")
        return self.fd


class In(Out):
    def __init__(self, answers, fd=0, raise_fd=False):
        super().__init__("in", fd=fd)
        self.answers = list(answers)
        self.raise_fd = raise_fd

    def readline(self):
        LOG.append("in.readline")
        return self.answers.pop(0) if self.answers else ""

    def fileno(self):
        LOG.append("in.fileno")
        if self.raise_fd:
            raise OSError("no fd")
        return self.fd


def install(answers=(), fd=0, raise_fd=False, out=None, err=None):
    sys.stdin = In(list(answers), fd=fd, raise_fd=raise_fd)
    sys.stdout = out if out is not None else Out()
    sys.stderr = err if err is not None else Out("err")


def restore():
    sys.stdin, sys.stdout, sys.stderr = REAL_IN, REAL_OUT, REAL_ERR


def told(action, note):
    LOG.clear()
    try:
        got = action()
        say("%-18s -> %s %s | %s" % (note, repr(got), type(got).__name__, " / ".join(LOG)))
    except BaseException as error:
        say("%-18s !! %s: %s | %s" % (note, type(error).__name__, error, " / ".join(LOG)))


def asked(action, note):
    try:
        say("%-16s ok %r" % (note, action()))
    except BaseException as error:
        say("%-16s !! %s: %s" % (note, type(error).__name__, error))


def wrote(target, name, value):
    """One attribute write, and what the same name reads back if the write was allowed."""
    try:
        setattr(target, name, value)
        return "ok %r" % (getattr(target, name),)
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def removed(target, name):
    """One delete, and the read that follows it — which is the refusal for a name that is not there."""
    try:
        delattr(target, name)
        return "ok %r" % (getattr(target, name, "gone"),)
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def answered(note, action):
    """One refusal, read for its sentence and for the type it is an instance of.

    The name is left out on purpose: the reference calls this refusal `io.UnsupportedOperation`, a type
    that is both an `OSError` and a `ValueError`, and this runtime has not built the `io` module that
    would hold it. What both answers carry is the sentence and the `OSError` half.
    """
    try:
        action()
        say("%-28s -> no error" % note)
    except BaseException as error:
        say("%-28s %-18s an OSError %s" % (note, repr(str(error)), isinstance(error, OSError)))


class Loud:
    def __str__(self):
        raise ValueError("no text")


class NotStr:
    def __str__(self):
        return 5


class Quiet:
    def __str__(self):
        LOG.append("str asked")
        return "shh"


print("== the row the name carries ==")
row = builtins.input
print("name", row.__name__, "qualname", row.__qualname__)
print("module", row.__module__, "type", type(row).__name__)
print("signature", repr(row.__text_signature__))
print("doc length", len(row.__doc__), "doc lines", row.__doc__.count("\n") + 1)
print("repr", repr(row))
print("the same function", row is builtins.input, row is ORIGINAL)

print("== the stream that reads ==")
stream = sys.stdin
print("type", type(stream).__module__ + "." + type(stream).__name__)
print("repr", repr(stream))
asked(lambda: stream.__dict__, "__dict__")
for name in ("mode", "name", "encoding", "errors", "closed", "line_buffering", "write_through"):
    print("  %-15s %r" % (name, getattr(stream, name)))
asked(lambda: stream.fileno(), "fileno")
asked(lambda: stream.readable(), "readable")
asked(lambda: stream.writable(), "writable")
asked(lambda: stream.isatty(), "isatty")
print("stdin is __stdin__", sys.stdin is sys.__stdin__)
print("stdout is __stdout__", sys.stdout is sys.__stdout__, "stderr", sys.stderr is sys.__stderr__)
print("flush of a reading stream", sys.stdin.flush(), "mode", sys.stdin.mode)

print("== the dictionary the stream keeps ==")
for stream in (sys.stdin, sys.stdout, sys.stderr):
    print("%-9s %-22s same=%-5s mode in the book %s"
          % (stream.name, list(stream.__dict__), stream.__dict__ is stream.__dict__,
             "mode" in type(stream).__dict__))
print("write", wrote(sys.stdin, "mode", "z"), "read", repr(sys.stdin.mode), "repr", repr(sys.stdin))
print("del", removed(sys.stdin, "mode"), "repr without", repr(sys.stdin))
sys.stdin.mode = "r"
print("back", repr(sys.stdin.mode), repr(sys.stdin), list(sys.stdin.__dict__))
print("an attribute", wrote(sys.stdout, "spam", 1), repr(sys.stdout.spam), list(sys.stdout.__dict__))
print("del of it", removed(sys.stdout, "spam"), "read after", removed(sys.stdout, "spam"))
for name in ("name", "encoding", "closed", "line_buffering", "errors", "write_through"):
    print("%-15s write %-60s del %s" % (name, wrote(sys.stderr, name, "x"), removed(sys.stderr, name)))
print("the dict itself", wrote(sys.stderr, "__dict__", {}), removed(sys.stderr, "__dict__"))
print("still", repr(sys.stderr), sys.stderr.mode, sys.stderr.encoding, sys.stderr.errors)

print("== the calls one input makes ==")
install(["one\n", "two\n"])
told(lambda: builtins.input("go: "), "with prompt")
told(lambda: builtins.input(), "no prompt")
restore()

print("== what the prompt becomes ==")
for prompt, note in [(None, "None"), (0, "zero"), ("", "empty string"), (Quiet(), "a value")]:
    install(["one\n"])
    told(lambda: builtins.input(prompt), note)
install(["one\n"])
told(lambda: builtins.input(Loud()), "str refuses")
install(["one\n"])
told(lambda: builtins.input(NotStr()), "str answers int")
install(["one\n"], out=type("NoWrite", (Out,), {"write": None})())
told(lambda: builtins.input("go: "), "no write member")
install(["one\n"], out=Out(fail_write=True))
told(lambda: builtins.input("go: "), "write refuses")
restore()

print("== the refusals that are dropped ==")
install(["one\n"], err=Out("err", fail_flush=True))
told(lambda: builtins.input("go: "), "stderr flush")
install(["one\n"], out=Out(fail_flush=True))
told(lambda: builtins.input("go: "), "stdout flush")
install(["one\n"], raise_fd=True)
told(lambda: builtins.input("go: "), "fileno raises")
install(["one\n"], out=type("NoFlush", (Out,), {"flush": None})())
told(lambda: builtins.input("go: "), "no flush member")

print("== the refusals that are kept ==")
for fd, note in [(1.5, "fileno float"), ("x", "fileno string"), (2**70, "fileno huge")]:
    install(["one\n"], fd=fd)
    told(lambda: builtins.input("go: "), note)
for fd, note in [(-1, "minus one"), (0, "zero"), (2**31, "2**31")]:
    install(["one\n"], fd=fd)
    told(lambda: builtins.input("go: "), note)

print("== the line, and what is cut off it ==")
for answer, note in [("", "empty"), ("no newline", "no last newline"), ("two\r\n", "crlf ends"),
                     ("a\r\nb\n", "crlf inside"), (b"raw\n", "octets cut"), (b"", "octets empty"),
                     (7, "an int"), (None, "nothing")]:
    install([answer])
    told(lambda: builtins.input("go: "), note)
restore()

HELD = "kept"
CUT = "held\n"
OCTS = b"octs\n"


def read_one(answer):
    """Ask `input` of a stream that answers once and keeps the answer, so identity can be read back."""
    holder = [answer]
    written = []
    reader = type("One", (object,), {
        "readline": lambda self: holder[0],
        "fileno": lambda self: 0,
    })()
    writer = type("Writer", (object,), {
        "write": lambda self, text: written.append(text),
        "flush": lambda self: None,
    })()
    sys.stdin, sys.stdout, sys.stderr = reader, writer, writer
    try:
        return builtins.input("p> "), holder[0], "".join(written)
    finally:
        restore()


got, source, prompt = read_one(HELD)
say("kept is HELD %s type %s prompt %r" % (got is source, type(got).__name__, prompt))
got, source, prompt = read_one(CUT)
say("cut is HELD %s type %s text %r" % (got is source, type(got).__name__, got))
got, source, prompt = read_one(OCTS)
say("octs cut is HELD %s type %s text %r" % (got is source, type(got).__name__, got))
got, source, prompt = read_one(b"octs")
say("octs kept is HELD %s type %s" % (got is source, type(got).__name__))

print("== a stream with nothing to read ==")
install(out=Out(), err=Out("err"))
sys.stdin = type("NoReadline", (object,), {"fileno": lambda self: 0})()
LOG.clear()
try:
    builtins.input("go: ")
    say("no readline -> no error")
except BaseException as error:
    say("no readline !! %s: %s | %s" % (type(error).__name__, error, " / ".join(LOG)))
restore()

print("== the three names, in order ==")
for name in ("stdin", "stdout", "stderr"):
    saved = getattr(sys, name)
    delattr(sys, name)
    told(lambda: builtins.input("go: "), "del " + name)
    setattr(sys, name, saved)
    setattr(sys, name, None)
    told(lambda: builtins.input("go: "), name + " is None")
    setattr(sys, name, saved)
restore()

print("== the calling convention ==")
install(["one\n", "two\n", "three\n"])
told(lambda: builtins.input(), "nothing")
told(lambda: builtins.input(1, 2), "two given")
told(lambda: builtins.input(prompt=1), "named")
told(lambda: builtins.input(1, x=2), "mixed")
told(lambda: builtins.input(*()), "star unpacked")
told(lambda: builtins.input(**{}), "double star")
restore()

print("== the writing half of a reading stream ==")
for name, action in [
        ("write", lambda: sys.stdin.write("x")),
        ("writelines", lambda: sys.stdin.writelines(["x"])),
        ("readline of a writing stream", lambda: sys.stdout.readline())]:
    answered(name, action)
try:
    sys.stdin.write("x")
except BaseException as error:
    print("as text", repr(str(error)), "an OSError", isinstance(error, OSError))

print("== after the stream is closed ==")
sys.stdin.close()
asked(lambda: sys.stdin.closed, "closed")
asked(lambda: sys.stdin.mode, "mode")
asked(lambda: sys.stdin.name, "name")
asked(lambda: sys.stdin.errors, "errors")
asked(lambda: sys.stdin.line_buffering, "line_buffering")
asked(lambda: sys.stdin.readable(), "readable")
asked(lambda: sys.stdin.writable(), "writable")
asked(lambda: sys.stdin.fileno(), "fileno")
asked(lambda: sys.stdin.isatty(), "isatty")
asked(lambda: sys.stdin.flush(), "flush")
asked(lambda: sys.stdin.close(), "close again")
asked(lambda: sys.stdin.readline(), "readline")
asked(lambda: sys.stdin.write("x"), "write")
asked(lambda: builtins.input("go: "), "input")
print("repr unchanged", repr(sys.stdin))
print("the row still there", builtins.input is ORIGINAL)
