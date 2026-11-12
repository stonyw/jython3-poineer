# The regular expression module. `re` is the first stdlib module in this tree that is written in
# Python on top of a C extension for something other than encoding: `re/_parser.py` and
# `re/_compiler.py` do the parsing and the code generation, and `_sre` is only asked to turn the
# finished source into a Pattern. So what follows is the surface those two layers hand a program --
# the six functions, the two value types and their groups, and the five flags Java has to be told
# about because Python spells them differently.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import re

print("module", re.__name__, re.__doc__ is not None)
print("types", re.Pattern.__name__, re.Match.__name__, issubclass(re.error, Exception))
print("isinstance", isinstance(re.compile("a"), re.Pattern), isinstance(re.match("a", "a"), re.Match))

# --- the six functions ---------------------------------------------------------------------


print("match", re.match(r"a(b)c", "abc").group(1))
print("match none", re.match("a", "ba"))
print("match pos", re.compile("b").match("abc", 1).span())
print("match endpos", re.compile("ab").match("abc", 0, 2).span())
print("search", re.search(r"\d+", "ab 123 cd 45").group())
print("search pos", re.compile(r"\d+").search("ab 123 cd 45", 5).group())
print("fullmatch", re.fullmatch(r"[a-z]+", "abc"), re.fullmatch(r"[a-z]+", "abc1"))
print("findall", re.findall(r"\d+", "a1b22c333"))
print("findall groups", re.findall(r"(\w+)@(\w+)", "a@b c@d"))
print("findall optional", re.findall(r"(\d)(\d)?", "12 3"))
print("findall empty", re.findall("", "ab"))
print("finditer", [m.span() for m in re.finditer(r"\d", "a1b2")])
print("finditer empty", [m.span() for m in re.finditer("a*", "b")])

# --- groups, named and numbered ------------------------------------------------------------


m = re.match(r"(?P<year>\d{4})-(?P<month>\d{2})-(?P<day>\d{2})", "2026-11-12")
print("groups", m.groups())
print("groupdict", m.groupdict())
print("last", m.lastindex, m.lastgroup)
print("by name", m.group("year"), m.group("month"), m["day"])
print("by index", m.group(1), m[2], m[0])
print("span", m.span(), m.span(2))
print("start end", m.start(), m.end(), m.start("day"), m.end("day"))
print("absent", re.match(r"a(b)?", "a").group(1), re.match(r"a(b)?", "a").span(1))
print("groupindex", re.compile(r"(?P<x>a)(?P<y>b)").groupindex)
print("groups count", re.compile(r"(a)(?:b)(c)").groups)

print("--- refusals")
for label, call in [
    ("group 3", lambda: re.match("(a)", "a").group(3)),
    ("group name", lambda: re.match("(a)", "a").group("zz")),
    ("span 3", lambda: re.match("(a)", "a").span(3)),
]:
    try:
        call()
    except BaseException as error:
        print(label, type(error).__name__, error)

# --- sub and split -------------------------------------------------------------------------


print("sub", re.sub(r"\s+", "_", " a  b \t c "))
print("sub count", re.sub("a", "X", "aaaa", count=2))
print("sub callable", re.sub(r"\d+", lambda m: str(int(m.group()) * 2), "1 20 3"))
print("sub named", re.sub(r"(?P<n>\w+)", r"<\g<n>>", "a b"))
print("sub backref", re.sub(r"(\w+) (\w+)", r"\2 \1", "hello world"))
print("sub empty", re.sub("x*", "-", "abxd"))
print("subn", re.subn("a", "X", "aa"))
print("split", re.split(r",\s*", "a, b,c"))
print("split groups", re.split(r"(\W)", "a, b"))
print("split limit", re.split(r",", "a,b,c", maxsplit=1))
print("split empty", re.split("", "ab"))

print("expand", re.match(r"(?P<a>\w)(\d)", "x1").expand(r"[\g<a>-\2]"))

# --- flags ---------------------------------------------------------------------------------


print("I", bool(re.match("abc", "ABC", re.IGNORECASE)), bool(re.match("abc", "ABC")))
print("I class", re.findall(r"[a-c]+", "AbCd", re.IGNORECASE))
print("M", re.findall(r"^\w+", "a\nb\nc", re.MULTILINE))
print("M off", re.findall(r"^\w+", "a\nb\nc"))
print("S", bool(re.match(r"a.b", "a\nb")), bool(re.match(r"a.b", "a\nb", re.DOTALL)))
print("X", bool(re.match(r"a b  # comment", "ab", re.VERBOSE)))
print("A", re.findall(r"\w+", "a\u00e9 1", re.ASCII))
print("no A", re.findall(r"\w+", "a\u00e9 1"))
print("inline", re.findall(r"(?i)abc", "ABC abc ABC"))
print("A anchors", bool(re.match(r"\Aab", "ab")), bool(re.search(r"ab\Z", "ab")))

# --- the two values ------------------------------------------------------------------------


p = re.compile(r"(?P<word>[a-z]+)!", re.IGNORECASE)
print("pattern", p.pattern, p.flags == (re.IGNORECASE | re.UNICODE), p.groups, p.groupindex)
print("pattern repr", p)
print("pattern repr flags", re.compile("a", re.I | re.M))
print("pattern methods", bool(p.match("Hi!")), p.findall("hi! ho!"), p.sub(r"[\g<word>]", "hi!"))
print("match repr", re.match("a", "abc"))
print("match repr long", re.match("a" * 60, "a" * 60))
print("match attrs", m.re is m.re, m.string, m.pos, m.endpos)

print("copies", p.__copy__() is p, m.__copy__() is m, p.__deepcopy__({}) is p)

# --- the error the parser raises -----------------------------------------------------------

print("--- compile errors")
for label, pattern in [
    ("unbalanced", "("),
    ("bad name", "(?P<1>a)"),
    ("unknown name", r"(?P=nope)"),
    ("nothing to repeat", "*"),
    ("bad range", "[z-a]"),
    ("bad escape", r"\q"),
    ("trailing", r"a\\"),
]:
    try:
        re.compile(pattern)
    except re.error as error:
        print(label, type(error).__name__, error)

print("purge", re.purge(), re.purge())
