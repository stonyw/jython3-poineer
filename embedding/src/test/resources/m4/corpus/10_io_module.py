# `_io`: the module the reference files its streams under, and the two in-memory streams that need
# no descriptor. The assembly questions are the ones this script asks — what the module holds, in
# what order, and whether the names are the same objects the builtins reach — while the two value
# types are asked about their closed state and their writes, which is where the reference's two
# different sentences show.

import _io

print("keys", list(vars(_io)))
print("basic", _io.__name__, _io.__package__, _io.__loader__.__name__,
      _io.__spec__.name, _io.__spec__.loader.__name__, _io.__spec__.origin)
print("doc", type(_io.__doc__).__name__, _io.__doc__.splitlines()[0][:40])
print("doc end", repr(_io.__doc__.splitlines()[-1]))

print("constants", _io.DEFAULT_BUFFER_SIZE, type(_io.DEFAULT_BUFFER_SIZE).__name__)
print("exceptions", _io.UnsupportedOperation.__module__, _io.UnsupportedOperation.__mro__[1].__name__,
      _io.BlockingIOError.__module__, _io.BlockingIOError.__mro__[1].__name__)

print("open", _io.open.__name__, _io.open.__module__, type(_io.open).__name__)
print("open is builtin", _io.open is open)
print("text_encoding", _io.text_encoding("utf-8"), _io.text_encoding(None))
try:
    _io.text_encoding()
except TypeError as error:
    print("encoding missing", type(error).__name__, error)
print("open_code", _io.open_code.__name__, _io.open_code.__module__)
try:
    _io.open_code()
except TypeError as error:
    print("open_code missing", type(error).__name__, error)

print("io bases", _io._IOBase.__module__, _io._TextIOBase.__mro__[1].__name__,
      _io._BufferedIOBase.__mro__[1].__name__, _io._RawIOBase.__mro__[1].__name__)
print("names only", _io.IncrementalNewlineDecoder.__name__, _io._BytesIOBuffer.__name__,
      _io.BufferedRWPair.__name__)
# `_BytesIOBuffer` is the one of the three the reference also refuses to build, and with the same words.
try:
    _io._BytesIOBuffer()
except TypeError as error:
    print("no new", type(error).__name__, error)

# --- StringIO ------------------------------------------------------------------------------------

text = _io.StringIO("one\ntwo\nthree\n")
print("text type", type(text).__name__, type(text).__module__,
      text.readable(), text.writable(), text.seekable(), text.isatty())
print("text read", repr(text.read(4)), text.tell())
print("text line", repr(text.readline()), repr(text.readline()))
print("text rest", repr(text.read()), repr(text.read()), text.tell())
print("text lines", _io.StringIO("a\nb\nc").readlines(), _io.StringIO("a\nb").readlines(2))
print("text write", _io.StringIO().write("hello"), _io.StringIO("x").getvalue())

lines = _io.StringIO()
lines.write("first\n")
lines.writelines(["second\n", "third"])
print("text written", repr(lines.getvalue()), lines.tell())
lines.seek(0)
print("text iter", list(lines), lines.tell())
print("text newline", repr(_io.StringIO("a\rb\r\nc\nd").newlines),
      repr(_io.StringIO("a\n", newline="").newlines),
      repr(_io.StringIO("").newlines))
print("text translate", repr(_io.StringIO("a\r\nb", newline=None).getvalue()),
      repr(_io.StringIO("a\r\nb", newline="\r\n").getvalue()))
print("text truncate", _io.StringIO("abcdef").truncate(3),
      _io.StringIO("abc").truncate(9), _io.StringIO("abcdef").getvalue())
print("text closed", text.closed)
text.close()
print("text after close", text.closed)
for row in ("read", "readline", "readlines", "seek", "tell", "truncate", "write", "getvalue",
            "isatty", "readable", "writable", "seekable", "flush"):
    call = {"seek": lambda: text.seek(0), "write": lambda: text.write("x")}.get(row, getattr(text, row))
    try:
        print("text closed", row, repr(call()))
    except ValueError as error:
        print("text closed", row, type(error).__name__, error)
try:
    next(text)
except ValueError as error:
    print("text closed next", type(error).__name__, error)

# --- BytesIO -------------------------------------------------------------------------------------

octets = _io.BytesIO(b"one\ntwo\nthree\n")
print("bytes type", type(octets).__name__, type(octets).__module__,
      octets.readable(), octets.writable(), octets.seekable(), octets.isatty())
print("bytes read", octets.read(4), octets.tell())
print("bytes line", octets.readline(), octets.readline())
print("bytes rest", octets.read(), octets.read(), octets.tell())
print("bytes read1", _io.BytesIO(b"abcdef").read1(2), _io.BytesIO(b"abcdef").read1())
print("bytes lines", _io.BytesIO(b"a\nb\nc").readlines(), _io.BytesIO(b"a\nb").readlines(2))
print("bytes initial", _io.BytesIO(bytearray(b"xy")).getvalue(),
      _io.BytesIO(initial_bytes=b"z").getvalue(), _io.BytesIO().getvalue())
print("bytes write", _io.BytesIO().write(b"hello"), _io.BytesIO().write(bytearray(b"ab")))

gap = _io.BytesIO(b"12345")
gap.seek(8)
print("bytes gap", gap.write(b"xy"), gap.getvalue(), gap.tell())
gap.seek(0)
print("bytes iter", list(gap), gap.tell())
print("bytes truncate", _io.BytesIO(b"abcdef").truncate(3), _io.BytesIO(b"abc").truncate(9),
      _io.BytesIO(b"abcdef").getvalue())
try:
    _io.BytesIO().write("text")
except TypeError as error:
    print("bytes wrong type", type(error).__name__, error)

octets.close()
print("bytes after close", octets.closed)
for row in ("read", "readline", "readlines", "seek", "tell", "truncate", "write", "getvalue",
            "isatty", "readable", "writable", "seekable", "flush"):
    call = {"seek": lambda: octets.seek(0),
            "write": lambda: octets.write(b"x")}.get(row, getattr(octets, row))
    try:
        print("bytes closed", row, repr(call()))
    except ValueError as error:
        print("bytes closed", row, type(error).__name__, error)
try:
    next(octets)
except ValueError as error:
    print("bytes closed next", type(error).__name__, error)
print("bytes closed iter", type(iter(octets)).__name__)

with _io.StringIO("ctx") as entered:
    print("enter", entered.read(), entered.closed)
print("exit", entered.closed)
with _io.BytesIO(b"ctx") as binary:
    print("enter bytes", binary.read(), binary.closed)
print("exit bytes", binary.closed)
