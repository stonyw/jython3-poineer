"""Where CPython asks a value for {@code __index__}, and in whose words it refuses.

Every {@code Index()}, {@code Wide()}, {@code Bad()} and {@code Raised()} below is an <em>instance</em>:
the point is what the converter does with a value that can answer as a number. The bare classes appear in
one section of their own, because a class is not one — {@code type} files no {@code __index__}.
"""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


class Index:
    def __index__(self):
        return 3


class Zero:
    def __index__(self):
        return 0


class Wide:
    def __index__(self):
        return 2 ** 70


class Deep:
    def __index__(self):
        return -(2 ** 70)


class Bad:
    def __index__(self):
        return "three"


class Nothing:
    def __index__(self):
        return None


class Raised:
    def __index__(self):
        raise ValueError("inside __index__")


class NoIndex:
    pass


class Answerer:
    def __len__(self):
        return 3


class Negative:
    def __len__(self):
        return -1


class Backwards:
    def __len__(self):
        return -(2 ** 70)


class TooWide:
    def __len__(self):
        return 2 ** 70


class Squares:
    def __getitem__(self, key):
        return key * key

    def __setitem__(self, key, value):
        return "written"

    def __len__(self):
        return 4


VALUES = [0, 1, 2, 3]
NUMBERS = [1, 2, 3, 4]

print("== the gate, on an instance of each kind")
print("list[Index()]           ", said(lambda: VALUES[Index()]))
print("list[Zero()]            ", said(lambda: VALUES[Zero()]))
print("list[Wide()]            ", said(lambda: VALUES[Wide()]))
print("list[Deep()]            ", said(lambda: VALUES[Deep()]))
print("list[Bad()]             ", said(lambda: VALUES[Bad()]))
print("list[Nothing()]         ", said(lambda: VALUES[Nothing()]))
print("list[Raised()]          ", said(lambda: VALUES[Raised()]))
print("list[NoIndex()]         ", said(lambda: VALUES[NoIndex()]))
print("list[2.0]               ", said(lambda: VALUES[2.0]))
print("list[None]              ", said(lambda: VALUES[None]))
print("list[True]              ", said(lambda: VALUES[True]))
print("list['2']               ", said(lambda: VALUES["2"]))
print("answer of Index()       ", said(lambda: Index().__index__()))
print("answer of Bad()         ", said(lambda: Bad().__index__()))

print("== a class is not an index")
print("list[Index]             ", said(lambda: VALUES[Index]))
print("list[Bad]               ", said(lambda: VALUES[Bad]))
print("list[Raised]            ", said(lambda: VALUES[Raised]))
print("chr[not](Wide)          ", said(lambda: chr(Wide)))
print("range(Bad)              ", said(lambda: range(Bad)))

print("== a sequence read by an index")
print("str[Index()]            ", said(lambda: "abcdef"[Index()]))
print("str[Wide()]             ", said(lambda: "abcdef"[Wide()]))
print("str[Deep()]             ", said(lambda: "abcdef"[Deep()]))
print("str[-4]                 ", said(lambda: "abcdef"[-4]))
print("bytes[Index()]          ", said(lambda: b"abcdef"[Index()]))
print("tuple[Bad()]            ", said(lambda: (1, 2, 3, 4)[Bad()]))
print("bytes[Nothing()]        ", said(lambda: b"abcd"[Nothing()]))
print("own[Wide()]             ", said(lambda: Squares()[Wide()]))
print("own[2]                  ", said(lambda: Squares()[2]))
print("own.__setitem__ Wide()  ", said(lambda: Squares().__setitem__(Wide(), 1)))
print("own.__setitem__ Bad()   ", said(lambda: Squares().__setitem__(Bad(), 1)))
print("own.__delitem__ Bad()   ", said(lambda: Squares().__delitem__(Bad())))
print("list.__setitem__ Bad()  ", said(lambda: [1, 2].__setitem__(Bad(), 1)))
print("list.__setitem__ Wide() ", said(lambda: [1, 2].__setitem__(Wide(), 1)))
print("list.__setitem__ 4      ", said(lambda: [1, 2].__setitem__(4, 1)))
print("str.index Index()       ", said(lambda: "abcdef".index(Index())))
print("str.count Index()       ", said(lambda: "abcdef".count(Index())))
print("list.index 2 Wide()     ", said(lambda: NUMBERS.index(2, Wide())))
print("list.index 2 Bad()      ", said(lambda: NUMBERS.index(2, Bad())))
print("tuple.index 2 Bad()     ", said(lambda: (1, 2, 3).index(2, Bad())))
print("list.count 1 Bad()      ", said(lambda: NUMBERS.count(1, Bad())))
print("tuple.count 1 Bad()     ", said(lambda: (1, 2).count(1, Bad())))

print("== slices take the same reading")
print("s[Index():Index()]      ", said(lambda: "abcdef"[Index() : Index()]))
print("s[Zero():Index()]       ", said(lambda: "abcdef"[Zero() : Index()]))
print("s[Wide():]              ", said(lambda: "abcdef"[Wide() :]))
print("s[:Wide()]              ", said(lambda: "abcdef"[: Wide()]))
print("s[Deep():Zero()]        ", said(lambda: "abcdef"[Deep() : Zero()]))
print("s[::Deep()]             ", said(lambda: "abcdef"[:: Deep()]))
print("s[Bad():None]           ", said(lambda: "abcdef"[Bad() : None]))
print("s[Raised():]            ", said(lambda: "abcdef"[Raised() :]))
print("s[2.0:]                 ", said(lambda: "abcdef"[2.0 :]))
print("s[None:None:Bad()]      ", said(lambda: "abcdef"[None : None : Bad()]))
print("own[::Index()]          ", said(lambda: Squares()[:: Index()]))
print("list.__setitem__ slice  ", said(lambda: NUMBERS.__setitem__(slice(Bad()), [9])))
print("del s[Index()]          ", said(lambda: NUMBERS.__delitem__(Index())))

print("== membership reads an index too")
print("4 in str                ", said(lambda: 4 in "abcdef"))
print("Index() in str          ", said(lambda: Index() in "abcdef"))
print("2.0 in str              ", said(lambda: 2.0 in "abcdef"))
print("Index() in list         ", said(lambda: Index() in NUMBERS))
print("Index() in tuple        ", said(lambda: Index() in (1, 2, 3, 4)))
print("Index() in set          ", said(lambda: Index() in {1, 2, 3}))

print("== repetition")
print("str * Index (the class) ", said(lambda: "ab" * Index))
print("str * Index()           ", said(lambda: "ab" * Index()))
print("str * Wide()            ", said(lambda: "ab" * Wide()))
print("str * Deep()            ", said(lambda: "ab" * Deep()))
print("str * Zero()            ", said(lambda: "ab" * Zero()))
print("str * 2.0               ", said(lambda: "ab" * 2.0))
print("str * NoIndex()         ", said(lambda: "ab" * NoIndex()))
print("str * Bad()             ", said(lambda: "ab" * Bad()))
print("str * Raised()          ", said(lambda: "ab" * Raised()))
print("Index() * str           ", said(lambda: Index() * "ab"))
print("bytes * Wide()          ", said(lambda: b"ab" * Wide()))
print("bytes * Deep()          ", said(lambda: b"ab" * Deep()))
print("bytes * NoIndex()       ", said(lambda: b"ab" * NoIndex()))
print("list * Wide()           ", said(lambda: [1] * Wide()))
print("list * NoIndex()        ", said(lambda: [1] * NoIndex()))
print("tuple * Bad()           ", said(lambda: (1,) * Bad()))
print("list *= Wide()          ", said(lambda: NUMBERS.__imul__(Wide())))

print("== octets")
print("bytes(Zero())           ", said(lambda: bytes(Zero())))
print("bytes(Index())          ", said(lambda: bytes(Index())))
print("bytes(Wide())           ", said(lambda: bytes(Wide())))
print("bytes(Deep())           ", said(lambda: bytes(Deep())))
print("bytes(-1)               ", said(lambda: bytes(-1)))
print("bytes(Bad())            ", said(lambda: bytes(Bad())))
print("bytes(Raised())         ", said(lambda: bytes(Raised())))
print("bytes(Nothing())        ", said(lambda: bytes(Nothing())))
print("bytes(NoIndex())        ", said(lambda: bytes(NoIndex())))
print("bytes(2.0)              ", said(lambda: bytes(2.0)))
print("bytes([Index()])        ", said(lambda: bytes([Index()])))
print("bytes([Bad()])          ", said(lambda: bytes([Bad()])))
print("bytes([Wide()])         ", said(lambda: bytes([Wide()])))
print("bytes([256])            ", said(lambda: bytes([256])))
print("3 in b'\\x02\\x03'     ", said(lambda: 3 in b"\x02\x03"))
print("Index() in bytes        ", said(lambda: Index() in b"\x02\x03"))
print("9 in bytes              ", said(lambda: 9 in b"\x02\x03"))
print("Wide() in bytes         ", said(lambda: Wide() in b"\x02\x03"))
print("Deep() in bytes         ", said(lambda: Deep() in b"\x02\x03"))
print("Bad() in bytes          ", said(lambda: Bad() in b"ab"))
print("Raised() in bytes       ", said(lambda: Raised() in b"ab"))
print("Nothing() in bytes      ", said(lambda: Nothing() in b"ab"))
print("b'ab'.find Index()      ", said(lambda: b"ab".find(Index())))
print("b'ab'.find Wide()       ", said(lambda: b"ab".find(Wide())))
print("b'ab'.find Bad()        ", said(lambda: b"ab".find(Bad())))
print("b'ab'.find NoIndex()    ", said(lambda: b"ab".find(NoIndex())))
print("b'ab'.find Raised()     ", said(lambda: b"ab".find(Raised())))
print("b'ab'.index -1          ", said(lambda: b"ab".index(-1)))
print("int.to_bytes Wide()     ", said(lambda: (1234).to_bytes(Wide(), "big")))
print("int.to_bytes Bad()      ", said(lambda: (1234).to_bytes(Bad(), "big")))
print("int.to_bytes Nothing()  ", said(lambda: (1234).to_bytes(Nothing(), "big")))
print("int.to_bytes 2.0        ", said(lambda: (1234).to_bytes(2.0, "big")))
print("int.to_bytes Deep()     ", said(lambda: (1234).to_bytes(Deep(), "big")))
print("int.to_bytes Index()    ", said(lambda: (1234).to_bytes(Index(), "big")))
print("int.from_bytes byteorder", said(lambda: int.from_bytes(b"\x01", NoIndex())))

print("== widths the builtins read")
print("chr(Index())            ", said(lambda: chr(Index())))
print("chr(Wide())             ", said(lambda: chr(Wide())))
print("chr(Bad())              ", said(lambda: chr(Bad())))
print("chr(NoIndex())          ", said(lambda: chr(NoIndex())))
print("chr(Raised())           ", said(lambda: chr(Raised())))
print("bin(Index())            ", said(lambda: bin(Index())))
print("hex(Wide())             ", said(lambda: hex(Wide())))
print("oct(Index())            ", said(lambda: oct(Index())))
print("bin(2.0)                ", said(lambda: bin(2.0)))
print("len(range(Wide()))      ", said(lambda: len(range(Wide()))))
print("str(range(Wide()))      ", said(lambda: str(range(Wide()))))
print("range(Wide())[Wide()]   ", said(lambda: range(Wide())[Wide()]))
print("range(10)[Bad()]        ", said(lambda: range(10)[Bad()]))
print("range(10)[Deep()]       ", said(lambda: range(10)[Deep()]))
print("range(0, Wide())[0]     ", said(lambda: range(0, Wide())[0]))
print("list(range(Index()))    ", said(lambda: list(range(Index()))))
print("next(enumerate(_,Wide()))", said(lambda: next(enumerate("ab", Wide()))))
print("next(enumerate(_,Index()))", said(lambda: next(enumerate("ab", Index()))))
print("enumerate('ab', Bad())  ", said(lambda: list(enumerate("ab", Bad()))))
print("len of Index() answer   ", said(lambda: len(Answerer())))
print("len of -1 answer        ", said(lambda: len(Negative())))
print("len of Wide answer      ", said(lambda: len(TooWide())))
print("len of -Wide answer     ", said(lambda: len(Backwards())))

print("== percent formatting and round")
print("'%%c' %% Index()        ", said(lambda: "%c" % Index()))
print("'%%c' %% 0x110000       ", said(lambda: "%c" % 0x110000))
print("'%%c' %% Wide()         ", said(lambda: "%c" % Wide()))
print("'%%c' %% -1             ", said(lambda: "%c" % -1))
print("'%%c' %% Bad()          ", said(lambda: "%c" % Bad()))
print("'%%c' %% NoIndex()      ", said(lambda: "%c" % NoIndex()))
print("'%%c' %% Raised()       ", said(lambda: "%c" % Raised()))
print("'%%c' %% 65             ", said(lambda: "%c" % 65))
print("'%%c' %% Zero()         ", said(lambda: "%c" % Zero()))
print("'%*s' width Index()     ", said(lambda: "%*s" % (Index(), "ab")))
print("'%*s' width Wide()      ", said(lambda: "%*s" % (Wide(), "ab")))
print("'%*s' width Bad()       ", said(lambda: "%*s" % (Bad(), "ab")))
print("round(5.5, Index())     ", said(lambda: round(5.5, Index())))
print("round(5.5, Wide())      ", said(lambda: round(5.5, Wide())))
print("round(5.5, Bad())       ", said(lambda: round(5.5, Bad())))
print("(5).__round__ Wide()    ", said(lambda: (5).__round__(Wide())))
print("(5).__index__           ", said(lambda: (5).__index__()))
print("True.__index__          ", said(lambda: True.__index__()))
print("float has no __index__  ", said(lambda: getattr(2.0, "__index__", "none")))
print("str has no __index__    ", said(lambda: getattr("ab", "__index__", "none")))

print("== the flags of __buffer__")
print("b''.__buffer__(1)       ", said(lambda: b"".__buffer__(1)))
print("b''.__buffer__(Index()) ", said(lambda: b"".__buffer__(Index())))
print("b''.__buffer__(Wide())  ", said(lambda: b"".__buffer__(Wide())))
print("b''.__buffer__(2 ** 31) ", said(lambda: b"".__buffer__(2 ** 31)))
print("b''.__buffer__(Bad())   ", said(lambda: b"".__buffer__(Bad())))
print("b''.__buffer__(NoIndex)", said(lambda: b"".__buffer__(NoIndex())))
print("b''.__buffer__(Raised())", said(lambda: b"".__buffer__(Raised())))
print("'ab'.__buffer__(1)      ", said(lambda: "ab".__buffer__(1)))
