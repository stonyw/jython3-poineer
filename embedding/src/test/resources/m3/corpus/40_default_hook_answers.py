import sys

# The hook the interpreter installs for itself, asked directly rather than through an escape.
print(type(sys.excepthook).__name__, repr(sys.excepthook))
print(sys.excepthook.__name__, sys.excepthook.__qualname__)
print(sys.excepthook.__doc__)
print(sys.excepthook is sys.__excepthook__)

# A value that never travelled has no frames to show, so the report is the complaint alone.
sys.excepthook(ValueError, ValueError("plain"), None)

# The class argument is not what names the complaint: PyErr_Display is given the value.
sys.excepthook(TypeError, KeyError("kept"), None)

# A note travels with the value, so the report the hook writes ends with the note.
noted = ValueError("noted")
noted.add_note("by the hook")
sys.excepthook(ValueError, noted, None)

# A value that is not an exception at all: the Python printer refuses it and the display says so once.
sys.excepthook(int, 5, None)
sys.excepthook(type, ValueError, None)
sys.excepthook(str.__class__, "text", None)

# The argument table counts and names itself bare; a keyword is refused before the count is looked at.
for n in range(0, 5):
    try:
        sys.excepthook(*([ValueError("three")] * n))
        print(n, "-> no refusal")
    except TypeError as refusal:
        print(n, "->", refusal)
try:
    sys.excepthook(ValueError("a"), value=ValueError("a"), traceback=None)
except TypeError as refusal:
    print("keyword ->", refusal)

# An empty unpacking leaves no names behind, so the call is refused by count rather than for a keyword.
try:
    sys.excepthook(**{})
except TypeError as refusal:
    print("nothing unpacked ->", refusal)
try:
    sys.excepthook(ValueError("a"), *[ValueError("b")], **{})
except TypeError as refusal:
    print("two unpacked ->", refusal)
