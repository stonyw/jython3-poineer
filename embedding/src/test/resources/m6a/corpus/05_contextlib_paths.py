# M6-a slice 2: the three ways `contextlib` is most often used, now that `os`, `functools.wraps`,
# `collections.deque` and `types.MethodType` lie under it. The log records the order calls arrive in,
# which is the part each construct is really about.

import sys

# The reference interpreter's own library, exactly as the M4 corpora do it, so both sides read the
# same `contextlib.py`, `os.py` and `functools.py`.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

from contextlib import ExitStack, contextmanager, suppress

log = []


@contextmanager
def opened(name):
    log.append("enter " + name)
    try:
        yield name
    finally:
        log.append("exit " + name)


with opened("a") as value:
    print("value", value)
print("contextmanager", log)

with suppress(ValueError):
    raise ValueError("hidden")
print("suppress", "done")

resources = []
stack = ExitStack()
stack.callback(lambda: resources.append("first"))
stack.callback(lambda: resources.append("second"))
with stack:
    print("inside", len(resources))
print("closing", resources)

with ExitStack() as entered:
    print("entered", entered.enter_context(opened("b")))
print("exitstack", log)
