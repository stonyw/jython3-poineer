"""Sequence patterns: which subject is a sequence at all, how the length is asked, and what a star takes.

A sequence pattern asks the subject's class the same single question a mapping pattern does, and the
answer is a flag rather than a method. `list`, `tuple` and `range` carry it; `str`, `bytes`, `set`,
`dict`, `int` and a class a person wrote do not — and the written class keeps its `__getitem__` and
`__len__` unread, because the flag decides before either name is looked up.

Then the length is asked: a pattern that names two elements wants exactly two, one with a star wants at
least the named count, and a star alone wants nothing. The star binds a list even when the subject was a
tuple, and the list is its own.
"""


def report(label, function):
    try:
        print("   " + label + " -> " + repr(function()))
    except BaseException as error:
        print("   " + label + " -> " + type(error).__name__ + ": " + str(error))


class Indexed:
    """Answers everything a sequence would be asked, and is still not one."""

    def __init__(self, size):
        self.size = size

    def __getitem__(self, index):
        print("   __getitem__ asked for " + str(index))
        return "item"

    def __len__(self):
        print("   __len__ asked")
        return self.size


def sequence_shape(subject):
    match subject:
        case [*only]:
            return "sequence"
        case _:
            return "not"


print("=== which subjects a sequence pattern accepts")
report("one element list", lambda: sequence_shape([1]))
report("empty list", lambda: sequence_shape([]))
report("tuple", lambda: sequence_shape((1, 2)))
report("empty tuple", lambda: sequence_shape(()))
report("range of three", lambda: sequence_shape(range(3)))
report("empty range", lambda: sequence_shape(range(0)))
report("string", lambda: sequence_shape("ab"))
report("bytes", lambda: sequence_shape(b"ab"))
report("set", lambda: sequence_shape({1, 2}))
report("frozenset", lambda: sequence_shape(frozenset({1, 2})))
report("dict", lambda: sequence_shape({"a": 1}))
report("int", lambda: sequence_shape(7))
report("none", lambda: sequence_shape(None))
report("written class with the two dunders", lambda: sequence_shape(Indexed(2)))


def fixed_two(subject):
    match subject:
        case [first, second]:
            return (first, second)
        case _:
            return "no match"


print("=== a fixed-length pattern asks for exactly that many")
report("two elements", lambda: fixed_two([1, 2]))
report("one element", lambda: fixed_two([1]))
report("three elements", lambda: fixed_two([1, 2, 3]))
report("none", lambda: fixed_two([]))
report("tuple of two", lambda: fixed_two((1, 2)))
report("range of two", lambda: fixed_two(range(2)))


def star_tail(subject):
    match subject:
        case [head, *rest]:
            return (head, rest, type(rest).__name__)
        case _:
            return "no match"


def star_head(subject):
    match subject:
        case [*rest, last]:
            return (rest, last)
        case _:
            return "no match"


def star_middle(subject):
    match subject:
        case [first, *middle, last]:
            return (first, middle, last)
        case _:
            return "no match"


def star_alone(subject):
    match subject:
        case [*everything]:
            return everything
        case _:
            return "no match"


print("=== what a star takes")
report("head star against three", lambda: star_tail([1, 2, 3]))
report("head star against one", lambda: star_tail([1]))
report("head star against none", lambda: star_tail([]))
report("head star against a tuple", lambda: star_tail((1, 2, 3)))
report("head star against a range", lambda: star_tail(range(3)))
report("star head against three", lambda: star_head([1, 2, 3]))
report("star head against one", lambda: star_head([1]))
report("star middle against four", lambda: star_middle([1, 2, 3, 4]))
report("star middle against two", lambda: star_middle([1, 2]))
report("star middle against one", lambda: star_middle([1]))
report("star alone over three", lambda: star_alone([1, 2, 3]))
report("star alone over empty", lambda: star_alone([]))
report("star alone over a tuple", lambda: star_alone((1, 2)))
report("star alone over a string", lambda: star_alone("ab"))


def star_is_its_own_list():
    subject = [1, 2, 3]
    rest = star_tail(subject)[1]
    rest.append(4)
    return (len(subject), len(rest))


print("=== the list a star binds")
report("mutating it leaves the subject alone", star_is_its_own_list)


def three_in_order(subject):
    match subject:
        case [first, second, third]:
            return (third, first, second)
        case _:
            return "no match"


print("=== the order the elements are bound in")
report("range of three read back in pattern order", lambda: three_in_order(range(3)))


def literals(subject):
    match subject:
        case [1, 2, 3]:
            return "one two three"
        case [1, 2]:
            return "one two"
        case _:
            return "no match"


print("=== elements matched against values")
report("exact list", lambda: literals([1, 2, 3]))
report("last element differs", lambda: literals([1, 2, 4]))
report("shorter", lambda: literals([1, 2]))
report("longer", lambda: literals([1, 2, 3, 4]))
report("same length wrong first", lambda: literals([9, 2, 3]))
report("tuple of the same values", lambda: literals((1, 2, 3)))


def nested(subject):
    match subject:
        case [[left, right], [only]]:
            return (left, right, only)
        case _:
            return "no match"


def deep(subject):
    match subject:
        case [[[[found]]]]:
            return found
        case _:
            return "no match"


print("=== sequences inside sequences")
report("two levels", lambda: nested([[1, 2], [3]]))
report("inner too long", lambda: nested([[1, 2], [3, 4]]))
report("outer too short", lambda: nested([[1, 2]]))
report("four levels", lambda: deep([[[[9]]]]))


def bound_by_as(subject):
    match subject:
        case [first, *_] as whole:
            return (first, whole is subject, type(whole).__name__)
        case _:
            return "no match"


print("=== what `as` binds after a sequence pattern")
report("as binds the subject itself", lambda: bound_by_as([1, 2, 3]))
report("as over a tuple", lambda: bound_by_as((1, 2, 3)))


def guarded(subject):
    match subject:
        case [first, second] if first > second:
            return ("guard passed", (first, second))
        case [first, second]:
            return ("guard failed, second case", first)
        case _:
            return "no match"


print("=== a guard after a sequence pattern")
report("guard passes", lambda: guarded([9, 1]))
report("guard fails", lambda: guarded([1, 9]))
report("nothing matches", lambda: guarded([1]))


def or_inside(subject):
    match subject:
        case [1 | 2, third]:
            return third
        case _:
            return "no match"


def or_across(subject):
    match subject:
        case [single] | [single, _]:
            return single
        case _:
            return "no match"


print("=== or-patterns inside a sequence")
report("first alternative", lambda: or_inside([1, "x"]))
report("second alternative", lambda: or_inside([2, "y"]))
report("neither alternative", lambda: or_inside([3, "z"]))
report("one element", lambda: or_across([7]))
report("two elements", lambda: or_across([7, 8]))


def len_and_getitem_are_never_asked(subject):
    match subject:
        case [_]:
            return "matched"
        case _:
            return "no match"


print("=== the written class is refused before its dunders are read")
report("indexed of length one", lambda: len_and_getitem_are_never_asked(Indexed(1)))
