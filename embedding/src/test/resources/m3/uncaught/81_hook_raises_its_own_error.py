import sys


def noisy(kind, value, tb):
    print("the hook began")
    raise RuntimeError("inside the hook")


sys.excepthook = noisy
raise KeyError("escaped")
