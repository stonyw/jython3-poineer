r"""The two layers under a text stream: the buffer, the descriptor, and who asks whom.

`sys.stdin.buffer` is a `_io.BufferedReader` and `buffer.raw` is an `_io.FileIO`, so the questions
here are asked of three objects over one pipe and one descriptor. None of the three keeps a state the
next one could copy: `buffer.closed`, `buffer.name` and `buffer.mode` are read off the descriptor, the
wrapper's three are read off the buffer, and the sentence a missing name answers after
`del raw.name` names `_io.FileIO` when the question was asked of `stdin` — the object that finally
looked it up. The rows go down and not up: `raw.raw`, `buffer.buffer` and `stdin.raw` are all
AttributeErrors of the object asked, and `closefd` is only ever the descriptor's own. The three
dictionaries say who owns what — `{'mode': 'r'}` on the wrapper, nothing on the buffer, and
`{'name': '<stdin>'}` on the descriptor, where the constructor filed the name rather than making it a
member — and the three reprs print the same pipe at three depths.

The store belongs to the buffer and the descriptor is the only thing that reads the pipe, which is
what makes the arithmetic worth walking. `read1(4)` asks for four and gets four; the `raw.read(4)`
after it gets the next four and no more, because one `read` is one trip to the descriptor; and
`peek(1)`, which wants the store non-empty before it answers anything, brings in the 33 octets that
were left and hands them all back. `read(3)`, `read(0)` and the filling `read1(4)` after them are cut
out of what the store already holds, so the text layer above, with `_CHUNK_SIZE` set to four octets,
reads the characters the octet-level questions left behind: `'\nf'`, then `'our\n'`, then `'fiv'`,
then `'e\nsix\nseven\neight'` — and when the pipe is dry, `read`, `readline`, `read1` and `peek`
answer `''`, `b''` and the empty list at every depth, `iter(buffer) is buffer` is True, and `next`
raises a `StopIteration` with nothing in it.

Positions are where the three disagree, because each asks in its own order. The buffered `tell` and
the descriptor's `tell` both reach the operating system, which answers `[Errno 29] Illegal seek` —
the four `builtins.OSError` rows here, one of them `raw.seek(0, 9)`, since a pipe refuses whatever it
is told to count from and the buffered layer's own `whence value 9 unsupported` is only reached by
`buffer.seek(0, 9)`. The wrapper asks `seekable` before anything else and answers
`underlying stream is not seekable`, and the buffer asks the descriptor the same question and answers
`File or stream is not seekable.`

The shapes are refused by the layer that owns the row, in the order its C function reaches them, so
the two halves of the same mistake say different things. `buffer.read('x')` is
`argument should be integer or None, not 'str'` where `buffer.read1('x')` and `buffer.peek('x')` are
`'str' object cannot be interpreted as an integer`; a keyword named at the buffer is refused by the
row's full name (`BufferedReader.read() takes no keyword arguments`) and at the descriptor by the
argument (`read() got an unexpected keyword argument 'size'`); `2 ** 70` asked of a size is an
`OverflowError` and the same number asked of `buffer.seek` is the seekable refusal, because nothing
unconverted about a position is ever read. `read(-2)` is a `ValueError` one layer up and `b''` here —
a negative is `readall()` and the descriptor was dry — and `raw.seek('x')` travels unconverted until
the body looks, which is why the same call answers a `TypeError` on an open descriptor and
`I/O operation on closed file` on a closed one while `raw.seek(0, 'x')` is the conversion in both.
Of the 313 rows, 61 are `TypeError`, 25 are `io.UnsupportedOperation`, 12 `AttributeError`, 4
`OSError`, 3 `OverflowError` and 2 the empty `StopIteration`.

Readers refuse writing in three different vocabularies. The buffer's rows, which answer the `_IOBase`
question before looking at anything, say nothing but `write` and `truncate`; the descriptor's own rows
reach the mode and say `File not open for writing`; and `raw.write('x')` is the bytes-like conversion
ahead of both, so an empty `writelines` answers `None` at both those layers — there is nothing to
write, so nothing asks — while a non-empty one refuses and a `None` argument is `'NoneType' object is
not iterable`. `readable()` is True, `writable()` and `seekable()` False at all three depths, and the
`_check` rows hand back the answer of the question they asked: `True`, not `None`, for readable, and
the dotted `File or stream is not writable.` for the other two. `fileno` is `0` three times over,
`flush` answers `None` three times over, `__enter__` hands back the object it was called on, and
`__getstate__` refuses all three in the words of the one `_IOBase` row they share — `cannot pickle
'FileIO' instances`, `'BufferedReader' instances`, `'TextIOWrapper' instances` — each naming the class
the row was reached through, and refusing an argument by that name as well.

The name is a dictionary entry, and everything above reads it through. Writing `other` rewrites all
three reprs; deleting it empties the dictionary, drops the `name='…'` half of the descriptor's repr in
favour of `fd=0`, makes the two questions answer the `AttributeError` named above, and leaves the two
reprs above printing themselves without a name — `'<_io.BufferedReader>'` and
`"<_io.TextIOWrapper mode='r' encoding='utf-8'>"`, because the attribute row the reprs use treats a
missing name and the detached `ValueError` alike. Writing the fields that are getsets refuses by the
object asked (`attribute 'mode' of '_io.BufferedReader' objects is not writable`, and the same for
`closed`, `buffer` and `__dict__`), `_finalizing` is a member and so writes True, reads True and
deletes as `can't delete numeric/char attribute`, and a value no row reads round-trips. A method
rewritten in the dictionary is the method the row then runs — `raw.readable()` False, and
`_checkReadable()` answering `File or stream is not readable.` because it asks that rewritten row
through Python — until the entry is deleted and the True comes back.

`buffer.detach()` hands the descriptor over and lets go of it, and the sentence it leaves behind,
`raw stream has been detached`, is the answer of 45 rows: 29 asked of the buffer, 16 of the wrapper
above it. It is the guard of the buffered body, so the rows inherited from `_IOBase` never reach it —
`writable()` answers False, `write(b'x')` answers `write`, `__getstate__` answers the pickle
`TypeError` and `repr` prints itself without a name, all on a buffer that refuses everything else.
`buffer.raw` is None there, `buffer.__dict__` keeps what a program filed in it, and the wrapper keeps
answering from what it cached: `seek` and `tell` still say `underlying stream is not seekable`,
`_CHUNK_SIZE` is still 4, `mode`, `encoding` and `newlines` still answer, and `stdin.buffer is buffer`
is still True. The wrapper cannot detach: `stdin.detach()` is refused with the same sentence, because
the flush it runs comes before its own attachment check — which is also why the buffer stays attached
to nothing after the descriptor is closed.

Closing the descriptor is where the two closed flags are asked apart. `_detached.close()` answers
`None` and files `__IOBase_closed` in the dictionary next to the name it still carries; `closed`
reads True and the repr collapses to `'<_io.FileIO [closed]>'`. Fifteen rows then answer
`I/O operation on closed file` — the descriptor's own check, no full stop — and nine answer the same
words with one: `readlines`, `iter`, `list`, `writelines`, `flush`, `_checkClosed` and `__enter__` are
`_IOBase` rows, as are the wrapper's `read` and `tell`, which read the descriptor's flag directly and
so notice the close that the buffer and the wrapper's other rows never see. The conversions still come
first — `write('x')`, `seek(0, 'x')` and `readinto('x')` are refused by their type on a closed
descriptor — `close()` called twice answers `None`, and `name`, `mode` and `closefd` still read.
"""

import sys

stdin = sys.stdin
buffer = sys.stdin.buffer
raw = buffer.raw


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


# The chain itself.
show("buffer type", lambda: type(buffer))
show("raw type", lambda: type(raw))
show("same buffer", lambda: sys.stdin.buffer is buffer)
show("same raw", lambda: buffer.raw is raw)
show("raw of raw", lambda: raw.raw)
show("buffer of buffer", lambda: buffer.buffer)
show("raw of wrapper", lambda: stdin.raw)
show("repr wrapper", lambda: repr(stdin))
show("repr buffer", lambda: repr(buffer))
show("repr raw", lambda: repr(raw))
show("wrapper dict", lambda: stdin.__dict__)
show("buffer dict", lambda: buffer.__dict__)
show("raw dict", lambda: raw.__dict__)
show("raw closed", lambda: raw.closed)
show("buffer closed", lambda: buffer.closed)
show("wrapper closed", lambda: stdin.closed)
show("raw mode", lambda: raw.mode)
show("buffer mode", lambda: buffer.mode)
show("wrapper mode", lambda: stdin.mode)
show("raw name", lambda: raw.name)
show("buffer name", lambda: buffer.name)
show("wrapper name", lambda: stdin.name)
show("closefd", lambda: raw.closefd)
show("buffer closefd", lambda: buffer.closefd)

# The store, and what each layer takes from it.
ask("read1 4", lambda: buffer.read1(4))
ask("raw read 4", lambda: raw.read(4))
ask("peek 1", lambda: buffer.peek(1))
ask("raw read 4 again", lambda: raw.read(4))
ask("peek 0", lambda: buffer.peek(0))
ask("read 3", lambda: buffer.read(3))
ask("read 0", lambda: buffer.read(0))
ask("read1 4 filled", lambda: buffer.read1(4))
ask("tell buffer", lambda: buffer.tell())
ask("tell raw", lambda: raw.tell())
ask("tell wrapper", lambda: stdin.tell())
ask("seek buffer", lambda: buffer.seek(0))
ask("seek raw", lambda: raw.seek(0))
ask("seek wrapper", lambda: stdin.seek(0))

# The text layer reads the same store.
ask("chunk to four", lambda: setattr(stdin, "_CHUNK_SIZE", 4))
ask("text read 2", lambda: stdin.read(2))
ask("text readline", lambda: stdin.readline())
ask("text readline 3", lambda: stdin.readline(3))
ask("text read -1", lambda: stdin.read(-1))
ask("text read rest", lambda: stdin.read())
ask("buffer read rest", lambda: buffer.read())
ask("raw read rest", lambda: raw.read())
ask("read1 at end", lambda: buffer.read1(4))
ask("peek at end", lambda: buffer.peek(1))

# Lines off each layer, once the descriptor is dry.
ask("buffer readline", lambda: buffer.readline())
ask("buffer readline 4", lambda: buffer.readline(4))
ask("buffer readlines", lambda: buffer.readlines())
ask("buffer readlines 6", lambda: buffer.readlines(6))
ask("iter buffer is self", lambda: iter(buffer) is buffer)
ask("next buffer", lambda: next(buffer))
ask("list buffer", lambda: list(buffer))
ask("raw readline", lambda: raw.readline())
ask("raw readline 4", lambda: raw.readline(4))
ask("raw readlines", lambda: raw.readlines())
ask("iter raw is self", lambda: iter(raw) is raw)
ask("next raw", lambda: next(raw))
ask("list raw", lambda: list(raw))
ask("text readline at end", lambda: stdin.readline())
ask("text readlines at end", lambda: stdin.readlines())

# The shapes each row refuses before it looks at the stream.
ask("buffer read str", lambda: buffer.read("x"))
ask("buffer read -2", lambda: buffer.read(-2))
ask("buffer read 2**70", lambda: buffer.read(2 ** 70))
ask("buffer read kw", lambda: buffer.read(size=2))
ask("buffer read two", lambda: buffer.read(1, 2))
ask("buffer read1 str", lambda: buffer.read1("x"))
ask("buffer read1 None", lambda: buffer.read1(None))
ask("buffer read1 kw", lambda: buffer.read1(n=2))
ask("buffer peek str", lambda: buffer.peek("x"))
ask("buffer peek None", lambda: buffer.peek(None))
ask("buffer peek two", lambda: buffer.peek(1, 2))
ask("buffer readline str", lambda: buffer.readline("x"))
ask("buffer readline None", lambda: buffer.readline(None))
ask("buffer readline kw", lambda: buffer.readline(size=4))
ask("buffer readlines kw", lambda: buffer.readlines(hint=6))
ask("buffer readlines two", lambda: buffer.readlines(1, 2))
ask("buffer readinto str", lambda: buffer.readinto("x"))
ask("buffer readinto bytes", lambda: buffer.readinto(b"x"))
ask("buffer readinto1 str", lambda: buffer.readinto1("x"))
ask("buffer readinto noarg", lambda: buffer.readinto())
ask("buffer readinto two", lambda: buffer.readinto(b"", 1))
ask("buffer readinto kw", lambda: buffer.readinto(buffer=b""))
ask("raw read str", lambda: raw.read("x"))
ask("raw read -2", lambda: raw.read(-2))
ask("raw read 2**70", lambda: raw.read(2 ** 70))
ask("raw read kw", lambda: raw.read(size=2))
ask("raw read two", lambda: raw.read(1, 2))
ask("raw read noarg", lambda: raw.read())
ask("raw readall arg", lambda: raw.readall(1))
ask("raw readline str", lambda: raw.readline("x"))
ask("raw readline 2**70", lambda: raw.readline(2 ** 70))
ask("raw readinto str", lambda: raw.readinto("x"))
ask("raw readinto bytes", lambda: raw.readinto(b"x"))
ask("raw readinto kw", lambda: raw.readinto(buffer=b""))
ask("raw write str", lambda: raw.write("x"))
ask("raw write two", lambda: raw.write(b"x", 1))
ask("raw write kw", lambda: raw.write(obj=b"x"))
ask("raw seek str", lambda: raw.seek("x"))
ask("raw seek str whence", lambda: raw.seek(0, "x"))
ask("raw seek 9", lambda: raw.seek(0, 9))
ask("raw seek noarg", lambda: raw.seek())
ask("raw seek kw", lambda: raw.seek(pos=0))
ask("raw seek two", lambda: raw.seek(0, 0, 0))
ask("raw tell arg", lambda: raw.tell(1))
ask("raw truncate str", lambda: raw.truncate("x"))
ask("raw truncate kw", lambda: raw.truncate(pos=0))
ask("raw truncate two", lambda: raw.truncate(1, 2))
ask("buffer seek str", lambda: buffer.seek("x"))
ask("buffer seek 9", lambda: buffer.seek(0, 9))
ask("buffer seek 3", lambda: buffer.seek(0, 3))
ask("buffer seek 4", lambda: buffer.seek(0, 4))
ask("buffer seek 2**70", lambda: buffer.seek(2 ** 70))
ask("buffer seek noarg", lambda: buffer.seek())
ask("buffer seek three", lambda: buffer.seek(0, 0, 0))
ask("buffer seek kw", lambda: buffer.seek(pos=0))
ask("buffer truncate str", lambda: buffer.truncate("x"))
ask("buffer truncate kw", lambda: buffer.truncate(pos=0))
ask("buffer truncate two", lambda: buffer.truncate(1, 2))
ask("buffer tell arg", lambda: buffer.tell(1))

# The writing rows of readers.
ask("buffer write", lambda: buffer.write(b"x"))
ask("buffer writelines", lambda: buffer.writelines([b"x"]))
ask("buffer writelines empty", lambda: buffer.writelines([]))
ask("buffer writelines none", lambda: buffer.writelines(None))
ask("buffer writelines two", lambda: buffer.writelines([b"x"], 1))
ask("buffer truncate", lambda: buffer.truncate())
ask("raw truncate", lambda: raw.truncate())
ask("raw write", lambda: raw.write(b"x"))
ask("raw writelines", lambda: raw.writelines([b"x"]))
ask("raw writelines empty", lambda: raw.writelines([]))

# The questions each layer asks itself.
ask("raw readable", lambda: raw.readable())
ask("raw writable", lambda: raw.writable())
ask("raw seekable", lambda: raw.seekable())
ask("buffer readable", lambda: buffer.readable())
ask("buffer writable", lambda: buffer.writable())
ask("buffer seekable", lambda: buffer.seekable())
ask("wrapper readable", lambda: stdin.readable())
ask("wrapper writable", lambda: stdin.writable())
ask("wrapper seekable", lambda: stdin.seekable())
ask("raw checkReadable", lambda: raw._checkReadable())
ask("raw checkWritable", lambda: raw._checkWritable())
ask("raw checkSeekable", lambda: raw._checkSeekable())
ask("raw checkClosed", lambda: raw._checkClosed())
ask("buffer checkReadable", lambda: buffer._checkReadable())
ask("buffer checkWritable", lambda: buffer._checkWritable())
ask("buffer checkSeekable", lambda: buffer._checkSeekable())
ask("buffer checkClosed", lambda: buffer._checkClosed())
ask("wrapper checkReadable", lambda: stdin._checkReadable())
ask("wrapper checkWritable", lambda: stdin._checkWritable())
ask("wrapper checkSeekable", lambda: stdin._checkSeekable())
ask("raw isatty", lambda: raw.isatty())
ask("buffer isatty", lambda: buffer.isatty())
ask("wrapper isatty", lambda: stdin.isatty())
ask("raw fileno", lambda: raw.fileno())
ask("buffer fileno", lambda: buffer.fileno())
ask("wrapper fileno", lambda: stdin.fileno())
ask("raw flush", lambda: raw.flush())
ask("buffer flush", lambda: buffer.flush())
ask("wrapper flush", lambda: stdin.flush())
ask("raw flush arg", lambda: raw.flush(1))
ask("buffer fileno arg", lambda: buffer.fileno(1))
ask("raw getstate", lambda: raw.__getstate__())
ask("buffer getstate", lambda: buffer.__getstate__())
ask("wrapper getstate", lambda: stdin.__getstate__())
ask("wrapper getstate arg", lambda: stdin.__getstate__(1))
ask("wrapper getstate kw", lambda: stdin.__getstate__(x=1))
ask("raw dealloc warn", lambda: raw._dealloc_warn(True))
ask("raw dealloc warn noarg", lambda: raw._dealloc_warn())
ask("buffer dealloc warn", lambda: buffer._dealloc_warn(True))
ask("raw enter", lambda: raw.__enter__() is raw)
ask("buffer enter", lambda: buffer.__enter__() is buffer)
ask("wrapper enter", lambda: stdin.__enter__() is stdin)
ask("raw enter arg", lambda: raw.__enter__(1))

# The name the constructor filed in the dictionary.
ask("raw name write", lambda: setattr(raw, "name", "other"))
ask("raw dict after name", lambda: raw.__dict__)
ask("repr raw renamed", lambda: repr(raw))
ask("buffer name forwarded", lambda: buffer.name)
ask("wrapper name forwarded", lambda: stdin.name)
ask("raw name delete", lambda: delattr(raw, "name"))
ask("raw dict deleted", lambda: raw.__dict__)
ask("repr raw no name", lambda: repr(raw))
ask("buffer name gone", lambda: buffer.name)
ask("wrapper name gone", lambda: stdin.name)
ask("repr buffer no name", lambda: repr(buffer))
ask("repr wrapper no name", lambda: repr(stdin))
ask("raw name back", lambda: setattr(raw, "name", "restored"))
ask("repr raw back", lambda: repr(raw))
ask("raw dict write", lambda: setattr(raw, "__dict__", {}))
ask("raw closed write", lambda: setattr(raw, "closed", False))
ask("raw mode write", lambda: setattr(raw, "mode", "wb"))
ask("buffer mode write", lambda: setattr(buffer, "mode", "wb"))
ask("buffer closed write", lambda: setattr(buffer, "closed", False))
ask("wrapper buffer write", lambda: setattr(stdin, "buffer", raw))
ask("raw finalizing", lambda: raw._finalizing)
ask("raw finalizing write", lambda: setattr(raw, "_finalizing", True))
ask("raw finalizing now", lambda: raw._finalizing)
ask("raw finalizing delete", lambda: delattr(raw, "_finalizing"))
ask("buffer slot write", lambda: setattr(buffer, "_NOT_A_ROW", 1))
ask("buffer slot read", lambda: buffer._NOT_A_ROW)
ask("raw method write", lambda: setattr(raw, "readable", lambda: False))
ask("raw readable now", lambda: raw.readable())
ask("raw checkReadable now", lambda: raw._checkReadable())
ask("raw dict keys now", lambda: sorted(raw.__dict__))
ask("raw method delete", lambda: delattr(raw, "readable"))
ask("raw readable back", lambda: raw.readable())

# The buffer lets go of the descriptor.
_detached = buffer.detach()
show("detached is raw", lambda: _detached is raw)
show("detached type", lambda: type(_detached))
ask("buffer raw", lambda: buffer.raw)
ask("buffer name detached", lambda: buffer.name)
ask("buffer mode detached", lambda: buffer.mode)
ask("buffer closed detached", lambda: buffer.closed)
ask("buffer readable detached", lambda: buffer.readable())
ask("buffer writable detached", lambda: buffer.writable())
ask("buffer seekable detached", lambda: buffer.seekable())
ask("buffer fileno detached", lambda: buffer.fileno())
ask("buffer isatty detached", lambda: buffer.isatty())
ask("buffer tell detached", lambda: buffer.tell())
ask("buffer flush detached", lambda: buffer.flush())
ask("buffer read detached", lambda: buffer.read())
ask("buffer read1 detached", lambda: buffer.read1())
ask("buffer peek detached", lambda: buffer.peek())
ask("buffer readline detached", lambda: buffer.readline())
ask("buffer readlines detached", lambda: buffer.readlines())
ask("buffer write detached", lambda: buffer.write(b"x"))
ask("buffer writelines detached", lambda: buffer.writelines([b"x"]))
ask("buffer truncate detached", lambda: buffer.truncate())
ask("buffer seek detached", lambda: buffer.seek(0))
ask("buffer close detached", lambda: buffer.close())
ask("buffer detach again", lambda: buffer.detach())
ask("buffer enter detached", lambda: buffer.__enter__())
ask("buffer iter detached", lambda: iter(buffer))
ask("buffer next detached", lambda: next(buffer))
ask("buffer checkClosed detached", lambda: buffer._checkClosed())
ask("buffer checkReadable detached", lambda: buffer._checkReadable())
ask("buffer getstate detached", lambda: buffer.__getstate__())
ask("buffer repr detached", lambda: repr(buffer))
ask("buffer dict detached", lambda: buffer.__dict__)
ask("wrapper read after buffer detach", lambda: stdin.read())
ask("wrapper readline after", lambda: stdin.readline())
ask("wrapper name after", lambda: stdin.name)
ask("wrapper closed after", lambda: stdin.closed)
ask("wrapper readable after", lambda: stdin.readable())
ask("wrapper writable after", lambda: stdin.writable())
ask("wrapper seekable after", lambda: stdin.seekable())
ask("wrapper seek after", lambda: stdin.seek(0))
ask("wrapper tell after", lambda: stdin.tell())
ask("wrapper fileno after", lambda: stdin.fileno())
ask("wrapper isatty after", lambda: stdin.isatty())
ask("wrapper flush after", lambda: stdin.flush())
ask("wrapper truncate after", lambda: stdin.truncate())
ask("wrapper chunk after", lambda: stdin._CHUNK_SIZE)
ask("wrapper newlines after", lambda: stdin.newlines)
ask("wrapper mode after", lambda: stdin.mode)
ask("wrapper encoding after", lambda: stdin.encoding)
ask("wrapper iter after", lambda: iter(stdin))
ask("wrapper next after", lambda: next(stdin))
ask("wrapper list after", lambda: list(stdin))
ask("wrapper repr after", lambda: repr(stdin))
ask("wrapper buffer after", lambda: stdin.buffer is buffer)

# The wrapper cannot hand over a buffer that has already let go of its descriptor.
ask("wrapper detach after", lambda: stdin.detach())
ask("wrapper buffer still", lambda: stdin.buffer is buffer)
ask("wrapper repr still", lambda: repr(stdin))

# Closing reaches the descriptor, and everyone reads its flag.
ask("detached close", lambda: _detached.close())
ask("detached dict", lambda: _detached.__dict__)
ask("detached closed", lambda: _detached.closed)
ask("detached repr", lambda: repr(_detached))
ask("detached read", lambda: _detached.read(8))
ask("detached readall", lambda: _detached.readall())
ask("detached readline", lambda: _detached.readline())
ask("detached readlines", lambda: _detached.readlines())
ask("detached iter", lambda: iter(_detached))
ask("detached next", lambda: next(_detached))
ask("detached list", lambda: list(_detached))
ask("detached write", lambda: _detached.write(b"x"))
ask("detached write str", lambda: _detached.write("x"))
ask("detached writelines", lambda: _detached.writelines([b"x"]))
ask("detached truncate", lambda: _detached.truncate())
ask("detached truncate str", lambda: _detached.truncate("x"))
ask("detached seek", lambda: _detached.seek(0))
ask("detached seek str", lambda: _detached.seek("x"))
ask("detached seek str whence", lambda: _detached.seek(0, "x"))
ask("detached tell", lambda: _detached.tell())
ask("detached flush", lambda: _detached.flush())
ask("detached closed row", lambda: _detached._checkClosed())
ask("detached readable", lambda: _detached.readable())
ask("detached writable", lambda: _detached.writable())
ask("detached seekable", lambda: _detached.seekable())
ask("detached isatty", lambda: _detached.isatty())
ask("detached fileno", lambda: _detached.fileno())
ask("detached name", lambda: _detached.name)
ask("detached mode", lambda: _detached.mode)
ask("detached closefd", lambda: _detached.closefd)
ask("detached enter", lambda: _detached.__enter__())
ask("detached exit", lambda: _detached.__exit__())
ask("detached close again", lambda: _detached.close())
ask("detached readinto", lambda: _detached.readinto("x"))
ask("buffer repr closed", lambda: repr(buffer))
ask("buffer read closed", lambda: buffer.read())
ask("buffer peek closed", lambda: buffer.peek())
ask("buffer readline closed", lambda: buffer.readline())
ask("buffer detach closed", lambda: buffer.detach())
ask("buffer close again", lambda: buffer.close())
ask("wrapper closed by raw", lambda: stdin.closed)
ask("wrapper repr closed", lambda: repr(stdin))
ask("wrapper read closed", lambda: stdin.read())
ask("wrapper tell closed", lambda: stdin.tell())
ask("wrapper chunk closed", lambda: stdin._CHUNK_SIZE)
ask("wrapper chunk write closed", lambda: setattr(stdin, "_CHUNK_SIZE", 100))
ask("wrapper newlines closed", lambda: stdin.newlines)
ask("wrapper close after raw", lambda: stdin.close())
ask("wrapper close arg", lambda: stdin.close(1))
