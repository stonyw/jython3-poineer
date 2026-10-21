import sys

sys.tracebacklimit = 1


def inner():
    raise ExceptionGroup("outer", [ValueError("v"), KeyError("k")])


def outer():
    inner()


outer()
