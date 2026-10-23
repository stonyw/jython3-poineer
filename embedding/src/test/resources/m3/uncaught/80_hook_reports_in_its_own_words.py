import sys

# A hook that reports in its own words replaces the whole of the interpreter's text.
def tell(kind, value, tb):
    print("called for", kind.__name__, "with", repr(value))
    print("the traceback is the one the value carries:", tb is value.__traceback__)
    print("the frames above the raise:", type(tb).__name__)


sys.excepthook = tell
print("the four last_ names are not there yet:", hasattr(sys, "last_exc"),
      hasattr(sys, "last_type"), hasattr(sys, "last_value"),
      hasattr(sys, "last_traceback"))


def deep():
    raise ValueError("boom")


deep()
