"""M5 slice 8: `compile()` refuses bad source by raising a Python `SyntaxError`.

The reference interpreter is the compiler, so the text of the error is already CPython's. What this
script measures is that the refusal crosses the seam as a Python exception rather than a Java failure:
`except SyntaxError` catches it, `IndentationError` is the class for the two bad indents, `eval` and
`ast.parse` refuse the same way, and every field `SyntaxError` carries -- `msg`, `filename`, `lineno`,
`offset`, `text`, `end_lineno`, `end_offset` and `args` -- answers what the reference interpreter
answers.

The display is printed too: it is the one place where the fields are read together, and a runtime that
kept the report but dropped a field would show it here. The source snippets stay small on purpose; what
is compared is the exception, not the parser.
"""

import sys

# The reference interpreter's own library, as the other corpora read it, so both sides parse with the
# same `ast` module.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import ast


def show(source, filename="<fragment>", mode="exec"):
    try:
        compile(source, filename, mode)
    except SyntaxError as error:
        print("-", repr(source))
        print("  class", type(error).__name__)
        print("  msg", repr(error.msg))
        print("  filename", repr(error.filename))
        print("  lineno", repr(error.lineno))
        print("  offset", repr(error.offset))
        print("  text", repr(error.text))
        print("  end", repr(error.end_lineno), repr(error.end_offset))
        print("  args", repr(error.args))
        print("  str", str(error))
    else:
        print("-", repr(source), "accepted")


show("continue\n")
show('f"{6 0}"')
show("x = (\n")
show("if 1:\n  pass\n pass\n", "<indent>")
show("def f(:\n  pass\n", "<def>")

try:
    eval("x = 1")
except SyntaxError as error:
    print("eval", type(error).__name__, repr(error.msg))

try:
    ast.parse("x = (", "<parsed>")
except SyntaxError as error:
    print("parse", type(error).__name__, repr(error.msg))

# The subclass check and the handler above it are the reason this script exists: an indentation
# error caught as `IndentationError` must also be caught as `SyntaxError`, exactly as in CPython.
try:
    compile("if 1:\n  pass\n pass\n", "<s>", "exec")
except IndentationError as error:
    print("indentation", isinstance(error, SyntaxError), repr(error.msg))
