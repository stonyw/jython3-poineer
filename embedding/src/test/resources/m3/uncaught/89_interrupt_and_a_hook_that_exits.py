import sys

# An interrupt is noted before the hook is read, but a hook that exits the program still decides the
# status: the process leaves through the exit, and the signal is never raised.
def quitter(kind, value, tb):
    print("the hook leaves for", kind.__name__)
    sys.exit(7)


sys.excepthook = quitter
raise KeyboardInterrupt("interrupt and leave")
