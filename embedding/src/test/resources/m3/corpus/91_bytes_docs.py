# The forty-two sentences bytes files beside its methods, and the two halves CPython cuts from
# each of them. Everything here is read off the descriptor bytes keeps in its own dictionary; the
# bound half is checked as a comparison of texts, never as a type name, because the shape a value's
# own method reports itself as is still this runtime's named deviation.
NAMES = [
    "capitalize", "center", "count", "decode", "endswith", "expandtabs", "find", "fromhex", "hex",
    "index", "isalnum", "isalpha", "isascii", "isdigit", "islower", "isspace", "istitle", "isupper",
    "join", "ljust", "lower", "lstrip", "maketrans", "partition", "removeprefix", "removesuffix",
    "replace", "rfind", "rindex", "rjust", "rpartition", "rsplit", "rstrip", "split", "splitlines",
    "startswith", "strip", "swapcase", "title", "translate", "upper", "zfill",
]


def show(label, fn):
    try:
        print(label, "->", repr(fn()))
    except Exception as e:
        print(label, "->", type(e).__name__)


print("== the two halves of one text ==")
print("names", len(NAMES), "kind", type(bytes.hex).__name__)
for name in NAMES:
    method = getattr(bytes, name)
    print(name, "|", repr(method.__text_signature__), "|", type(method.__doc__).__name__, "|",
          repr(method.__doc__))

print("== whose table names what ==")
print("with $self", sum(1 for name in NAMES
                        if getattr(bytes, name).__text_signature__.startswith("($self")))
print("with $type", sum(1 for name in NAMES
                        if getattr(bytes, name).__text_signature__.startswith("($type")))
print("with neither", sum(1 for name in NAMES
                          if not getattr(bytes, name).__text_signature__.startswith("($")))
print("$module anywhere", sum(1 for name in NAMES
                              if "$module" in getattr(bytes, name).__text_signature__))

print("== the two made from the class, not the value ==")
for name in ["fromhex", "maketrans"]:
    row = bytes.__dict__[name]
    reached = getattr(bytes, name)
    print(name, "|", type(row).__name__, "|", type(reached).__name__, "|",
          repr(reached.__text_signature__), "|", reached.__name__, "|", reached.__qualname__, "|",
          type(reached.__self__).__name__)

print("== one text, two readers ==")
for name in ["hex", "upper", "center", "translate", "maketrans", "fromhex", "count"]:
    bound = getattr(b"", name)
    print(name, "|", bound.__doc__ == getattr(bytes, name).__doc__, "|", bound.__name__, "|",
          bound.__qualname__, "|", type(bound.__self__).__name__)

print("== the doc half is a read, not a field ==")
show("write bytes.hex.__doc__", lambda: setattr(bytes.hex, "__doc__", "x"))
show("delete bytes.hex.__doc__", lambda: delattr(bytes.hex, "__doc__"))
show("write the dictionary row's doc", lambda: setattr(bytes.__dict__["hex"], "__doc__", "x"))
show("write b''.hex.__doc__", lambda: setattr(b"".hex, "__doc__", "x"))
show("write bytes.maketrans.__doc__", lambda: setattr(bytes.maketrans, "__doc__", "x"))
show("write bytes.hex.__text_signature__", lambda: setattr(bytes.hex, "__text_signature__", "()"))
show("the row and the read are one text", lambda: bytes.__dict__["hex"].__doc__ == bytes.hex.__doc__)
show("a second read of the doc", lambda: getattr(bytes.hex, "__doc__") == bytes.hex.__doc__)
show("a missing name reads none", lambda: getattr(bytes.hex, "__ml_doc__", "absent"))

print("== what the text does not change ==")
show("hex repr", lambda: repr(bytes.hex))
show("hex callable", lambda: callable(bytes.hex))
show("hex dir is sorted", lambda: dir(bytes.hex) == sorted(dir(bytes.hex)))
show("hex hash is stable", lambda: hash(bytes.hex) == hash(bytes.hex))
show("upper called", lambda: b"abc".upper())
show("hex called", lambda: b"ab".hex())
show("hex with a separator", lambda: b"ab".hex(b":"))
show("center called", lambda: b"ab".center(5, b"-"))
show("strip called", lambda: b"  x ".strip())
show("count called", lambda: b"ababa".count(b"aba"))
show("find called", lambda: b"abc".find(b"c"))
show("index called", lambda: b"abc".index(b"c"))
show("split called", lambda: b"a,b".split(b","))
show("rsplit called", lambda: b"a,b".rsplit(b",", 1))
show("partition called", lambda: b"a,b".partition(b","))
show("replace called", lambda: b"ab".replace(b"a", b"c"))
show("join called", lambda: b".".join([b"a", b"b"]))
show("startswith called", lambda: b"ab".startswith(b"a"))
show("endswith called", lambda: b"ab".endswith(b"b"))
show("decode called", lambda: b"ab".decode())
show("title called", lambda: b"hello world".title())
show("swapcase called", lambda: b"AbC".swapcase())
show("zfill called", lambda: b"12".zfill(5))
show("expandtabs called", lambda: b"a\tb".expandtabs())
show("splitlines called", lambda: b"a\nb".splitlines())
show("removeprefix called", lambda: b"abc".removeprefix(b"a"))
show("removesuffix called", lambda: b"abc".removesuffix(b"c"))
show("istitle called", lambda: b"A B".istitle())
show("isalnum called", lambda: b"a1".isalnum())
show("fromhex called", lambda: bytes.fromhex("ff"))
show("maketrans called", lambda: bytes.maketrans(b"ab", b"cd"))
show("translate called", lambda: b"abc".translate(bytes.maketrans(b"ab", b"cd")))
show("capitalize called", lambda: b"aBC".capitalize())
