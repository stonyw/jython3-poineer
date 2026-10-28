DESCRIPTOR = type(str.upper)
BOUND = type("".upper)

# The forty-eight sentences str files beside its methods, and the two halves CPython cuts from each of
# them. Everything here is read off the descriptor str keeps in its own dictionary; the bound half is
# measured as a comparison of texts, never as a type name, because the shape a value's own method reports
# itself as is still this runtime's named deviation. `maketrans` is not listed: its sentence was transcribed
# with the static rows. `__getnewargs__` is not listed either — it is the one row of these forty-nine that
# CPython registers with no sentence at all, so its table is built from the METH_NOARGS flag rather than
# read out of a text, and this runtime leaves such a table unanswered instead of inventing one.
NAMES = [
    "__format__", "__sizeof__", "capitalize", "casefold", "center", "count", "encode", "endswith",
    "expandtabs", "find", "format", "format_map", "index", "isalnum", "isalpha", "isascii", "isdecimal",
    "isdigit", "isidentifier", "islower", "isnumeric", "isprintable", "isspace", "istitle", "isupper",
    "join", "ljust", "lower", "lstrip", "partition", "removeprefix", "removesuffix", "replace", "rfind",
    "rindex", "rjust", "rpartition", "rsplit", "rstrip", "split", "splitlines", "startswith", "strip",
    "swapcase", "title", "translate", "upper", "zfill",
]


def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "->", type(e).__name__)


def refuses(label, fn):
    try:
        fn()
        print(label, "-> accepted")
    except Exception as e:
        print(label, "->", type(e).__name__)


print("== the two halves of one text ==")
print("names", len(NAMES), "kind", DESCRIPTOR.__name__)
for name in NAMES:
    method = getattr(str, name)
    print(name, "|", repr(method.__text_signature__), "|", type(method.__doc__).__name__, "|",
          repr(method.__doc__))

print("== whose table names what ==")
print("with $self", sum(1 for name in NAMES
                        if getattr(str, name).__text_signature__.startswith("($self")))
print("with $type", sum(1 for name in NAMES
                        if getattr(str, name).__text_signature__.startswith("($type")))
print("with neither", sum(1 for name in NAMES
                          if not getattr(str, name).__text_signature__.startswith("($")))
print("$module anywhere", sum(1 for name in NAMES
                              if "$module" in getattr(str, name).__text_signature__))

print("== what the tables say, and do not say ==")
print("tables that end with /)", sum(1 for name in NAMES
                                     if getattr(str, name).__text_signature__.endswith("/)")))
print("tables that name a default", sum(1 for name in NAMES
                                        if "=" in getattr(str, name).__text_signature__))
print("tables with optional brackets", sum(1 for name in NAMES
                                           if "[" in getattr(str, name).__text_signature__))
print("tables with a keyword-only mark", sum(1 for name in NAMES
                                             if "*, " in getattr(str, name).__text_signature__))
print("docs that hold an argument block", sum(1 for name in NAMES
                                             if "\n  " in getattr(str, name).__doc__))

print("== the fold points the C source chose ==")
print("sentences of one line", sum(1 for name in NAMES if "\n" not in getattr(str, name).__doc__))
print("longest line of any text", max(len(line) for name in NAMES
                                      for line in getattr(str, name).__doc__.split("\n")))
print("the widest table", max(len(getattr(str, name).__text_signature__) for name in NAMES))

print("== the row that answers a call but says nothing ==")
row = str.__dict__["__getnewargs__"]
print("row", type(row).__name__, "|", "doc", repr(row.__doc__), "|", "reached",
      repr(str.__getnewargs__("ab")), "|", "bound", repr("".__getnewargs__()))

print("== one text, two readers ==")
for name in ["upper", "center", "split", "encode", "count", "translate", "__format__", "isidentifier"]:
    bound = getattr("", name)
    print(name, "|", bound.__doc__ == getattr(str, name).__doc__, "|", bound.__name__, "|",
          bound.__qualname__, "|", type(bound.__self__).__name__)
print("bound kind", BOUND.__name__ != DESCRIPTOR.__name__)

print("== the doc half is a read, not a field ==")
refuses("write str.upper.__doc__", lambda: setattr(str.upper, "__doc__", "x"))
refuses("delete str.upper.__doc__", lambda: delattr(str.upper, "__doc__"))
refuses("write the dictionary row's doc", lambda: setattr(str.__dict__["upper"], "__doc__", "x"))
refuses("write ''.upper.__doc__", lambda: setattr("".upper, "__doc__", "x"))
refuses("write str.encode.__text_signature__",
        lambda: setattr(str.encode, "__text_signature__", "()"))
refuses("delete str.encode.__text_signature__", lambda: delattr(str.encode, "__text_signature__"))
show("the row and the read are one text", lambda: str.__dict__["upper"].__doc__ == str.upper.__doc__)
show("a second read of the doc", lambda: getattr(str.upper, "__doc__") == str.upper.__doc__)
show("a second read of the table",
     lambda: getattr(str.encode, "__text_signature__") == str.encode.__text_signature__)

print("== what the text does not change ==")
show("call the dictionary row", lambda: str.__dict__["replace"]("aaa", "a", "b", 2))
show("call through a value", lambda: "".join(["ab", "pq", "rs"]))
show("call with a keyword", lambda: "a b c".split(sep=" ", maxsplit=1))
show("call the empty string's strip", lambda: "   ".strip())
show("encode an ascii string", lambda: "hi".encode())
show("format through the dunder", lambda: format(42, "05d"))
show("a casefolded comparison", lambda: "İ".casefold() == "i̇".casefold())
show("an identifier's own sentence", lambda: "def".isidentifier())
show("the class's own doc is not a method's", lambda: str.__doc__.startswith("str(object='')"))
show("no text leaked into the instances", lambda: "".upper.__doc__ is str.upper.__doc__)
