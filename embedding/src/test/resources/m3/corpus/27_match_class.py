"""Class patterns: the matcher is asked first, then the attributes the pattern names are read.

Three questions come out of this in a fixed order. The thing written before the parentheses has to be a
class at all — even when the pattern names nothing inside it, a matcher that is an ordinary object is a
`TypeError` rather than a failed match. The subject then has to be an instance of it, and a subject that
is not simply fails the pattern. Only after both does the pattern ask for a value.

The positionals are read through `__match_args__`, so their meaning is the class's, not the pattern's;
the keywords name attributes directly. A class that writes no `__match_args__` accepts no positional at
all, and a built-in that writes none either — `list`, `dict`, `str`, `int` — answers by handing back the
subject itself, which is how `case list(rest)` works. An attribute the subject does not have ends the
pattern the way a wrong value does; an attribute whose read raises is an error, and it leaves.
"""


def report(label, function):
    try:
        print("   " + label + " -> " + repr(function()))
    except BaseException as error:
        print("   " + label + " -> " + type(error).__name__ + ": " + str(error))


class Args:
    __match_args__ = ("first", "second")

    def __init__(self, first=None, second=None):
        self.first = first
        self.second = second


class OneArg:
    __match_args__ = ("only",)

    def __init__(self, only=None):
        self.only = only


class Plain:
    def __init__(self, a=None, b=None):
        self.a = a
        self.b = b


class Base:
    pass


class Child(Base):
    pass


def matcher_of(matcher, subject):
    match subject:
        case matcher():
            return "instance of the matcher"
        case _:
            return "no match"


def positional_of(matcher, subject):
    match subject:
        case matcher(x):
            return (x is subject, type(x).__name__)
        case _:
            return "no match"


print("=== the matcher has to be a class before anything else")
report("an instance used as a matcher, no sub-patterns", lambda: matcher_of(Args(1, 2), Args(3, 4)))
report("an instance used as a matcher, one sub-pattern", lambda: positional_of(Args(1, 2), Args(3, 4)))
report("a string used as a matcher", lambda: positional_of("not a class", Args(3, 4)))
report("a function used as a matcher", lambda: positional_of(report, 7))

print("=== the subject has to be an instance of the matcher")
report("the class itself written in a matcher position", lambda: positional_of(Child, Child))
report("a parent class matching a child instance", lambda: matcher_of(Base, Child()))
report("a child class against a parent instance", lambda: matcher_of(Child, Base()))
report("an int against a written class", lambda: matcher_of(Base, 7))


def two_positionals(subject):
    match subject:
        case Args(first_value, second_value):
            return (first_value, second_value)
        case _:
            return "no match"


def one_of_two(subject):
    match subject:
        case Args(only):
            return only
        case _:
            return "no match"


def keywords_spelled_backwards(subject):
    match subject:
        case Args(second=second_value, first=first_value):
            return (first_value, second_value)
        case _:
            return "no match"


def mixed(subject):
    match subject:
        case Args(positional, second=keyword):
            return (positional, keyword)
        case _:
            return "no match"


print("=== the positionals are read in the order __match_args__ spells")
report("two positionals", lambda: two_positionals(Args(1, 2)))
report("one of two", lambda: one_of_two(Args(1, 2)))
report("keywords spelled in the other order", lambda: keywords_spelled_backwards(Args(1, 2)))
report("a positional and a keyword", lambda: mixed(Args(1, 2)))


def plain_positional(subject):
    match subject:
        case Plain(x):
            return x
        case _:
            return "no match"


def plain_two_positionals(subject):
    match subject:
        case Plain(x, y):
            return (x, y)
        case _:
            return "no match"


def plain_keyword(subject):
    match subject:
        case Plain(a=value):
            return value
        case _:
            return "no match"


print("=== a written class that spells no __match_args__ takes no positional")
report("one positional", lambda: plain_positional(Plain(1, 2)))
report("two positionals", lambda: plain_two_positionals(Plain(1, 2)))
report("a keyword by attribute name", lambda: plain_keyword(Plain(1, 2)))


def one_arg_two(subject):
    match subject:
        case OneArg(x, y):
            return (x, y)
        case _:
            return "no match"


def args_three(subject):
    match subject:
        case Args(x, y, z):
            return (x, y, z)
        case _:
            return "no match"


print("=== more positionals than the class allows")
report("two against a one-argument matcher", lambda: one_arg_two(OneArg(9)))
report("three against a two-argument matcher", lambda: args_three(Args(1, 2)))


class BadTuple:
    __match_args__ = ["first", "second"]

    def __init__(self):
        self.first = 1


class IntElement:
    __match_args__ = (1,)

    def __init__(self):
        self.first = 1


class TupleElement:
    __match_args__ = ("ok", (2,))

    def __init__(self):
        self.ok = 1


class EmptyArgs:
    __match_args__ = ()


def bad_positional(matcher, subject):
    match subject:
        case matcher(x):
            return x
        case _:
            return "no match"


def two_of_a_mixed_match_args(matcher, subject):
    match subject:
        case matcher(x, y):
            return (x, y)
        case _:
            return "no match"


def empty_keyword(subject):
    match subject:
        case EmptyArgs(a=value):
            return value
        case _:
            return "no match"


print("=== a __match_args__ that is not what the runtime wants")
report("__match_args__ is a list", lambda: bad_positional(BadTuple, BadTuple()))
report("__match_args__ is a list, subject not an instance", lambda: bad_positional(BadTuple, 7))
report("an element is an int", lambda: bad_positional(IntElement, IntElement()))
report("the second element is a tuple", lambda: two_of_a_mixed_match_args(TupleElement, TupleElement()))
report("__match_args__ is empty, one positional", lambda: bad_positional(EmptyArgs, EmptyArgs()))
report("__match_args__ is empty, a keyword", lambda: empty_keyword(EmptyArgs()))


def duplicate_attribute(subject):
    match subject:
        case Args(captured, first=other):
            return (captured, other)
        case _:
            return "no match"


def two_distinct_keywords(subject):
    match subject:
        case Args(first=one, second=two):
            return (one, two)
        case _:
            return "no match"


print("=== the same attribute named by a positional and a keyword")
report("both name 'first'", lambda: duplicate_attribute(Args(1, 2)))
report("each names its own", lambda: two_distinct_keywords(Args(1, 2)))


class Holder:
    class Inner:
        __match_args__ = ("value",)

        def __init__(self, value=None):
            self.value = value


def dotted_matcher(subject):
    match subject:
        case Holder.Inner(x):
            return x
        case _:
            return "no match"


print("=== a matcher named with dots")
report("dotted", lambda: dotted_matcher(Holder.Inner(3)))
report("dotted, wrong subject", lambda: dotted_matcher(7))


class Classy:
    plain = 7

    @property
    def size(self):
        return 42

    @classmethod
    def kind(cls):
        return "a classmethod"

    @staticmethod
    def flat():
        return "flat"


def reads_property(subject):
    match subject:
        case Classy(size=value):
            return value
        case _:
            return "no match"


def reads_plain(subject):
    match subject:
        case Classy(plain=value):
            return value
        case _:
            return "no match"


def reads_kind(subject):
    match subject:
        case Classy(kind=value):
            return (type(value).__name__, value())
        case _:
            return "no match"


def reads_flat(subject):
    match subject:
        case Classy(flat=value):
            return (type(value).__name__, value())
        case _:
            return "no match"


print("=== what the pattern reads off the subject")
report("a property", lambda: reads_property(Classy()))
report("a plain attribute", lambda: reads_plain(Classy()))
report("a classmethod", lambda: reads_kind(Classy()))
report("a staticmethod", lambda: reads_flat(Classy()))


class Half:
    __match_args__ = ("present", "absent")

    def __init__(self):
        self.present = 1


def missing_keyword(subject):
    match subject:
        case Half(absent=value):
            return value
        case _:
            return "no match"


def second_positional_missing(subject):
    match subject:
        case Half(one, two):
            return (one, two)
        case _:
            return "no match"


def first_positional(subject):
    match subject:
        case Half(one):
            return one
        case _:
            return "no match"


def holds_none(subject):
    match subject:
        case Args(value):
            return ("bound", value)
        case _:
            return "no match"


print("=== an attribute the subject does not have ends the pattern")
report("a keyword naming nothing", lambda: missing_keyword(Half()))
report("a second positional naming nothing", lambda: second_positional_missing(Half()))
report("a first positional naming something", lambda: first_positional(Half()))
report("an attribute holding none", lambda: holds_none(Args()))


def dict_keyword(subject):
    match subject:
        case dict(a=value):
            return value
        case _:
            return "no match"


print("=== a class pattern reads attributes, never keys")
report("dict(a=...) over a dict holding key 'a'", lambda: dict_keyword({"a": 1}))


class Sub(Args):
    pass


class Replaced(Args):
    __match_args__ = ("second", "first")


def through_a_subclass(subject):
    match subject:
        case Sub(x, y):
            return (x, y)
        case _:
            return "no match"


def through_a_replacement(subject):
    match subject:
        case Replaced(x, y):
            return (x, y)
        case _:
            return "no match"


print("=== an inherited and a replaced __match_args__")
report("inherited order", lambda: through_a_subclass(Sub(1, 2)))
report("replaced order", lambda: through_a_replacement(Replaced(1, 2)))


class NamedByMeta(type):
    __match_args__ = ("from_meta",)


class ThroughMeta(metaclass=NamedByMeta):
    def __init__(self, from_meta=None):
        self.from_meta = from_meta


class OwnWins(metaclass=NamedByMeta):
    __match_args__ = ("own",)

    def __init__(self, own=None):
        self.own = own


def through_the_metaclass(subject):
    match subject:
        case ThroughMeta(x):
            return x
        case _:
            return "no match"


def the_class_answers_first(subject):
    match subject:
        case OwnWins(x):
            return x
        case _:
            return "no match"


print("=== a __match_args__ the metaclass spells")
report("found on the metaclass", lambda: through_the_metaclass(ThroughMeta(5)))
report("the class's own answer wins", lambda: the_class_answers_first(OwnWins(6)))
report("a foreign subject never reaches it", lambda: through_the_metaclass(7))


class Gate(type):
    def __instancecheck__(cls, other):
        return True


class Everything(metaclass=Gate):
    __match_args__ = ("attr",)

    def __init__(self, attr=None):
        self.attr = attr


class Foreign:
    attr = "read off a foreign subject"


def through_the_gate(subject):
    match subject:
        case Everything(x):
            return x
        case _:
            return "no match"


print("=== a metaclass hook opens the gate to anything")
report("its own instance", lambda: through_the_gate(Everything("held")))
report("another class holding the attribute", lambda: through_the_gate(Foreign()))
report("another class not holding it", lambda: through_the_gate(7))


def list_two(subject):
    match subject:
        case list(x, y):
            return (x, y)
        case _:
            return "no match"


def builtins_answer_themselves(matcher, subject, label):
    report(label, lambda: positional_of(matcher, subject))


print("=== the built-ins that hand back the subject itself")
builtins_answer_themselves(list, [1, 2], "list")
builtins_answer_themselves(tuple, (1, 2), "tuple")
builtins_answer_themselves(str, "ab", "str")
builtins_answer_themselves(bytes, b"ab", "bytes")
builtins_answer_themselves(set, {1}, "set")
builtins_answer_themselves(frozenset, frozenset({1}), "frozenset")
builtins_answer_themselves(dict, {"a": 1}, "dict")
builtins_answer_themselves(int, 7, "int")
builtins_answer_themselves(float, 1.5, "float")
builtins_answer_themselves(bool, True, "bool")
report("one of these asked for two positionals", lambda: list_two([1, 2]))
report("the same, subject not a list", lambda: list_two("ab"))


print("=== the built-ins that do not")
report("range", lambda: positional_of(range, range(2)))
report("complex", lambda: positional_of(complex, 1j))
report("slice", lambda: positional_of(slice, slice(1, 2)))
report("type", lambda: positional_of(type, list))


def list_pattern_of(subject):
    match subject:
        case list([first, second]):
            return (first, second)
        case _:
            return "no match"


print("=== a sub-pattern inside the positional a built-in hands back")
report("list([first, second])", lambda: list_pattern_of([1, 2]))
report("list([first, second]) over three", lambda: list_pattern_of([1, 2, 3]))
report("list([first, second]) over a tuple", lambda: list_pattern_of((1, 2)))


def sequence_of_class_patterns(subject):
    match subject:
        case [Args(left), Args(right)]:
            return (left, right)
        case _:
            return "no match"


def mapping_value_is_a_class_pattern(subject):
    match subject:
        case {"k": Args(x)}:
            return x
        case _:
            return "no match"


def class_pattern_holds_the_others(subject):
    match subject:
        case Args(first=[a, b], second={"key": value}):
            return (a, b, value)
        case _:
            return "no match"


print("=== class patterns among the other patterns")
report("two in a sequence", lambda: sequence_of_class_patterns([Args(1, 2), Args(3, 4)]))
report("as a mapping value", lambda: mapping_value_is_a_class_pattern({"k": Args(5, 6)}))
report("holding a sequence and a mapping", lambda: class_pattern_holds_the_others(Args([7, 8], {"key": 9})))


def bound_by_as(subject):
    match subject:
        case Args(x) as whole:
            return (x, whole is subject)
        case _:
            return "no match"


def guarded(subject):
    match subject:
        case Args(x) if x > 5:
            return ("guard passed", x)
        case Args(x):
            return ("guard failed, second case", x)
        case _:
            return "no match"


print("=== an as and a guard after a class pattern")
report("as binds the subject", lambda: bound_by_as(Args(1, 2)))
report("guard passes", lambda: guarded(Args(9, 2)))
report("guard fails", lambda: guarded(Args(1, 2)))
report("guard on a failed pattern", lambda: guarded(Plain(9, 2)))


def literal_capture_dotted(subject):
    match subject:
        case 3:
            return "the literal three"
        case Holder.Inner:
            return "the class as a value pattern"
        case captured:
            return ("captured", captured)


print("=== the patterns that need no opcode of their own")
report("a literal", lambda: literal_capture_dotted(3))
report("a dotted name compared", lambda: literal_capture_dotted(Holder.Inner))
report("a bare name captures", lambda: literal_capture_dotted("anything"))
