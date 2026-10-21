def one():
    raise ExceptionGroup("outer", [ValueError("v"), KeyError("k")])


def two():
    one()


two()
