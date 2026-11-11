r"""The read side of a text stream: what a chunk hands back, and what the wrong stream refuses.

Everything here is answered off one pipe, in the order the reads are asked. The pipe holds ten whole
lines — `one\r\n`, `two\r\n`, `three\rcafé\r\n`, `🐍x\n`, `abc🐍🐍def\n`, `six\r\n`, `seven\n`,
`eight\n`, `nine\r\n` and `tail\r\n` — and then ends, so the last line has a terminator and the walk
below reaches the end of the descriptor. The chunk size is set to four octets before the first read,
so the reads below are made of the smallest chunks the field allows rather than one big one — and
every answer is the one it would be with the default 8192, because the loops reassemble what the
descriptor hands out. That is what makes the astral lines worth reading: a snake is four octets, so a
chunk of four carries it whole or cuts it in half, and the half waits for the chunk that completes it.

The pipe stops at a clean end on purpose. `errors` is `strict`, so an octet that starts a sequence its
neighbour refuses to continue raises `UnicodeDecodeError` — while the report of that failure carries a
`<frozen codecs>` frame and PEP 657 anchors this runtime does not draw yet, which keeps the comparison
of an escaped traceback out of this corpus rather than out of the runtime.

The two counters the stream keeps are in different units. It asks the descriptor for octets and
answers in characters, so `read(3)` is `one` then `\r\nt` then `wo\r`, and the `read(5)` after that
crosses a line ending to `\nthre`. A count of `0` — which is also `False`, a bool being a count like
any other — is the empty answer that consumes nothing, so the read after it starts where it would
have, and a negative count or `None` asks for everything left, which after the walk below is nothing
at all.

Lines end at the one character the stream was opened with. `sys.stdin` is opened with `newline='\n'`,
so nothing is translated and a `\r` is ordinary text: the line the reads leave behind answers
`'\rcafé\r\n'`, its first character the `\r` that `three` ended with. A limit cuts inside a line
rather than ending it — `readline(2)` takes the two characters of `🐍x` and the `readline()` after it
answers the rest of that same line, which is the terminator alone, and `readline(5)` and
`readline(100)` cut `abc🐍🐍def\n` into `abc🐍🐍` and `def\n` — while a negative limit answers the whole
line however long it is. `readlines` counts the same characters against its hint and gives up once the
run it collected is longer than that hint, the overshooting line included: a hint of five takes two
lines where `six\r\n` alone fills it, a hint of four takes one, and a hint of `True` takes the one
line `tail\r\n`. Iteration is the same reader, with the stream as its own cursor, and at the end of
the descriptor `read` and `readline` answer `''`, `readlines` and `list` answer `[]` and `next` raises
a `StopIteration` with nothing in it.

The refusals come in the order the reference reaches them, which is the order of the C function: the
arguments are unpacked, then converted, and only then does the body look at the stream. So a closed
stream still refuses `read(1.5)` by the conversion and `write(b'x')` by the text check, and only
`seek`'s whence-less cookie and `truncate`'s position travel unconverted, because nothing but a
seekable or a truncating stream would ever read them. Then comes the closed check, ahead of the
direction: a closed stream refuses `read()`, `write('x')`, `flush()`, `tell()`, `seek(0)`,
`truncate()`, `writelines([...])`, `readlines()`, `iter()`, `next()` and `list()` all with
`I/O operation on closed file.` — full stop and all, which the questions asked of a closed stream
answer without — and a stream open the wrong way round refuses in words of its own, `not readable` off
the writing one and `not writable` off the reading one, as an `io.UnsupportedOperation`.

Three more sentences belong to a stream with nothing under it. There is no buffer and no raw
descriptor beneath this stream, so `seekable()` is False, `seek` and `tell` refuse `underlying stream
is not seekable` however the call is shaped — even `seek(0, True)`, whose whence converts to one, and
even `seek(None)` and `seek(2 ** 70)` — and `truncate` answers the refusal its buffer would have made,
which the reference words as nothing but `truncate`. The `_check` rows ask their question of the
stream and refuse the answer rather than the call: `File or stream is not seekable.`,
`File or stream is not readable.` and `File or stream is not writable.` once the question has said
False, and the plain `True` rather than `None` once it has said yes, because what the row hands back
is the answer the question gave. `_checkClosed` is the one of them whose whole job is the closed
check, so it is the one that answers a closed stream with the full stop.

The fields that describe the chunk rather than its contents answer last. `newlines` is None, because
the universal-newline scan that would fill it in is the one this stream never runs; it stays None over
a closed descriptor, and a write reaches it only as `attribute 'newlines' of '_io.TextIOWrapper'
objects is not writable`. `mode`, `encoding` and `errors` answer `r`, `utf-8` and `strict`,
and the repr spells the same pipe after the close as `<_io.TextIOWrapper name='<stdin>' mode='r'
encoding='utf-8'>`. `_CHUNK_SIZE` reads 8192 until it is rewritten and 4 after that, and rewriting it
takes an integer count that is strictly positive: `0` and `-3` answer `a strictly positive integer is
required`, `1.5` and `None` are refused by their type before they are asked, and `2 ** 70` is refused
as a bad value where the same number asked of `read` is an `OverflowError`. A write reaches the field
after the stream has closed, where a delete refuses in the words of a row that has a setter and no
deleter.
"""

import sys

stdin = sys.stdin


def ask(label, call):
    try:
        print(label, repr(call()))
    except BaseException as exc:
        print(label, type(exc).__module__ + "." + type(exc).__name__, "|", exc)


def show(label, call):
    try:
        print(label, call())
    except BaseException as exc:
        print(label, type(exc).__module__ + "." + type(exc).__name__, "|", exc)


ask("chunk default", lambda: stdin._CHUNK_SIZE)
print("chunk to four", repr(setattr(stdin, "_CHUNK_SIZE", 4)))

# One read at a time, counted in characters however the octets fall.
ask("read 0", lambda: stdin.read(0))
ask("read 3 a", lambda: stdin.read(3))
ask("read 3 b", lambda: stdin.read(3))
ask("read 3 c", lambda: stdin.read(3))
ask("read 5 across", lambda: stdin.read(5))
ask("read 1", lambda: stdin.read(1))

# The same line, cut where the limit asks for it and finished by the read that follows.
ask("readline", lambda: stdin.readline())
ask("readline 0", lambda: stdin.readline(0))
ask("readline 2 of astral", lambda: stdin.readline(2))
ask("readline rest of line", lambda: stdin.readline())
ask("readline 5 of astral", lambda: stdin.readline(5))
ask("readline past end", lambda: stdin.readline(100))

# The hint of readlines, which stops on the line that no longer fits and keeps it.
ask("readlines 5", lambda: stdin.readlines(5))
ask("readlines 4", lambda: stdin.readlines(4))

# A count of False is a count of nothing, and a negative count is the whole line.
ask("readline -2", lambda: stdin.readline(-2))
ask("read False", lambda: stdin.read(False))
ask("readlines True", lambda: stdin.readlines(True))

# Iteration is the same reader, and the end of it is the end of the descriptor.
show("iter is self", lambda: iter(stdin) is stdin)
for line in stdin:
    print("walked", repr(line))
ask("read at end", lambda: stdin.read())
ask("readline at end", lambda: stdin.readline())
ask("readlines at end", lambda: stdin.readlines())
ask("list at end", lambda: list(stdin))
show("next at end", lambda: next(stdin))

# The counts that mean all: None is one of them for two of the three rows, and not for the third.
ask("read None", lambda: stdin.read(None))
ask("read -2 at end", lambda: stdin.read(-2))
ask("readlines None", lambda: stdin.readlines(None))
ask("readline None", lambda: stdin.readline(None))

# The fields that describe the chunk rather than its contents.
ask("newlines", lambda: stdin.newlines)
ask("chunk 1.5", lambda: setattr(stdin, "_CHUNK_SIZE", 1.5))
ask("chunk 0", lambda: setattr(stdin, "_CHUNK_SIZE", 0))
ask("chunk -3", lambda: setattr(stdin, "_CHUNK_SIZE", -3))
ask("chunk 2**70", lambda: setattr(stdin, "_CHUNK_SIZE", 2 ** 70))
ask("chunk None", lambda: setattr(stdin, "_CHUNK_SIZE", None))
ask("chunk back", lambda: stdin._CHUNK_SIZE)
ask("newlines write", lambda: setattr(stdin, "newlines", "x"))
ask("mode", lambda: stdin.mode)
ask("encoding", lambda: stdin.encoding)
ask("errors", lambda: stdin.errors)

# The stream open the wrong way round: the reading rows of a writing stream, and the other half.
ask("stdout read", lambda: sys.stdout.read())
ask("stdout readline", lambda: sys.stdout.readline())
ask("stdout readlines", lambda: sys.stdout.readlines())
ask("stdout list", lambda: list(sys.stdout))
ask("stdout next", lambda: next(sys.stdout))
ask("stdin write", lambda: stdin.write("x"))
ask("stdin writelines", lambda: stdin.writelines(["x"]))
ask("stdout seekable", lambda: sys.stdout.seekable())
ask("stdout _checkReadable", lambda: sys.stdout._checkReadable())
ask("stdin _checkWritable", lambda: stdin._checkWritable())
ask("stdout _checkWritable", lambda: sys.stdout._checkWritable())
ask("stdin _checkReadable", lambda: stdin._checkReadable())
ask("stdin _checkSeekable", lambda: stdin._checkSeekable())
ask("stdin seekable", lambda: stdin.seekable())

# The position questions, which have nothing under them to ask.
ask("seek 0", lambda: stdin.seek(0))
ask("seek 0 1", lambda: stdin.seek(0, 1))
ask("seek 0 True", lambda: stdin.seek(0, True))
ask("seek None", lambda: stdin.seek(None))
ask("seek 2**70", lambda: stdin.seek(2 ** 70))
ask("tell", lambda: stdin.tell())
ask("truncate", lambda: stdin.truncate())
ask("truncate 5", lambda: stdin.truncate(5))
ask("seek noargs", lambda: stdin.seek())
ask("seek three", lambda: stdin.seek(0, 0, 0))
ask("seek float whence", lambda: stdin.seek(0, 2.0))
ask("seek kw", lambda: stdin.seek(cookie=0))
ask("tell arg", lambda: stdin.tell(1))
ask("truncate two", lambda: stdin.truncate(1, 2))
ask("truncate kw", lambda: stdin.truncate(pos=0))
ask("seekable arg", lambda: stdin.seekable(1))

# The unpacking and the converters, which are asked before the stream is.
ask("read float", lambda: stdin.read(1.5))
ask("read kw", lambda: stdin.read(size=2))
ask("read two", lambda: stdin.read(1, 2))
ask("read 2**70", lambda: stdin.read(2 ** 70))
ask("readlines float", lambda: stdin.readlines(1.5))
ask("readlines kw", lambda: stdin.readlines(hint=2))
ask("readlines two", lambda: stdin.readlines(1, 2))
ask("readline float", lambda: stdin.readline(1.5))
ask("readline big", lambda: stdin.readline(2 ** 70))
ask("write bytes", lambda: sys.stdout.write(b"x"))
ask("write int", lambda: sys.stdout.write(1))
ask("write none", lambda: sys.stdout.write(None))
ask("write noarg", lambda: sys.stdout.write())
ask("write two", lambda: sys.stdout.write("a", "b"))
ask("write kw", lambda: sys.stdout.write(text="a"))
ask("writelines noarg", lambda: sys.stdout.writelines())
ask("writelines two", lambda: sys.stdout.writelines("a", "b"))
ask("writelines kw", lambda: sys.stdout.writelines(iterable="a"))
ask("writelines none", lambda: sys.stdout.writelines(None))
ask("checkClosed arg", lambda: stdin._checkClosed(1))
ask("checkWritable kw", lambda: stdin._checkWritable(x=1))

# Closed, and the order the refusals are reached in.
ask("close", lambda: stdin.close())
ask("closed again", lambda: stdin.close())
ask("closed read", lambda: stdin.read())
ask("closed read float", lambda: stdin.read(1.5))
ask("closed write bytes", lambda: stdin.write(b"x"))
ask("closed write none", lambda: stdin.write(None))
ask("closed write", lambda: stdin.write("x"))
ask("closed flush", lambda: stdin.flush())
ask("closed tell", lambda: stdin.tell())
ask("closed seek", lambda: stdin.seek(0))
ask("closed seek str whence", lambda: stdin.seek(0, "x"))
ask("closed truncate", lambda: stdin.truncate())
ask("closed writelines", lambda: stdin.writelines(["x"]))
ask("closed readlines", lambda: stdin.readlines())
ask("closed iter", lambda: iter(stdin))
ask("closed next", lambda: next(stdin))
ask("closed list", lambda: list(stdin))
ask("closed readable", lambda: stdin.readable())
ask("closed writable", lambda: stdin.writable())
ask("closed seekable", lambda: stdin.seekable())
ask("closed isatty", lambda: stdin.isatty())
ask("closed fileno", lambda: stdin.fileno())
ask("closed checkReadable", lambda: stdin._checkReadable())
ask("closed checkSeekable", lambda: stdin._checkSeekable())
ask("closed checkClosed", lambda: stdin._checkClosed())
ask("closed newlines", lambda: stdin.newlines)
ask("closed chunk read", lambda: stdin._CHUNK_SIZE)
ask("closed chunk write", lambda: setattr(stdin, "_CHUNK_SIZE", 100))
ask("closed chunk back", lambda: stdin._CHUNK_SIZE)

try:
    del stdin._CHUNK_SIZE
except BaseException as exc:
    print("chunk delete", type(exc).__module__ + "." + type(exc).__name__, "|", exc)
else:
    print("chunk delete None")
print("mode after", repr(stdin.mode))
print("repr", repr(stdin))
