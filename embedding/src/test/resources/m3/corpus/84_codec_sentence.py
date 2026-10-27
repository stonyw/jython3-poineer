"""The five fields a codec fills when it gives up, and the sentence they are worth.

A ``UnicodeEncodeError`` a person writes by hand is one story: five arguments, an initialiser that parses them
and a ``tp_str`` that reads the struct back. A codec's failure is the same value arrived at by the other door —
CPython's ``charmap_encode`` and ``ascii_decode`` call the leaf — ``make_encode_exception`` hands it five
arguments as ``"sOnns"`` and ``PyUnicodeDecodeError_Create`` hands it the same five as ``"sy#nns"`` — so the
exception a script catches from ``'aé'.encode('ascii')`` has an ``encoding``, an ``object``, a ``start``,
an ``end`` and a ``reason`` just as though it had been constructed, and its sentence is written by that same
``UnicodeEncodeError_str``. A value that carries a message and no fields would print the empty string, which is
what ``exc->object == NULL`` is worth.

Two rules decide what the fields hold. The positions count *code points*, so an astral character is one item at
one index and not two surrogate halves. And the run is the group of consecutive items the codec cannot write,
from the first failure to the last one before an item it can: ``'aéé'`` is one error over ``1-3`` and prints
``characters in position 1-2``, while ``'éab'`` stops at ``0-1`` and never reaches the character two places
later. A decode of octets reports the one byte it stopped on, because ``ascii_decode`` fails where it fails.

The name in the sentence is the codec's own, not the spelling the call used: ``'ASCII'``, ``'ascii '`` and
``'ascii'`` all answer ``'ascii' codec can't encode``. What this case does not print is a ``utf-8`` decode
failure — this runtime reads octets as UTF-8 the way Java does and so has no strict decoder to complain — and
the substitution handlers, which belong to the codec registry at M4.
"""


def fields(error):
    return (error.encoding, error.object, error.start, error.end, error.reason)


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def caught(label, thunk):
    try:
        thunk()
    except UnicodeError as error:
        print("%s %s %r" % (label, type(error).__name__, str(error)))
        print("%s fields %r" % (label, fields(error)))
        print("%s args %r" % (label, error.args))
        print("%s dict %r" % (label, error.__dict__))
        print("%s leaf %r, base %r" % (label, isinstance(
            error, (UnicodeEncodeError, UnicodeDecodeError)), isinstance(error, UnicodeError)))
    else:
        print("%s !! nothing raised" % label)


show("encode one", lambda: "aé".encode("ascii"))
show("encode a run", lambda: "aéé".encode("ascii"))
show("encode a run then one good", lambda: "aééb".encode("ascii"))
show("encode two runs", lambda: "éaé".encode("ascii"))
show("encode astral first", lambda: "\U0001f600aé".encode("ascii"))
show("encode astral only", lambda: "\U0001f600".encode("ascii"))
show("encode cjk run", lambda: "中文".encode("ascii"))
show("encode latin1 bad", lambda: "aĀ".encode("latin-1"))
show("encode latin1 run", lambda: "aĀā".encode("latin-1"))
show("encode latin1 good", lambda: "aé".encode("latin-1"))
show("encode utf8 good", lambda: "é中".encode("utf-8"))
show("encode name upper", lambda: "é".encode("ASCII"))
show("encode name spaced", lambda: "é".encode("ascii ", "strict"))
show("decode one", lambda: b"a\xffz".decode("ascii"))
show("decode two bad", lambda: b"a\xff\xfez".decode("ascii"))
show("decode latin1 good", lambda: b"ab\xff".decode("latin-1"))
show("decode utf8 good", lambda: b"\xc3\xa9".decode("utf-8"))
show("encode empty", lambda: "".encode("ascii"))
show("unknown codec", lambda: "a".encode("no-such-codec"))

caught("one caught", lambda: "aé".encode("ascii"))
caught("run caught", lambda: "aéé".encode("ascii"))
caught("astral caught", lambda: "\U0001f600a".encode("ascii"))
caught("latin1 caught", lambda: "aĀ".encode("latin-1"))
caught("decode caught", lambda: b"a\xffz".decode("ascii"))

def raise_encode(text):
    try:
        text.encode("ascii")
    except UnicodeEncodeError as error:
        return error


def raise_decode(octets):
    try:
        octets.decode("ascii")
    except UnicodeDecodeError as error:
        return error


show("reduce of a codec error", lambda: raise_encode("aé").__reduce__())
show("rebuild it", lambda: UnicodeEncodeError(*raise_encode("aé").args))
show("rebuild says the same", lambda: str(UnicodeEncodeError(*raise_encode("aé").args)))
show("rebuild of a decode", lambda: str(UnicodeDecodeError(*raise_decode(b"a\xffz").args)))
show("str of a codec error", lambda: str(raise_encode("aéé")))
show("format of a codec error", lambda: "%s" % raise_encode("aéé"))
show("write the fields back", lambda: (lambda e: (
    setattr(e, "start", 3), setattr(e, "end", 4), str(e))[2])(raise_encode("aéé")))
show("read the object back", lambda: repr(raise_encode("aéé").object))
show("the base files none of the five", lambda: [
    name for name in ("encoding", "object", "start", "end", "reason")
    if hasattr(UnicodeError, name)])
show("which class", lambda: type(raise_encode("aé")).__name__)
show("which decode class", lambda: type(raise_decode(b"a\xff")).__name__)
