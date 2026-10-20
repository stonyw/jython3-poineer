# sys.tracebacklimit keeps the last entries, counting from the failing frame.
import sys

def a():
    raise ValueError('deep')

def b():
    return a()

def c():
    return b()

sys.tracebacklimit = 2
c()
