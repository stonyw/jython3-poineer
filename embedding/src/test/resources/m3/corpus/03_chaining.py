"""What a handler leaves behind on the exception it caught."""


def fails():
    raise ValueError("inner failure")


def from_clause():
    try:
        fails()
    except ValueError as error:
        raise TypeError("outer failure") from error


def from_none():
    try:
        fails()
    except ValueError:
        raise TypeError("no cause") from None


def implicit():
    try:
        fails()
    except ValueError:
        raise TypeError("chained")


def double_implicit():
    try:
        raise KeyError("first")
    except KeyError:
        try:
            raise IndexError("second")
        except IndexError:
            raise RuntimeError("third")


def report(name, thunk):
    try:
        thunk()
    except BaseException as error:
        cause = error.__cause__
        context = error.__context__
        print(
            name,
            type(error).__name__,
            error,
            "| cause",
            type(cause).__name__ if cause is not None else None,
            cause,
            "| context",
            type(context).__name__ if context is not None else None,
            context,
            "| suppressed",
            error.__suppress_context__,
        )


report("from_clause", from_clause)
report("from_none", from_none)
report("implicit", implicit)
report("double_implicit", double_implicit)


def raise_from_itself():
    try:
        raise ValueError("self")
    except ValueError as error:
        try:
            raise TypeError("after") from error
        except TypeError as outer:
            print("cause identity kept:", outer.__cause__ is error)
            print("the cause still knows its own args:", outer.__cause__.args)


raise_from_itself()


def cause_across_a_call():
    try:
        from_clause()
    except TypeError as error:
        print("two levels up:", type(error).__name__, type(error.__cause__).__name__,
              type(error.__cause__.__context__).__name__)


cause_across_a_call()
