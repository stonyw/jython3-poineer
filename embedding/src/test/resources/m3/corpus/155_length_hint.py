"""The count list() asks for before it fills, and the constructors that do not ask.

3.14 asks len() first: a class whose __len__ answers never hears __length_hint__, even when it
declares one. Only a TypeError from len -- no __len__ at all -- lets the hint be asked, and the
hint's own refusals are CPython's three: not an int, negative, or too wide for a machine word.
tuple, set and frozenset never ask at all; list, [*x], (*x,) and bytes(x) do.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


class Order:
    def __iter__(self):
        print("  Order.__iter__")
        return iter([7, 8])

    def __len__(self):
        print("  Order.__len__")
        return 2


print("list", list(Order()))
print("star", [*Order()])
print("star-tail", [0, *Order()])
print("tuple-display", (*Order(),))
extended = []
extended.extend(Order())
print("extend", extended)
started = [0]
started.__init__(Order())
print("init", started)


class Both:
    def __iter__(self):
        return iter([1, 2])

    def __len__(self):
        print("  Both.__len__")
        return 2

    def __length_hint__(self):
        print("  Both.__length_hint__")
        return 9


print("both", list(Both()))


class LenTypeError:
    def __iter__(self):
        return iter([1, 2])

    def __len__(self):
        raise TypeError("no length here")

    def __length_hint__(self):
        print("  LenTypeError.__length_hint__")
        return 2


print("len-type-error", list(LenTypeError()))


class LenValueError:
    def __iter__(self):
        return iter([1, 2])

    def __len__(self):
        raise ValueError("length refused")

    def __length_hint__(self):
        print("  LenValueError.__length_hint__")
        return 2


attempt("len-value-error", lambda: list(LenValueError()))


class LenNegative:
    def __iter__(self):
        return iter([1])

    def __len__(self):
        return -1


attempt("len-negative", lambda: list(LenNegative()))


class HintString:
    def __iter__(self):
        return iter([1])

    def __length_hint__(self):
        return "two"


class HintFloat:
    def __iter__(self):
        return iter([1])

    def __length_hint__(self):
        return 2.0


class HintNegative:
    def __iter__(self):
        return iter([1])

    def __length_hint__(self):
        return -1


class HintHuge:
    def __iter__(self):
        return iter([1])

    def __length_hint__(self):
        return 2 ** 100


class HintHugeNegative:
    def __iter__(self):
        return iter([1])

    def __length_hint__(self):
        return -(2 ** 100)


class HintTrue:
    def __iter__(self):
        return iter([1])

    def __length_hint__(self):
        return True


class HintNone:
    def __iter__(self):
        return iter([1])

    __length_hint__ = None


class HintInstanceOnly:
    def __init__(self):
        self.__length_hint__ = lambda: "not seen"

    def __iter__(self):
        return iter([5])


attempt("hint-string", lambda: list(HintString()))
attempt("hint-float", lambda: list(HintFloat()))
attempt("hint-negative", lambda: list(HintNegative()))
attempt("hint-huge", lambda: list(HintHuge()))
attempt("hint-huge-negative", lambda: list(HintHugeNegative()))
attempt("hint-true", lambda: list(HintTrue()))
attempt("hint-none", lambda: list(HintNone()))
attempt("hint-instance-only", lambda: list(HintInstanceOnly()))


class Refused:
    def __iter__(self):
        return iter(["a", "b"])

    def __length_hint__(self):
        raise ValueError("hint refused")


attempt("list-refused", lambda: list(Refused()))
print("tuple", tuple(Refused()))
print("set", sorted(set(Refused())))
print("frozenset", sorted(frozenset(Refused())))
attempt("star-refused", lambda: [*Refused()])
attempt("bytes-refused", lambda: bytes(Refused()))
