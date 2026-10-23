import sys

# The four last_ names are filed before the hook is read, so a hook can look at the exception it was
# called for through them and find the very same objects it was handed.
def reporter(kind, value, tb):
    print(sys.last_exc is value, sys.last_type is kind, sys.last_value is value,
          sys.last_traceback is tb, type(sys.last_traceback).__name__)
    print(type(sys.last_exc).__name__, sys.last_type.__name__, sys.last_value.args)
    raise RuntimeError("after the last names")


sys.excepthook = reporter
raise ValueError("plain")
