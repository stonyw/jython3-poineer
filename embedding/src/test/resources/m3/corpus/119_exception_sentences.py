"""The fifty sentences a type of its own has to say about itself, and the two reads of them.

Forty-nine of the names in `builtins` are exception or warning classes whose C file writes a `tp_doc` beside
the `PyTypeObject`, and `super` is the fiftieth: fourteen lines of usage that open with a line shaped exactly
like a usage recipe. None of the fifty carries an argument table, so `type_get_text_signature`
(`Objects/typeobject.c:1972`) answers `None` for every one of them — for `super` because `skip_signature`
walks the first line, finds no `--` rule under the bracket, and gives up, and for the forty-nine because
`find_signature` never finds the class's name followed by a bracket in the first place. `type_get_doc`
(`:1955`) answers the whole text for all fifty, and the dictionary row a static type files under `__doc__`
holds the same text: the two knives cut nothing, so nothing is left to disagree about.

Six of the fifty are not one line at all. `SystemError` runs to four and `super` to fourteen, its last line
ended by a newline; four of the warning categories run to two because the sentence wraps after `semantically`,
`deprecated`, `mostly` or `problems`. The other forty-four are a single line with nothing at the end, and
`ImportWarning`'s is the one of those that ends without a full stop. `ExceptionGroup` is the name in the same
family that says nothing at all — its `tp_doc` is `NULL` in `Objects/exceptions.c` — so it is the one class
left with no text to transcribe.

Neither knife walks a base: `type_get_doc` reads the dictionary of the type being asked, so `ExceptionGroup`,
whose `tp_doc` is `NULL`, answers `None` beside a parent with a sentence, and `class Quiet(ValueError): pass`
answers `None` under a class that had forty-seven characters to say. A class Python wrote keeps no `tp_doc` at
all until `type_new_impl` copies a `__doc__` that holds a string out of the namespace it was handed, which is
why `Loud`, a subclass written with a table in its own docstring, answers that whole text at `__doc__` and
`(a)` at `__text_signature__` — the copy is the heap type's only `tp_doc`, and the second knife never touches
it. A value of a built-in exception reads its class's sentence, since the instance keeps no `__doc__` of its
own and the lookup falls through to the type, and rewriting the name on a static type is refused: the three
last rows are the sentence `type_setattro` answers a `__doc__` write with on a type written down in C.
"""

import builtins

FIFTY = [
    "ArithmeticError",
    "AssertionError",
    "BlockingIOError",
    "BrokenPipeError",
    "BufferError",
    "BytesWarning",
    "ChildProcessError",
    "ConnectionAbortedError",
    "ConnectionError",
    "ConnectionRefusedError",
    "ConnectionResetError",
    "DeprecationWarning",
    "EOFError",
    "EncodingWarning",
    "Exception",
    "FileExistsError",
    "FileNotFoundError",
    "FloatingPointError",
    "FutureWarning",
    "GeneratorExit",
    "ImportWarning",
    "IndexError",
    "InterruptedError",
    "IsADirectoryError",
    "KeyboardInterrupt",
    "LookupError",
    "MemoryError",
    "NotADirectoryError",
    "NotImplementedError",
    "OverflowError",
    "PendingDeprecationWarning",
    "PermissionError",
    "ProcessLookupError",
    "PythonFinalizationError",
    "RecursionError",
    "ReferenceError",
    "ResourceWarning",
    "RuntimeError",
    "RuntimeWarning",
    "StopAsyncIteration",
    "SyntaxWarning",
    "SystemError",
    "TimeoutError",
    "TypeError",
    "UnicodeWarning",
    "UserWarning",
    "ValueError",
    "Warning",
    "ZeroDivisionError",
    "super",
]

# The texts that are more than one line because the sentence wraps, and the class no sentence was written for.
LONG = ["BytesWarning", "FutureWarning", "PendingDeprecationWarning", "SystemError", "UnicodeWarning"]
SILENT = ["ExceptionGroup"]

# Five classes that sit under another class's sentence and file one of their own beside it.
NESTED = ["TabError", "IndentationError", "SyntaxError", "ModuleNotFoundError", "UnboundLocalError"]


def reading(kind):
    """The one text, counted four ways, and what the other knife makes of it."""
    doc = kind.__doc__
    if not isinstance(doc, str):
        return kind.__name__ + " doc=" + repr(doc) + " sig=" + repr(kind.__text_signature__)
    return (
        kind.__name__
        + " chars="
        + str(len(doc))
        + " bytes="
        + str(len(doc.encode("utf-8")))
        + " lines="
        + str(len(doc.split("\n")))
        + " endnl="
        + str(doc.endswith("\n"))
        + " sig="
        + repr(kind.__text_signature__)
    )


print("== the fifty sentences, counted ==")
total = 0
for name in FIFTY:
    kind = getattr(builtins, name)
    total += len(kind.__doc__)
    print(reading(kind))
print("names=" + str(len(FIFTY)) + " chars=" + str(total))

print()
print("== each of the fifty, as its own repr ==")
for name in FIFTY:
    print(name + " " + repr(getattr(builtins, name).__doc__))

print()
print("== the five texts that wrap, and the class that says nothing ==")
for name in LONG + SILENT:
    kind = getattr(builtins, name)
    print(reading(kind))
    print("   filed=" + repr(kind.__dict__["__doc__"] if "__doc__" in kind.__dict__ else None))

print()
print("== the row and the read, for a name that files its own text ==")
for name in ["ValueError", "SyntaxError", "Warning"]:
    kind = getattr(builtins, name)
    row = kind.__dict__["__doc__"]
    print(
        name
        + " rowtype="
        + type(row).__name__
        + " same="
        + str(row is kind.__doc__)
        + " equal="
        + str(row == kind.__doc__)
    )

print()
print("== who reads it: a value, a subclass, and a class that says nothing ==")
print("value " + repr(ValueError("nope").__doc__))
print("group " + repr(ExceptionGroup("", [ValueError("nope")]).__doc__))


class Quiet(ValueError):
    pass


class Loud(RuntimeError):
    "Loud(a)\n--\n\nA subclass with a table of its own, which is not cut."


class Adopted(TypeError, ValueError):
    pass


print("subclass " + repr(Quiet.__doc__) + " sig=" + repr(Quiet.__text_signature__))
print("subclass dict " + repr(Quiet.__dict__.get("__doc__")))
print("loud " + repr(Loud.__doc__) + " sig=" + repr(Loud.__text_signature__))
print("adopted " + repr(Adopted.__doc__))

print()
print("== five classes that file their own under a class that files its own ==")
for name in NESTED:
    kind = getattr(builtins, name)
    print(reading(kind))
    print("   bases=" + str([b.__name__ for b in kind.__bases__]))

print()
print("== a static type refuses the write that would move the read ==")
for name in ["ValueError", "super", "SystemError"]:
    kind = getattr(builtins, name)
    try:
        kind.__doc__ = "Rewritten"
    except TypeError as exc:
        print(name + " refuses " + repr(str(exc)))
    else:
        print(name + " accepted " + repr(kind.__doc__))
