"""The parts of `re` this runtime answers through java.util.regex, pinned byte for byte.

Every line here was also run through the reference interpreter; the corpus exists to notice the
day a translation starts answering differently. The divergences that remain are written down in
docs/re-java-regex-divergences.md, not exercised here.
"""

import sys

sys.path.insert(
    0,
    "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
    "Versions/3.14/lib/python3.14",
)

import re


def show(label, value):
    print(label, repr(value))


# --- \w, \d, \s and their negations are Python's Unicode tables, not Java's ---
show("w combining", bool(re.match(r"\w", "\u0301")))
show("w connector", bool(re.match(r"\w", "\u203f")))
show("w roman", bool(re.match(r"\w", "\u2160")))
show("w circled", bool(re.match(r"\w", "\u2460")))
show("W combining", bool(re.match(r"\W", "\u0301")))
show("W in class", bool(re.match(r"[\W]", "\u0301")))
show("d fullwidth", bool(re.match(r"\d", "\uff10")))
show("d arabic", bool(re.match(r"\d", "\u0660")))
show("D fullwidth", bool(re.match(r"\D", "\uff10")))
show("s 1c", bool(re.match(r"\s", "\x1c")))
show("s 85", bool(re.match(r"\s", "\x85")))
show("s 180e", bool(re.match(r"\s", "\u180e")))
show("s 2028", bool(re.match(r"\s", "\u2028")))
show("S tab", bool(re.match(r"[\S]", "\t")))
show("S 1c", bool(re.match(r"[\S]", "\x1c")))
show("S a", bool(re.match(r"[\S]", "a")))

# --- the same escapes under re.ASCII are Java's default tables ---
show("ascii w combining", bool(re.match(r"\w", "\u0301", re.ASCII)))
show("ascii s 1c", bool(re.match(r"\s", "\x1c", re.ASCII)))
show("ascii d fullwidth", bool(re.match(r"\d", "\uff10", re.ASCII)))
show("ascii word", bool(re.match(r"\w+", "abc_1", re.ASCII)))

# --- scoped flags change those tables for their own group only ---
show("scoped a", bool(re.match(r"\w(?a:\W)\w", "\xe0\xe0\xe0")))
show("scoped u", bool(re.match(r"(?a:\w)(?u:\w)", "a\xe0")))
show("scoped u inside ascii", bool(re.match(r"\w(?u:\w)", "\xe0\xe0", re.ASCII)))

# --- \b and \B follow the same word set ---
show("b end accent", bool(re.match(r"a\b", "a\u0301")))
show("b accent start", bool(re.match(r"\b\u0301", "\u0301")))
show("B start", bool(re.match(r"\Ba", "a")))
show("B in word", bool(re.match(r"a\Bb", "ab")))
show("b positions", [m.start() for m in re.finditer(r"\b", "ab cd")])

# --- octal escapes: three digits outside a class, every digit inside one ---
show("octal class", bool(re.match(r"[\41]", "!")))
show("octal class range", bool(re.match(r"[\101-\103]+", "ABC")))
show("octal outside", bool(re.match(r"\277", "\xbf")))
show("octal zero", bool(re.match(r"\0", "\x00")))
show("octal four digits", re.findall(r"\1011", "A1"))

# --- ^ and $ know only \n, and $ also matches before a trailing newline ---
show("hat multiline cr", [m.start() for m in re.finditer("^", "a\rb", re.M)])
show("dollar multiline cr", [m.start() for m in re.finditer("$", "a\rb", re.M)])
show("hat multiline nl", [m.start() for m in re.finditer("^", "a\nb", re.M)])
show("dollar multiline nl", [m.start() for m in re.finditer("$", "a\nb", re.M)])
show("dollar trailing nl", re.search("a$", "a\n").span())
show("z absolute", bool(re.search("a\\z", "a")))
show("Z absolute", bool(re.search("a\\Z", "a")))

# --- named groups keep their numbers; only Java's spelling changes ---
m = re.match(r"(?P<left>a)(?P<right>b)(?P=left)", "aba")
show("named group", m.group(0))
show("named groupdict", m.groupdict())
show("named by name", m.group("right"))
show("named by number", m.group(2))

# --- greedy, lazy and possessive quantifiers ---
show("greedy", re.match(r"a+", "aaa").group(0))
show("lazy", re.match(r"a+?", "aaa").group(0))
show("possessive", re.match(r"a*+a", "aaaa"))
show("lazy backoff", re.match(r"a+?b", "aaab").group(0))
show("nested repeat", re.fullmatch(r"(a+)+", "aaa").group(0))

# --- the Match and Pattern surfaces over the same translation ---
m = re.search(r"(?P<word>\w+)=(?P<num>\d+)", "xage=42y")
show("match group", m.group())
show("match groups", m.groups())
show("match groupdict", m.groupdict())
show("match span", m.span())
show("match span num", m.span("num"))
show("match start end", (m.start(), m.end()))
show("match expand", m.expand(r"\g<num>-\g<word>"))
show("findall", re.findall(r"(\w)(\d)", "a1 b2"))
show("finditer spans", [x.span() for x in re.finditer(r"\d+", "a12b345")])
show("sub", re.sub(r"(\w+)", r"<\1>", "a b"))
show("subn", re.subn(r"\d", "#", "a1b2"))
show("split", re.split(r",\s*", "a, b,c"))
show("split capture", re.split(r"([,;])", "a,b;c"))
show("sub empty word boundary", re.sub(r"\b", "|", "ab cd"))
show("escaped space verbose", bool(re.fullmatch("(?x)" + re.escape(" "), " ")))
show("escaped 1c verbose", bool(re.fullmatch("(?x)" + chr(0x1c), chr(0x1c))))

# --- bytes patterns use the ASCII tables without being asked ---
show("bytes w", bool(re.match(rb"\w+", b"abc_1")))
show("bytes w high", bool(re.match(rb"\w", b"\xff")))
show("bytes octal", bool(re.match(rb"[\41]", b"!")))
show("bytes boundary", [m.start() for m in re.finditer(rb"\b", b"ab cd")])
