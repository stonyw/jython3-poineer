# A bool is an integer here, so True means the innermost frame and nothing above it.
import sys

sys.tracebacklimit = True

def a():
    raise ValueError('deep')

def b():
    return a()

b()
