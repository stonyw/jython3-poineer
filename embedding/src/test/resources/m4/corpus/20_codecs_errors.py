# The error-handler registry: the eight names `_codecs` has always answered for, and the one table a
# program writes into with `register_error`. A handler is a function of one argument -- the exception a
# codec raised -- answering `(replacement, new_position)`, and which of the three Unicode errors it is
# handed is what decides the answer: a run of octets has no width to spend, so `replace` answers one
# U+FFFD when decoding and one '?' per character when encoding. `codecs.py:1114` is the first caller in
# the stdlib: it asks for six of these by name at import time.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import _codecs

NAMES = ["strict", "ignore", "replace", "xmlcharrefreplace", "backslashreplace",
         "namereplace", "surrogateescape", "surrogatepass"]

handlers = {name: _codecs.lookup_error(name) for name in NAMES}

# --- the eight as objects ------------------------------------------------------------------


for name in NAMES:
    handler = handlers[name]
    print("handler", name, handler.__name__, handler.__qualname__, handler.__module__, handler.__self__)
print("stable", all(_codecs.lookup_error(name) is handlers[name] for name in NAMES))
print("docs", [handlers[name].__doc__ is not None for name in NAMES])
print("repr", repr(handlers["ignore"]), repr(handlers["surrogatepass"]))

# --- the three shapes, handler by handler --------------------------------------------------


def run(name, exc):
    try:
        print(name, "->", handlers[name](exc))
    except BaseException as error:
        print(name, "!!", type(error).__name__, error)


def encode(text, start, stop):
    return UnicodeEncodeError("ascii", text, start, stop, "ordinal not in range(128)")


def decode(data, start, stop):
    return UnicodeDecodeError("utf-8", data, start, stop, "invalid start byte")


def translate(text, start, stop):
    return UnicodeTranslateError(text, start, stop, "character maps to <undefined>")


print("--- encode")
for name in NAMES:
    run(name, encode("a\u00e9", 1, 2))

print("--- decode")
for name in NAMES:
    run(name, decode(b"a\xff", 1, 2))

print("--- translate")
for name in NAMES:
    run(name, translate("a\n", 1, 2))

# --- what each shape spends its run on -----------------------------------------------------

print("--- replace")
run("replace", encode("\u00e9\u00e9", 0, 2))
run("replace", decode(b"\xff\xfe\xfd", 0, 3))
run("replace", decode(b"\xff\xfe\xfd", 1, 2))
run("replace", translate("\u00e9\u00e9", 0, 2))

print("--- ignore")
run("ignore", encode("\u00e9\u00e9", 0, 2))
run("ignore", decode(b"\xff\xfe", 0, 2))
run("ignore", translate("\u00e9", 0, 1))

print("--- xmlcharrefreplace")
run("xmlcharrefreplace", encode("\u00e9\u20ac\U0001f600", 0, 3))
run("xmlcharrefreplace", translate("\u00e9", 0, 1))

print("--- backslashreplace")
run("backslashreplace", encode("\x00\u00e9\u20ac\U0001f600", 0, 4))
run("backslashreplace", encode("\n\\'", 0, 3))
run("backslashreplace", decode(b"\x00\x80\xff", 0, 3))
run("backslashreplace", decode(b"\xff\xfe", 1, 2))
run("backslashreplace", translate("\u00e9\u20ac", 0, 2))

print("--- namereplace")
run("namereplace", encode("\x00\u00e9\ue000\U0001f600", 0, 4))
run("namereplace", encode("a\ud800", 1, 2))
run("namereplace", decode(b"\xff", 0, 1))

print("--- surrogateescape")
run("surrogateescape", encode("a\udc80\udcff", 1, 3))
run("surrogateescape", encode("\udc80\udcff", 0, 2))
run("surrogateescape", encode("\udc80x", 0, 2))
run("surrogateescape", decode(b"\xff\xfe", 0, 2))
run("surrogateescape", decode(b"a\x80", 1, 2))
run("surrogateescape", decode(b"\xff\xff\xff\xff\xff", 0, 5))
run("surrogateescape", decode(b"\x7f\xff", 0, 2))
run("surrogateescape", translate("\udc80", 0, 1))

print("--- surrogatepass")


def attempt(name, make):
    try:
        print(name, "->", handlers[name](make()))
    except BaseException as error:
        print(name, "!!", type(error).__name__, error)


attempt("surrogatepass", lambda: UnicodeEncodeError("utf-8", "\ud800", 0, 1, "surrogates not allowed"))
attempt("surrogatepass", lambda: UnicodeEncodeError("utf-16-le", "\ud800", 0, 1, "surrogates not allowed"))
attempt("surrogatepass", lambda: UnicodeEncodeError("utf-32-be", "\ud83d", 0, 1, "surrogates not allowed"))
attempt("surrogatepass", lambda: UnicodeEncodeError("utf-8-sig", "\ud800", 0, 1, "surrogates not allowed"))
attempt("surrogatepass", lambda: UnicodeEncodeError("ascii", "\ud800", 0, 1, "ordinal not in range(128)"))
attempt("surrogatepass", lambda: UnicodeDecodeError("utf-8", b"\xed\xa0\x80", 0, 3, "invalid continuation byte"))
attempt("surrogatepass", lambda: UnicodeDecodeError("utf-16-be", b"\xd8\x00", 0, 2, "invalid continuation byte"))
attempt("surrogatepass", lambda: UnicodeDecodeError("utf-32-le", b"\x00\xd8\x00\x00", 0, 4, "invalid continuation byte"))
attempt("surrogatepass", lambda: UnicodeDecodeError("utf-8", b"\xed\xa0", 0, 2, "invalid continuation byte"))
attempt("surrogatepass", lambda: UnicodeDecodeError("latin-1", b"\x80", 0, 1, "ordinal not in range(128)"))
attempt("surrogatepass", lambda: translate("\ud800", 0, 1))

# --- what a handler refuses to be handed ---------------------------------------------------

print("--- not an exception")
for name in NAMES:
    run(name, 1)
run("ignore", None)
run("replace", b"abc")
run("strict", ValueError("boom"))

print("--- arity and keywords")
for label, call in [
    ("arity0", lambda: handlers["ignore"]()),
    ("arity2", lambda: handlers["ignore"](1, 2)),
    ("keyword", lambda: handlers["ignore"](exc=None)),
    ("strict arity", lambda: handlers["strict"]()),
]:
    try:
        call()
    except BaseException as error:
        print(label, type(error).__name__, error)

# --- the table a program writes into -------------------------------------------------------


def mine(error):
    return ("!", error.end)


print("register", _codecs.register_error("mine", mine))
print("registered", _codecs.lookup_error("mine") is mine)
print("unregister", _codecs._unregister_error("mine"), _codecs._unregister_error("mine"))

try:
    _codecs.lookup_error("mine")
except LookupError as error:
    print("gone", type(error).__name__, error)

print("shadow", _codecs.register_error("ignore", mine))
print("shadowed", _codecs.lookup_error("ignore") is mine)

try:
    _codecs._unregister_error("ignore")
except ValueError as error:
    print("builtin", type(error).__name__, error)

print("kept", _codecs.lookup_error("ignore") is mine)

# The name is filed exactly as written, so these are names of their own.
_codecs.register_error("IGNORE", mine)
print("case", _codecs.lookup_error("IGNORE") is mine, _codecs.lookup_error("ignore") is mine)

_codecs.register_error("ignore", handlers["ignore"])
_codecs._unregister_error("IGNORE")
print("restored", _codecs.lookup_error("ignore").__name__)

print("--- refusals")
for label, call in [
    ("lookup0", lambda: _codecs.lookup_error()),
    ("lookup2", lambda: _codecs.lookup_error("a", "b")),
    ("lookup keyword", lambda: _codecs.lookup_error(name="strict")),
    ("lookup type", lambda: _codecs.lookup_error(1)),
    ("register0", lambda: _codecs.register_error()),
    ("register1", lambda: _codecs.register_error("x")),
    ("register3", lambda: _codecs.register_error("a", 1, 2)),
    ("register keyword", lambda: _codecs.register_error(name="x", handler=1)),
    ("register name", lambda: _codecs.register_error(None, 1)),
    ("register bytes", lambda: _codecs.register_error(b"x", 1)),
    ("register handler", lambda: _codecs.register_error("x", 1)),
    ("unregister0", lambda: _codecs._unregister_error()),
    ("unregister keyword", lambda: _codecs._unregister_error(name="x")),
    ("unregister type", lambda: _codecs._unregister_error(b"x")),
]:
    try:
        call()
    except BaseException as error:
        print(label, type(error).__name__, error)

for bad in ["STRICT", "surrogate-escape", "z" * 405]:
    try:
        _codecs.lookup_error(bad)
    except LookupError as error:
        print("unknown", len(str(error)), str(error)[:40], str(error)[-12:])

# --- the codecs module the six names are assigned in ---------------------------------------

import codecs

print("codecs", codecs.strict_errors is _codecs.lookup_error("strict"),
      codecs.ignore_errors is _codecs.lookup_error("ignore"),
      codecs.namereplace_errors is _codecs.lookup_error("namereplace"))
print("shared", codecs.register_error is _codecs.register_error,
      codecs.lookup_error is _codecs.lookup_error)

# --- the eight driving real codec calls ----------------------------------------------------

print("calls", "a\u00e9".encode("ascii", "replace"), b"a\xff".decode("utf-8", "replace"))
print("calls2", "\u00e9".encode("ascii", "xmlcharrefreplace"), "\u00e9".encode("ascii", "namereplace"))
print("calls3", repr(b"\xff\xfe".decode("ascii", "surrogateescape")),
      "a\udc80".encode("utf-8", "surrogateescape"))
print("calls4", "\ud800".encode("utf-8", "surrogatepass"),
      repr(b"\xed\xa0\x80".decode("utf-8", "surrogatepass")))
print("calls5", "a\u00e9".encode("ascii", "backslashreplace"), b"a\xff".decode("utf-8", "backslashreplace"))
