def deep():
    raise ValueError("v")


def build():
    try:
        deep()
    except ValueError as e:
        first = e
    first.__notes__ = ["note one", "two\nlines"]
    try:
        raise KeyError("k")
    except KeyError as e:
        second = e
    second.__cause__ = first
    return ExceptionGroup("outer", [first, second])


raise ExceptionGroup(
    "top",
    [build(), ExceptionGroup("mid", [ValueError("nested deep"), ExceptionGroup("inner", [KeyError("q")])])],
)
