"""The thread's current exception: who sets it, who restores it, who can see it."""

import sys


print("outside:", sys.exc_info())


def show(where):
    info = sys.exc_info()
    print(where, info[0].__name__ if info[0] is not None else None, info[1])


def handler():
    show("before the try")
    try:
        raise ValueError("caught")
    except ValueError:
        show("inside the handler")
        try:
            raise KeyError("nested")
        except KeyError:
            show("inside the nested handler")
        show("back in the outer handler")
    show("after the handler")


def bare_reraise():
    try:
        raise TypeError("original")
    except TypeError:
        try:
            raise
        except TypeError as error:
            show("caught the re-raise")
            print("same object:", str(error))


def reraise_out():
    try:
        raise RuntimeError("escape")
    except RuntimeError:
        raise


def lasti_after():
    order = []
    try:
        order.append("in try")
        raise ValueError("late")
    except ValueError:
        order.append("in handler")
    finally:
        order.append("in finally")
    return order


show("inside a plain call")
handler()
bare_reraise()
print("after everything:", sys.exc_info())
print(lasti_after())

try:
    reraise_out()
except RuntimeError as error:
    print("caught the escape:", error)
    print("still current:", sys.exc_info()[1])
