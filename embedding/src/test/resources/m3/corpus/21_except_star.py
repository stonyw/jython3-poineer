"""`except*` over a group: which clause runs, what is left over, and what escapes when the clauses are done.

A clause does not catch a group. It catches the *part* of it that matches, and the rest travels on: a
naked exception is wrapped in a group of one before a clause is asked, two clauses over one group each see
their own half, and the value bound by `as` is always a group, even for the single `ValueError` that came
out of nowhere. What leaves the statement is decided once every clause has run, by comparing each
exception's metadata with the group it was split out of — the one that still carries that traceback is the
same exception travelling on and keeps its place in the tree, and everything new is gathered beside it.

The two refusals of a clause's types are ordered, which is why `except* (int, ExceptionGroup)` complains
about `int` and never reaches the group it would also have refused.
"""


def leaves(value):
    """Every exception the tree has at its bottom, in the order a reader meets them."""
    out = []
    stack = [value]
    while stack:
        each = stack.pop()
        if isinstance(each, BaseExceptionGroup):
            stack.extend(reversed(each.exceptions))
        else:
            out.append(each)
    return out


def show(value):
    return type(value).__name__ + ": " + repr(value)


def report(label, function):
    print("-- " + label)
    try:
        function()
        print("   nothing escaped")
    except BaseException as error:
        print("   escaped " + show(error))
        for each in leaves(error):
            print("   leaf " + type(each).__name__ + ": " + str(each))


print("=== a clause sees a group even when a single exception was raised")


def naked():
    try:
        raise ValueError("naked")
    except* ValueError as caught:
        print("   bound", type(caught).__name__, repr(caught))
        print("   child", type(caught.exceptions[0]).__name__, caught.exceptions[0])


report("naked", naked)


print("=== two clauses, each taking its half")


def halves():
    try:
        raise ExceptionGroup("outer", [ValueError("v"), KeyError("k")])
    except* ValueError as caught:
        print("   first", len(caught.exceptions), caught)
    except* KeyError as caught:
        print("   second", len(caught.exceptions), caught)


report("halves", halves)


print("=== a clause that raises, beside one that handles")


def one_raises():
    try:
        raise ExceptionGroup("outer", [ValueError("v"), KeyError("k")])
    except* ValueError:
        print("   handles")
    except* KeyError:
        print("   reraises")
        raise TypeError("from clause")


report("one raises", one_raises)


print("=== a clause that re-raises the exception it was handed")


def reraises_the_clause():
    try:
        raise ExceptionGroup("outer", [ValueError("v"), KeyError("k")])
    except* ValueError:
        raise
    except* KeyError:
        print("   key handled")


report("bare raise in a clause", reraises_the_clause)


print("=== a clause that raises something new, over a group that is itself a group")


def raises_a_group():
    try:
        raise ExceptionGroup("outer", [ValueError("v"), KeyError("k"), TypeError("t")])
    except* ValueError:
        raise ExceptionGroup("second", [IndexError("i")])
    except* KeyError:
        print("   key handled")


report("clause raises a group", raises_a_group)


print("=== what is left of a nested group, and the clause that closes over it")


def nested():
    try:
        try:
            raise ExceptionGroup("inner", [ValueError("v")])
        except* ValueError:
            print("   inner clause")
            raise RuntimeError("inner raised")
    except* BaseException as caught:
        print("   outer saw", repr(caught))


report("nested", nested)


print("=== a tuple clause takes everything it names")


def tuple_clause():
    try:
        raise ExceptionGroup("outer", [ValueError("v"), TypeError("t")])
    except* (ValueError, TypeError) as caught:
        print("   matched", len(caught.exceptions))


report("tuple clause", tuple_clause)


print("=== a clause that matches nothing leaves the group as it was")


def no_match():
    try:
        raise ExceptionGroup("outer", [ValueError("v")])
    except* TypeError as caught:
        print("   never runs", caught)


report("no match", no_match)


print("=== an error made inside a clause replaces the clause's own work")


def fresh_error():
    try:
        raise ExceptionGroup("outer", [ValueError("v")])
    except* ValueError:
        1 / 0


report("fresh error", fresh_error)


print("=== a base group whose two children each get a clause")


def base_group():
    try:
        raise BaseExceptionGroup("base", [KeyboardInterrupt("ki"), ValueError("v")])
    except* KeyboardInterrupt:
        print("   ki clause")
    except* ValueError:
        print("   v clause")


report("base group", base_group)


print("=== the current exception a clause is running for")


def current():
    import sys

    try:
        raise ExceptionGroup("outer", [ValueError("v")])
    except* ValueError:
        kind, value, traceback = sys.exc_info()
        print("   exc_info", kind.__name__, repr(value), traceback is not None)


report("current", current)


print("=== a clause body that finishes is not an exception at all")


def quiet():
    try:
        raise ExceptionGroup("outer", [ValueError("v"), KeyError("k")])
    except* ValueError:
        print("   first body")
    except* KeyError:
        print("   second body")
    print("   after the statement")


report("quiet", quiet)


print("=== try/except* with a finally")


def finally_after():
    try:
        raise ExceptionGroup("outer", [ValueError("v"), KeyError("k")])
    except* ValueError:
        print("   clause with finally")
    finally:
        print("   finally ran")


report("finally", finally_after)


print("=== the two refusals a clause's types get")


def refuses_non_exception():
    try:
        raise ValueError("v")
    except* int:
        print("   never")


report("except* int", refuses_non_exception)


def refuses_group():
    try:
        raise ExceptionGroup("outer", [ValueError("v")])
    except* ExceptionGroup:
        print("   never")


report("except* ExceptionGroup", refuses_group)


def refuses_in_order():
    try:
        raise ValueError("v")
    except* (int, ExceptionGroup):
        print("   never")


report("except* (int, ExceptionGroup)", refuses_in_order)


def refuses_base_group():
    try:
        raise ValueError("v")
    except* BaseExceptionGroup:
        print("   never")


report("except* BaseExceptionGroup", refuses_base_group)


print("=== a class a person wrote, matched by a clause")


class Loud(Exception):
    pass


def written():
    try:
        raise ExceptionGroup("outer", [Loud("shout"), ValueError("v")])
    except* Loud as caught:
        print("   loud", repr(caught))


report("written class", written)


print("=== a group subclass split by a clause that names it")


class Bundle(ExceptionGroup):
    pass


def subclassed():
    try:
        raise Bundle("bundle", [ValueError("v"), KeyError("k")])
    except* Bundle as caught:
        print("   whole bundle", repr(caught))


report("clause names a group subclass", subclassed)
