import sys

# The name taken away entirely is not the same as a hook that prints nothing: CPython says the
# reader was owed a report and got none.
del sys.excepthook
print("the original is still filed:", repr(sys.__excepthook__.__name__))


def inner():
    raise ValueError("printed by the interpreter")


inner()
