# A limit that is not a number is no limit: every frame is shown, as if nothing was set.
import sys

def a():
    raise ValueError('deep')

def b():
    return a()

sys.tracebacklimit = 'not a number'
b()
