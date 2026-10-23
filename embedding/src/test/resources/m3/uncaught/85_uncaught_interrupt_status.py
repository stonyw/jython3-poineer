import sys

# An interrupt that nobody caught is reported like any other error, but the process leaves through
# the signal, so the status the shell sees is 130 rather than 1.
def inner():
    raise KeyboardInterrupt("interrupted")


inner()
