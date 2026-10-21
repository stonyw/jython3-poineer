"""`split`, `subgroup` and `derive`: the three questions a group answers about its own children.

A matcher is a class, a tuple of classes, or a callable that is not a class, and the third of those is the
only one that gets called per exception. A full match takes the whole value rather than walking it, so
`split(ValueError)` over a group of `ValueError`s hands back the group it was asked about — and a matcher
that takes nothing hands back `None` beside the whole.

The parts are always built through `BaseExceptionGroup`, never through the class they came from, which is
what makes a subset of an `Exception` group read back as an `ExceptionGroup` and a group subclass get no
subclass at all. A part keeps the original's traceback, cause, context and a copy of its notes — all hung
on as fields, which is why a part does not inherit the original's decision to hide its context.
"""

V = ValueError("v")
T = TypeError("t")
KI = KeyboardInterrupt("ki")
SE = SystemExit("s")


class My(ExceptionGroup):
    pass


def shape(value):
    """An exception, a group or None, as names and messages rather than as addresses."""
    if isinstance(value, BaseExceptionGroup):
        return (
            type(value).__name__
            + "("
            + repr(value.message)
            + ", ["
            + ", ".join(shape(each) for each in value.exceptions)
            + "])"
        )
    if isinstance(value, BaseException):
        return type(value).__name__ + "(" + repr(str(value)) + ")"
    return repr(value)


def spell(pair):
    """The two halves of a split, as the reader meets them."""
    return "(" + shape(pair[0]) + ", " + shape(pair[1]) + ")"


def show(label, function):
    try:
        print("  " + label + " -> " + shape(function()))
    except BaseException as error:
        print("  " + label + " -> " + type(error).__name__ + ": " + str(error))


mixed = BaseExceptionGroup("mixed", [V, KI, T])
nested = BaseExceptionGroup(
    "outer", [BaseExceptionGroup("inner", [V, T]), KeyError("k"), BaseExceptionGroup("deep", [KI])]
)

print("=== split takes the matching half and leaves the rest")
show("one of three", lambda: mixed.split(ValueError))
show("a class that is not there", lambda: mixed.split(IndexError))
show("every child", lambda: mixed.split(Exception))
show("BaseException takes all", lambda: mixed.split(BaseException))
show("a tuple", lambda: mixed.split((ValueError, TypeError)))
show("a callable", lambda: mixed.split(lambda e: isinstance(e, ValueError)))
show("a callable that refuses all", lambda: mixed.split(lambda e: False))
show("an exception has no split", lambda: V.split(ValueError))

print("=== subgroup keeps the matching half alone")
show("subgroup ValueError", lambda: mixed.subgroup(ValueError))
show("subgroup KeyError", lambda: mixed.subgroup(KeyError))
show("subgroup all", lambda: mixed.subgroup(BaseException))
show("subgroup nothing", lambda: mixed.subgroup(IndexError))
show("subgroup callable", lambda: mixed.subgroup(lambda e: e.args[0] == "t"))

print("=== a nested group is walked, not matched as a whole")
show("nested split ValueError", lambda: nested.split(ValueError))
show("nested subgroup KeyError", lambda: nested.subgroup(KeyError))
show("nested subgroup KeyboardInterrupt", lambda: nested.subgroup(KeyboardInterrupt))
show("nested subgroup Exception", lambda: nested.subgroup(Exception))
show("nested split of a group class", lambda: nested.split(BaseExceptionGroup))

print("=== the class a part is made as")
show("EG of exceptions", lambda: type(My("m", [V, T]).split(ValueError)[0]).__name__)
show("EG with a base child", lambda: type(My("m", [V, KI]).subgroup(BaseException)).__name__)
show("subclass derive", lambda: type(My("m", [V]).derive([T])).__name__)
show("EG derive a base", lambda: type(ExceptionGroup("m", [V]).derive([KI])).__name__)
show("EG derive an exception", lambda: type(ExceptionGroup("m", [V]).derive([T])).__name__)
show("derive keeps the message", lambda: mixed.derive([V]).message)
show("derive refuses a non-sequence", lambda: mixed.derive(3))
show("derive refuses a non-exception", lambda: mixed.derive([1]))

print("=== what a part inherits")
try:
    raise mixed
except BaseException as raised:
    raised.__notes__ = ["one"]
    part = raised.subgroup(ValueError)
    show("same traceback", lambda: part.__traceback__ is raised.__traceback__)
    show("same context", lambda: part.__context__ is raised.__context__)
    show("same cause", lambda: part.__cause__ is raised.__cause__)
    show("notes copied", lambda: part.__notes__ is not raised.__notes__)
    show("notes equal", lambda: part.__notes__ == raised.__notes__)
    show("message kept", lambda: part.message)
    show("children", lambda: [type(each).__name__ for each in part.exceptions])
    show("a part of nothing", lambda: raised.subgroup(IndexError))
    show("suppress context is left alone", lambda: part.__suppress_context__)

print("=== the matchers a split refuses")
show("not a type or a callable", lambda: mixed.split(3))
show("a string", lambda: mixed.split("ValueError"))
show("a tuple with a non-class", lambda: mixed.split((ValueError, 3)))
show("an empty tuple", lambda: mixed.split(()))
show("a class that is not an exception", lambda: mixed.split(int))
show("an instance", lambda: mixed.split(V))
show("a callable that is a class", lambda: mixed.split(type(V)))

print("=== the arguments the three methods refuse")
show("split with nothing", lambda: mixed.split())
show("split with two", lambda: mixed.split(ValueError, TypeError))
show("split by keyword", lambda: mixed.split(matcher=ValueError))
show("subgroup with nothing", lambda: mixed.subgroup())
show("subgroup with two", lambda: mixed.subgroup(ValueError, TypeError))
show("derive with nothing", lambda: mixed.derive())
show("derive with two", lambda: mixed.derive([V], [T]))
show("derive by keyword", lambda: mixed.derive(excs=[V]))

print("=== a method asked of the wrong kind of value")
show("unbound split", lambda: BaseExceptionGroup.split)
show("split of a plain exception", lambda: BaseExceptionGroup.split(V, ValueError))
show("subgroup of a plain exception", lambda: BaseExceptionGroup.subgroup(V, ValueError))
show("derive of a plain exception", lambda: BaseExceptionGroup.derive(V, [T]))
show("split of a class", lambda: BaseExceptionGroup.split(ValueError, ValueError))
