"""Conditional groups `(?(id)yes|no)` on top of `java.util.regex`, which has no such construct.

Java rejects a backreference to a group that never took part and accepts one whose text is empty,
so an empty group placed at the very end of group N answers exactly "did group N take part". The
translation is `(?:(?=\\k<mkN>)yes|(?!\\k<mkN>)no)` around that marker. Two consequences are pinned
here as well: a group that is still open has no end mark yet and therefore reads as "did not take
part" — CPython does the same — and a two-digit reference such as `\\119` is group 11 followed by a
literal 9, because `_parser.py` reads at most two digits there.
"""
import sys

sys.path.insert(
    0,
    "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
    "Versions/3.14/lib/python3.14",
)

import re


def show(pattern, string):
    match = re.search(pattern, string)
    print(pattern, repr(string), match.group() if match else None,
          match.groups() if match else None)


# A closed group decides the branch; `no` is taken when the group did not take part.
for pattern, string in [
    (r"(a)(?(1)b|c)", "ab"),
    (r"(a)(?(1)b|c)", "c"),
    (r"(?P<x>a)(?(x)b|c)", "ab"),
    (r"(?P<x>a)(?(x)b|c)", "c"),
    (r"(?:(a)|c)(?(1)b|d)", "ab"),
    (r"(?:(a)|c)(?(1)b|d)", "cd"),
    (r"(a?)(?(1)X|Y)", "X"),
    (r"(a?)(?(1)X|Y)", "Y"),
    (r"(a)(?(1))", "a"),
    (r"(a)(?(1)|b)", "ab"),
    (r"(?=(a)(?(1)b|c))", "ab"),
]:
    show(pattern, string)

# An open group has not recorded its end mark yet, so the condition is false.
show(r"((?(1)a|b))", "b")
show(r"((?(1)a|b))", "ab")
show(r"(a|(?(1)b|c))", "c")

# The 256 patterns `test_re_groupref_exists_validation_bug` compiles.
for code in (0, 1, 65, 255):
    match = re.compile(r"()(?(1)\x%02x?)" % code).match(chr(code))
    print(code, match.span(), match.groups())

# A reference is at most two digits: `\119` is group 11 and a literal 9.
twelve = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)"
print(re.match(twelve + r"\119", "abcdefghijklk9").group())
print(re.match(twelve + r"\11", "abcdefghijklk").group())
print(re.match(twelve + r"\1", "abcdefghijkla").group())

# Numbering is unchanged by the marker group: `\2\1` still says b then a.
mixed = re.compile(r"(a)(b)\2\1(?(2)x)")
print(mixed.match("abbax").group(), mixed.match("abbax").groups())
print(re.match(r"(a)(b)\2\1", "abba").group())

# The iteration APIs see the same pattern as `search` does.
print(re.findall(r"(a)(?(1)b|c)", "ab c"))
print(re.sub(r"(a)(?(1)b|c)", r"<\1>", "ab c"))
print(re.split(r"(a)(?(1)b|c)", "ab c"))
print([m.group() for m in re.finditer(r"(a)(?(1)b|c)", "ab c")])
