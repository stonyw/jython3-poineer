r"""The four books under every stream: what `_io` files on its bases, and what a bare base answers.

The four types are not importable here as a module, because this runtime registers them under
`_io` and builds no module of that name; the corpus reaches them the way slice 86 reached the three
layers, by walking the MRO of `sys.stdin`, its buffer and the descriptor under it. Each base keeps a
book of its own, and the books are what `vars` reads: twenty-five rows on `_IOBase`, eight on
`_TextIOBase`, five on `_RawIOBase` and seven on `_BufferedIOBase` here, one fewer each than the
reference prints because `__module__` lives on the type rather than in its dictionary in this
runtime. Every other name and every name's order is the reference's, and before anything is called
the two books are compared name by name rather than counted.

A bare base is a value with nothing but an instance dictionary: `iter` hands it back, `next` asks
its own `readline`, and the missing `read` is the AttributeError the reference raises. The refusals
are the whole of the surface, and each is worded by the row that produced it — `seek` and `truncate`
by the clinic parser, `tell` and `flush` by the legacy count, `writelines` by the one-argument row,
`readline` and `readlines` by the legacy pair, `fileno` and `detach` by the no-argument sentence,
and `__exit__` by its keyword refusal. A text base answers `None` for its three fields and refuses
to have them written, because the getsets are registered with no setter and the sentence names the
owner.

The context manager is the proof that the rows are inherited rather than copied: `with` on a bare
`_IOBase` enters on the value itself and leaves through `close`, which flushes, files the marker in
the dictionary and turns `closed` True; a second `close` answers `None`, a readline after it is the
`I/O operation on closed file` sentence, and the dictionary keeps the marker where the reference
files it. `sys.stdin` is entered and left the same way at the very end, which is why its own
`closed` reads True after the block.
"""

import sys

_ORDER = ("_IOBase", "_TextIOBase", "_RawIOBase", "_BufferedIOBase")


def base(name):
    for value in (sys.stdin, sys.stdin.buffer, sys.stdin.buffer.raw):
        for candidate in type(value).__mro__:
            if candidate.__name__ == name:
                return candidate
    raise LookupError(name)


def show(label, call):
    try:
        print(label, repr(call()))
    except BaseException as exc:
        print(label, type(exc).__module__ + "." + type(exc).__name__, "|", exc)


# The four books, name by name, with the one deviation this runtime makes.
for name in _ORDER:
    print(name, [key for key in vars(base(name)) if key != "__module__"])

# The order the streams are built in, and the three layers each belong to.
print("stdin mro", [c.__name__ for c in type(sys.stdin).__mro__])
print("buffer mro", [c.__name__ for c in type(sys.stdin.buffer).__mro__])
print("raw mro", [c.__name__ for c in type(sys.stdin.buffer.raw).__mro__])
print("stdin is io", isinstance(sys.stdin, base("_TextIOBase")))
print("stdin is base", isinstance(sys.stdin, base("_IOBase")))
print("buffer is buffered", isinstance(sys.stdin.buffer, base("_BufferedIOBase")))
print("raw is raw", isinstance(sys.stdin.buffer.raw, base("_RawIOBase")))

# A bare _IOBase: its construction, its iteration and its inherited refusals.
for label, call in (
    ("ctor positional", lambda: base("_IOBase")(1)),
    ("ctor keyword", lambda: base("_IOBase")(k=1)),
):
    show(label, call)

# Each of the four books spells its filed name in the repr, before the address no run shares.
for label in ("_IOBase", "_TextIOBase", "_RawIOBase", "_BufferedIOBase"):
    print("repr", label, repr(base(label)()).split(" at ")[0])

bare = base("_IOBase")()
print("iter is self", iter(bare) is bare)
show("next", lambda: next(bare))
show("closed", lambda: bare.closed)
show("dict", lambda: bare.__dict__)
show("seek none", lambda: bare.seek())
show("seek three", lambda: bare.seek(1, 2, 3))
show("seek text", lambda: bare.seek("x"))
show("truncate two", lambda: bare.truncate(1, 2))
show("truncate text", lambda: bare.truncate("x"))
show("tell arg", lambda: bare.tell(1))
show("flush arg", lambda: bare.flush(1))
show("seekable", lambda: bare.seekable())
show("readable", lambda: bare.readable())
show("writable", lambda: bare.writable())
show("isatty", lambda: bare.isatty())
show("fileno", lambda: bare.fileno())
show("fileno arg", lambda: bare.fileno(1))
show("readline two", lambda: bare.readline(1, 2))
show("readline", lambda: bare.readline())
show("readlines two", lambda: bare.readlines(1, 2))
show("readlines", lambda: bare.readlines())
show("writelines none", lambda: bare.writelines())
show("writelines two", lambda: bare.writelines([], 1))
show("writelines empty", lambda: bare.writelines([]))
show("checkClosed", lambda: bare._checkClosed())
show("checkSeekable", lambda: bare._checkSeekable())
show("checkReadable", lambda: bare._checkReadable())
show("checkWritable", lambda: bare._checkWritable())
show("enter is self", lambda: bare.__enter__() is bare)
show("exit keyword", lambda: bare.__exit__(1, k=2))

# A text base answers its three fields with None and refuses to have them written.
text = base("_TextIOBase")()
show("encoding", lambda: text.encoding)
show("newlines", lambda: text.newlines)
show("errors", lambda: text.errors)
show("encoding write", lambda: setattr(text, "encoding", "x"))
show("detach", lambda: text.detach())
show("detach arg", lambda: text.detach(1))
show("text read", lambda: text.read())
show("text read two", lambda: text.read(1, 2))
show("text read keyword", lambda: text.read(size=1))
show("text readline", lambda: text.readline())
show("text write none", lambda: text.write())
show("text write two", lambda: text.write("a", "b"))
show("text write int", lambda: text.write(1))
show("text write keyword", lambda: text.write(data="a"))

# A raw base refuses every body with a bare NotImplementedError, after its own parse.
raw = base("_RawIOBase")()
show("raw read", lambda: raw.read())
show("raw read two", lambda: raw.read(1, 2))
show("raw read keyword", lambda: raw.read(k=1))
show("raw readall", lambda: raw.readall())
show("raw readall arg", lambda: raw.readall(1))
show("raw readinto", lambda: raw.readinto(b"x"))
show("raw readinto keyword", lambda: raw.readinto(buffer=b"x"))
show("raw write", lambda: raw.write(b"x"))
show("raw write keyword", lambda: raw.write(obj=b"x"))

# A buffered base has no buffer protocol here, so every write into one is the same TypeError.
buffered = base("_BufferedIOBase")()
show("buffered read", lambda: buffered.read())
show("buffered read1", lambda: buffered.read1())
show("buffered read keyword", lambda: buffered.read(n=1))
show("buffered readinto", lambda: buffered.readinto(b"x"))
show("buffered readinto keyword", lambda: buffered.readinto(buffer=b"x"))
show("buffered readinto1", lambda: buffered.readinto1(b"x"))
show("buffered write none", lambda: buffered.write())
show("buffered write two", lambda: buffered.write(b"x", 1))
show("buffered write keyword", lambda: buffered.write(data=b"x"))
show("buffered detach", lambda: buffered.detach())

# A bare base is a context manager, and close files the marker the closed row reads.
with base("_IOBase")() as entered:
    print("entered open", entered.closed)
print("entered closed", entered.closed)
print("entered dict", entered.__dict__)
show("close again", lambda: entered.close())
show("readline closed", lambda: entered.readline())
show("iter closed", lambda: iter(entered))
show("enter closed", lambda: entered.__enter__())
show("flush closed", lambda: entered.flush())

# The real stream is entered and left the same way, and carries the same marker.
with sys.stdin as stream:
    print("stdin enter is self", stream is sys.stdin)
print("stdin closed", sys.stdin.closed)
