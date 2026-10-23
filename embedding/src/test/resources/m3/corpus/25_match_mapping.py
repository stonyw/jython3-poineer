"""Mapping patterns: which subject is a mapping at all, what its keys are asked for, and what `**rest` keeps.

A mapping pattern asks one question of the subject's class before it asks anything of the value. `dict` is
a mapping; a list, a tuple, a string and a class a person wrote are not — and a written class stays a
non-mapping however carefully it answers `__getitem__`, `__len__` and `get`, which is why `get` prints
nothing below: the flag decides, and the method is never looked up.

After that the keys are checked in the order the pattern spells them, each through the subject's `get`
with a default, so one key missing fails the whole pattern and the values come back in that same order —
not in the dict's. What `**rest` collects is a new dict of everything the pattern did not name, and it is
new: the subject keeps its own contents.
"""


def report(label, function):
    try:
        print("   " + label + " -> " + repr(function()))
    except BaseException as error:
        print("   " + label + " -> " + type(error).__name__ + ": " + str(error))


class Indexed:
    """Answers everything a mapping would be asked, and is still not one."""

    def __getitem__(self, key):
        return "getitem " + str(key)

    def __len__(self):
        return 2

    def get(self, key, default):
        print("   get asked for " + str(key))
        return "value"


class Quiet:
    pass


FULL = {"a": 1, "b": 2, "c": 3}
EMPTY = {}
ONE = {"a": 1}


def mapping_shape(subject):
    match subject:
        case {"a": _}:
            return "mapping"
        case _:
            return "not"


print("=== which subjects a mapping pattern accepts")
report("dict", lambda: mapping_shape(ONE))
report("empty dict", lambda: mapping_shape(EMPTY))
report("list", lambda: mapping_shape([1, 2]))
report("tuple", lambda: mapping_shape((1, 2)))
report("string", lambda: mapping_shape("ab"))
report("set", lambda: mapping_shape({1, 2}))
report("range", lambda: mapping_shape(range(2)))
report("int", lambda: mapping_shape(7))
report("written class with the three dunders", lambda: mapping_shape(Indexed()))
report("plain instance", lambda: mapping_shape(Quiet()))
report("none", lambda: mapping_shape(None))


def one_key(subject):
    match subject:
        case {"a": found}:
            return found
        case _:
            return "no match"


print("=== the keys a mapping pattern names")
report("key present", lambda: one_key(ONE))
report("key absent", lambda: one_key({"b": 2}))
report("key absent from an empty dict", lambda: one_key(EMPTY))
report("extra keys are ignored", lambda: one_key({"a": 1, "z": 9}))


def two_keys(subject):
    match subject:
        case {"b": second, "a": first}:
            return (first, second)
        case _:
            return "no match"


def three_keys(subject):
    match subject:
        case {"c": third, "a": first, "b": second}:
            return (first, second, third)
        case _:
            return "no match"


print("=== the order the values are bound in")
report("two keys in pattern order", lambda: two_keys(FULL))
report("three keys in pattern order", lambda: three_keys(FULL))


def one_key_missing(subject):
    match subject:
        case {"a": _, "missing": _}:
            return "matched"
        case _:
            return "no match"


print("=== one missing key fails the whole pattern")
report("second key absent", lambda: one_key_missing(ONE))


def empty_pattern(subject):
    match subject:
        case {}:
            return "empty"
        case _:
            return "not empty"


print("=== an empty mapping pattern asks the class only, never the length")
report("empty dict", lambda: empty_pattern(EMPTY))
report("one entry", lambda: empty_pattern(ONE))
report("three entries", lambda: empty_pattern(FULL))
report("list", lambda: empty_pattern([]))


class Colors:
    RED = "red"


def int_key(subject):
    match subject:
        case {1: found}:
            return found
        case _:
            return "no match"


def none_key(subject):
    match subject:
        case {None: found}:
            return found
        case _:
            return "no match"


def true_key(subject):
    match subject:
        case {True: found}:
            return found
        case _:
            return "no match"


def dotted_key(subject):
    match subject:
        case {Colors.RED: found}:
            return found
        case _:
            return "no match"


print("=== the keys a pattern is allowed to spell")
report("int key", lambda: int_key({1: "one"}))
report("int key against a string dict", lambda: int_key({"1": "one"}))
report("none key", lambda: none_key({None: "nothing"}))
report("true key", lambda: true_key({True: "truth"}))
report("true key against one", lambda: true_key({1: "one"}))
report("dotted name as a key", lambda: dotted_key({"red": "colour"}))
report("dotted name absent from the class", lambda: dotted_key({"blue": "cold"}))


def rest_of(subject):
    match subject:
        case {"a": named, **others}:
            return (named, others)
        case _:
            return "no match"


def rest_only(subject):
    match subject:
        case {**everything}:
            return everything
        case _:
            return "no match"


print("=== what **rest keeps")
report("rest without the named key", lambda: rest_of(FULL))
report("rest when nothing is left", lambda: rest_of(ONE))
report("rest alone", lambda: rest_only(FULL))
report("rest alone over empty", lambda: rest_only(EMPTY))


def rest_is_a_new_dict():
    result = rest_only(FULL)
    return (result is FULL, sorted(FULL) == sorted(result))


report("rest is the subject", rest_is_a_new_dict)


def nested_in_sequence(subject):
    match subject:
        case [{"k": inner}]:
            return inner
        case _:
            return "no match"


def sequence_inside_mapping(subject):
    match subject:
        case {"a": [head, *tail]}:
            return (head, tail)
        case _:
            return "no match"


def mapping_inside_mapping(subject):
    match subject:
        case {"outer": {"inner": found}}:
            return found
        case _:
            return "no match"


print("=== a mapping pattern beside the others")
report("mapping inside a sequence", lambda: nested_in_sequence([{"k": 7}]))
report("sequence inside a mapping", lambda: sequence_inside_mapping({"a": [1, 2, 3]}))
report("mapping inside a mapping", lambda: mapping_inside_mapping({"outer": {"inner": 9}}))


def bound_by_as(subject):
    match subject:
        case {"a": _} as whole:
            return whole is subject
        case _:
            return "no match"


print("=== what `as` binds after a mapping pattern")
report("as binds the subject itself", lambda: bound_by_as(ONE))


def as_beside_a_name(subject):
    match subject:
        case {"a": found} as whole:
            return (found, whole is subject, sorted(whole))
        case _:
            return "no match"


report("as and a captured name", lambda: as_beside_a_name(FULL))


def guarded(subject):
    match subject:
        case {"a": found} if found > 5:
            return ("guard passed", found)
        case {"a": found}:
            return ("guard failed, second case", found)
        case _:
            return "no match"


print("=== a guard after a mapping pattern")
report("guard passes", lambda: guarded({"a": 9}))
report("guard fails", lambda: guarded({"a": 1}))
report("nothing matches", lambda: guarded({"b": 1}))


def get_is_never_asked(subject):
    match subject:
        case {"a": _}:
            return "matched"
        case _:
            return "no match"


print("=== the written class is refused before its get is looked up")
report("indexed", lambda: get_is_never_asked(Indexed()))
