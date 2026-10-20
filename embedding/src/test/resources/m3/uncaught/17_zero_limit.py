# Zero entries means no header: there is no trace to introduce.
import sys

def run():
    raise ValueError('no frames')

sys.tracebacklimit = 0
run()
