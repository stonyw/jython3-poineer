import sys

def tell(kind, value, tb):
    named = getattr(kind, "__name__", None)
    print("hooked", named if named is not None else type(kind).__name__,
          str(value), type(tb).__name__)

print(sys.excepthook is sys.__excepthook__)
sys.excepthook = tell
print(tell is sys.excepthook, sys.__excepthook__ is tell)
print(sys.excepthook.__name__, sys.excepthook.__qualname__)

# A replaced hook is what a direct call reaches; the original is still filed under its own name.
sys.excepthook(ValueError, ValueError("plain"), None)
sys.__excepthook__(ValueError, ValueError("original"), None)

# The name can be taken away entirely, which is how a program declines to have its error reported.
del sys.excepthook
try:
    sys.excepthook
except AttributeError as missing:
    print("missing ->", missing)
print(hasattr(sys, "__excepthook__"))
sys.__excepthook__(ValueError, ValueError("still there"), None)
