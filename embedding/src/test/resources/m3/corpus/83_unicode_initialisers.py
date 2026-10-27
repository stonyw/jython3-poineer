"""The three tables the Unicode leaves parse, and the six sentences they are worth.

``UnicodeEncodeError("utf-8", "é", 0, 1, "why")`` takes ``BaseException_init`` on its way in — as every class of
the family does, which is why a refused call still keeps the arguments it was given — and then reads its own
``PyArg_ParseTuple`` table, and the table is the whole difference between them: ``UUnnU`` for the encode,
``UOnnU`` for the decode — whose second converter is a bare object, turned into octets only after the five are
read — and ``UnnU`` for the translate, which has no codec to name. The arity follows from the table, so five is
exact for two of them and four for the third, and a sixth argument is refused as ``function takes exactly 5
arguments (6 given)`` in the words ``PyArg_ParseTuple`` reserves for a count, not in the words of the class. No
keywords are read at all: ``UnicodeEncodeError(encoding="utf-8")`` is refused in the class's own words, because
the refusal of a keyword is ``BaseException_init``'s and that half knows which class it was called for.

Each converter refuses in its own words. ``'U'`` wants a ``str`` and says ``argument 1 must be str, not
bytes``, naming the position of the argument and leaving the type unquoted — a ``bytes`` handed where a codec
name belongs is refused as the number one, not as the name. ``'n'`` wants a ``Py_ssize_t`` and answers a float
with ``'float' object cannot be interpreted as an integer``, which is ``PyLong_AsSsize_t``'s own sentence and
therefore says nothing about positions. The decode's ``'O'`` takes anything, so the object a decode is handed
survives the parse and is complained about afterwards, by ``PyObject_GetBuffer``: a ``str`` handed to a decode
is refused as ``a bytes-like object is required, not 'str'`` while the same ``str`` handed to an encode is kept
as the object to be encoded. Because that check is last, a float in the ``start`` of a call whose object is a
string is complained about as an index and never reaches the buffer. The translate leaf reads its object with
``'U'``, which is why ``UnicodeTranslateError(b"x", 0, 1, "w")`` is refused as the first argument rather than
accepted as a buffer.

The display is where the five fields come back, and it reads three of them before it looks at the fourth. The
``reason`` and the ``encoding`` are both ``str``'d first — a person may have written anything into either, and
the sentence prints whatever ``__str__`` answers, including ``<NULL>`` for a field that was never filled at all
by a value made with ``__new__``. Only then is the ``object`` checked, which is why a value whose ``object``
was replaced with the wrong kind of sequence is refused with the class named as ``UnicodeError``, the base that
owns the check, and not as the leaf the value is; and why a broken ``__str__`` on the ``reason`` carries that
failure out of ``str(e)`` before anything is said about the object. An ``object`` that is ``NULL``, or one that
was deleted, is worth the empty string, and the empty string is what makes ``str(e)`` of a value made by
``__new__`` alone print nothing at all.

What a range is worth then depends on the gate: ``(start >= 0 && start < len) && (end >= 0 && end <= len) &&
end == start + 1``. Inside it, the sentence names the one item — a character as a backslash-x, backslash-u or
backslash-U escape by its width, or an octet as ``0x..`` — and outside it the sentence names both ends, the
second of them printed as one less than the field holds. A range of no items at all therefore prints its two
positions crossed over, ``position 1-0``, and a negative one prints as it was written.

What this case does not print is a ``bytearray`` or a ``memoryview`` handed to a decode as the object, which the
buffer converter accepts and this runtime has no type for, and the real codec failure that puts the whole
unconvertible text in the ``object``.
"""


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def fields(e):
    return (e.encoding, e.object, e.start, e.end, e.reason)


ENCODE = UnicodeEncodeError("utf-8", "é", 0, 1, "why")
DECODE = UnicodeDecodeError("utf-8", b"x\xffz", 1, 2, "nope")
TRANSLATE = UnicodeTranslateError("éx", 0, 2, "nope")

show("encode fields", lambda: fields(ENCODE))
show("decode fields", lambda: fields(DECODE))
show("translate fields", lambda: fields(TRANSLATE))
show("encode args", lambda: ENCODE.args)
show("decode args", lambda: DECODE.args)
show("translate args", lambda: TRANSLATE.args)
show("encode repr", lambda: repr(ENCODE))
show("encode str", lambda: str(ENCODE))
show("decode str", lambda: str(DECODE))
show("translate str", lambda: str(TRANSLATE))
show("reduce of an encode", lambda: ENCODE.__reduce__())
show("dict stays empty", lambda: ENCODE.__dict__)

show("encode no arguments", lambda: UnicodeEncodeError())
show("encode one", lambda: UnicodeEncodeError("utf-8"))
show("encode six", lambda: UnicodeEncodeError("utf-8", "é", 0, 1, "why", 7))
show("encode eight", lambda: UnicodeEncodeError("utf-8", "é", 0, 1, "why", 7, 8, 9))
show("decode four", lambda: UnicodeDecodeError("utf-8", b"x", 1))
show("decode six", lambda: UnicodeDecodeError("utf-8", b"x", 1, 2, "r", 6))
show("translate three", lambda: UnicodeTranslateError("x", 0, 1))
show("translate five", lambda: UnicodeTranslateError("x", 0, 1, "r", 5))
show("encode a keyword", lambda: UnicodeEncodeError(encoding="utf-8"))
show("encode after five a keyword", lambda: UnicodeEncodeError("utf-8", "é", 0, 1, "why", extra=1))
show("decode a keyword", lambda: UnicodeDecodeError(object=b"x"))
show("translate a keyword", lambda: UnicodeTranslateError(reason="r"))
show("encode no keywords at all", lambda: UnicodeEncodeError(**{}))
show("base takes anything", lambda: fields(UnicodeError("utf-8", b"a", 0, 1, "r")))
show("base of nothing", lambda: str(UnicodeError()))

show("encoding a bytes", lambda: UnicodeEncodeError(b"utf-8", "é", 0, 1, "why"))
show("encoding a number", lambda: UnicodeEncodeError(8, "é", 0, 1, "why"))
show("encoding none", lambda: UnicodeEncodeError(None, "é", 0, 1, "why"))
show("object a bytes", lambda: UnicodeEncodeError("utf-8", b"\xe9", 0, 1, "why"))
show("object a number", lambda: UnicodeEncodeError("utf-8", 5, 0, 1, "why"))
show("start a float", lambda: UnicodeEncodeError("utf-8", "é", 0.5, 1, "why"))
show("start a str", lambda: UnicodeEncodeError("utf-8", "é", "0", 1, "why"))
show("start two to the eighty", lambda: UnicodeEncodeError("utf-8", "é", 2**80, 1, "why"))
show("start a bool", lambda: (lambda e: (e.start, e.end))(UnicodeEncodeError("utf-8", "ab", True, 2, "r")))
show("end two to the eighty", lambda: UnicodeEncodeError("utf-8", "é", 0, 2**80, "why"))
show("reason a number", lambda: UnicodeEncodeError("utf-8", "é", 0, 1, 7))
show("decode object a str", lambda: UnicodeDecodeError("utf-8", "x", 0, 1, "w"))
show("decode object a list", lambda: UnicodeDecodeError("utf-8", [1], 0, 1, "w"))
show("decode object a number", lambda: UnicodeDecodeError("utf-8", 5, 0, 1, "w"))
show("decode object none", lambda: UnicodeDecodeError("utf-8", None, 0, 1, "w"))
show("decode object bytes", lambda: (lambda e: e.object)(UnicodeDecodeError("utf-8", b"ab\xff", 2, 3, "w")))
show("translate object a bytes", lambda: UnicodeTranslateError(b"x", 0, 1, "w"))
show("translate start a float", lambda: UnicodeTranslateError("x", 0.5, 1, "w"))

show("one character", lambda: str(UnicodeEncodeError("ascii", "a", 0, 1, "nope")))
show("eight bits", lambda: str(UnicodeEncodeError("ascii", "é", 0, 1, "nope")))
show("sixteen bits", lambda: str(UnicodeEncodeError("ascii", "℘", 0, 1, "nope")))
show("thirty two bits", lambda: str(UnicodeEncodeError("ascii", "\U0001F600", 0, 1, "nope")))
show("two characters", lambda: str(UnicodeEncodeError("ascii", "ab", 0, 2, "nope")))
show("whole text", lambda: str(UnicodeEncodeError("ascii", "ab", 0, 2, "nope")))
show("empty range", lambda: str(UnicodeEncodeError("ascii", "ab", 1, 1, "nope")))
show("start past the end", lambda: str(UnicodeEncodeError("ascii", "ab", 2, 3, "nope")))
show("end past the end", lambda: str(UnicodeEncodeError("ascii", "ab", 0, 9, "nope")))
show("both negative", lambda: str(UnicodeEncodeError("ascii", "ab", -3, -1, "nope")))
show("negative one long", lambda: str(UnicodeEncodeError("ascii", "ab", -1, 0, "nope")))
show("empty object", lambda: str(UnicodeEncodeError("ascii", "", 0, 1, "nope")))
show("no range at all", lambda: str(UnicodeEncodeError("ascii", "ab", 0, 0, "nope")))
show("one byte", lambda: str(UnicodeDecodeError("utf-8", b"x\xffz", 1, 2, "nope")))
show("two bytes", lambda: str(UnicodeDecodeError("utf-8", b"x\xffz", 1, 3, "nope")))
show("zero byte", lambda: str(UnicodeDecodeError("utf-8", b"\x00", 0, 1, "nope")))
show("high byte", lambda: str(UnicodeDecodeError("utf-8", b"\xff", 0, 1, "nope")))
show("byte range crossed", lambda: str(UnicodeDecodeError("utf-8", b"abc", 1, 1, "nope")))
show("translate one", lambda: str(UnicodeTranslateError("é", 0, 1, "nope")))
show("translate two", lambda: str(UnicodeTranslateError("éx", 0, 2, "nope")))
show("translate negative", lambda: str(UnicodeTranslateError("éx", -2, -1, "nope")))

show("encoding written as none", lambda: (lambda e: (setattr(e, "encoding", None), str(e))[1])(UnicodeEncodeError("ascii", "a", 0, 1, "n")))
show("reason written as a number", lambda: (lambda e: (setattr(e, "reason", 7), str(e))[1])(UnicodeEncodeError("ascii", "a", 0, 1, "n")))
show("encoding written as a pair", lambda: (lambda e: (setattr(e, "encoding", (1, 2)), str(e))[1])(UnicodeEncodeError("ascii", "a", 0, 1, "n")))
show("object written the wrong way", lambda: (lambda e: (setattr(e, "object", b"z"), str(e))[1])(UnicodeEncodeError("ascii", "a", 0, 1, "n")))
show("decode object written as text", lambda: (lambda e: (setattr(e, "object", "s"), str(e))[1])(UnicodeDecodeError("utf-8", b"a", 0, 1, "n")))
show("translate encoding never filled", lambda: (lambda e: (e.encoding, str(e)))(UnicodeTranslateError("a", 0, 1, "n")))
show("object deleted then printed", lambda: str((lambda e: (delattr(e, "object"), e)[1])(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r"))))
show("reason deleted then printed", lambda: str((lambda e: (delattr(e, "reason"), e)[1])(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r"))))
show("encoding deleted then printed", lambda: str((lambda e: (delattr(e, "encoding"), e)[1])(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r"))))
show("start written past the text", lambda: (lambda e: (setattr(e, "start", 5), str(e))[1])(UnicodeDecodeError("utf-8", b"abc", 1, 2, "r")))


class Broken:
    def __str__(self):
        raise RuntimeError("no text")


show("reason that cannot be said", lambda: str((lambda e: (setattr(e, "reason", Broken()), str(e))[1])(UnicodeEncodeError("ascii", "a", 0, 1, "n"))))
show("encoding that cannot be said", lambda: str((lambda e: (setattr(e, "encoding", Broken()), str(e))[1])(UnicodeEncodeError("ascii", "a", 0, 1, "n"))))
show("leaf str row called", lambda: UnicodeEncodeError.__dict__["__str__"](ENCODE))
show("leaf str of another leaf", lambda: UnicodeEncodeError.__dict__["__str__"](DECODE))
show("init row of an int", lambda: UnicodeEncodeError.__dict__["__init__"](3, "utf-8", "a", 0, 1, "r"))
show("subclass keeps the sentences", lambda: (lambda k: (k("utf-8", b"a", 0, 1, "r").start, str(k("utf-8", b"a", 0, 1, "r"))))(type("SD", (UnicodeDecodeError,), {})))
