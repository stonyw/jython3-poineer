import sys

# The interrupt does not have to come from the program: a hook that raises one is noted the same way,
# and the two reports are still written.
def interrupter(kind, value, tb):
    raise KeyboardInterrupt("from the hook")


sys.excepthook = interrupter
raise ValueError("plain")
