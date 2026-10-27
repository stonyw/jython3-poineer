"""The one sentence ``SyntaxError`` writes, and the four things it reads to write it.

``SyntaxError_str`` is the reason the class needs a ``__str__`` of its own, and it is a smaller function than
it looks: four of the nine fields are asked, two of them are used, and everything else about the value is left
to whoever prints a traceback. ``filename`` counts when it is a ``str`` and is then cut at its last separator
by ``my_basename``, so ``/a/b/f.py`` is printed as ``f.py`` and a name with no separator is printed as it
stands. ``lineno`` counts only when it is *exactly* an ``int``: ``PyLong_CheckExact`` refuses a ``bool``, so
``e.lineno = True`` leaves the line out of the sentence entirely, and refuses an ``int`` subclass written by a
person. It is then read with ``PyLong_AsLongAndOverflow``, whose overflow answer is ``-1``, so
``e.lineno = 2**80`` prints ``line -1`` — the number a person wrote is never what goes on the page when it does
not fit a C long.

The shape of the sentence has three possibilities and one of them is not a sentence at all. With both a name
and a line it is ``msg (f.py, line 3)``, with a name alone ``msg (f.py)``, with a line alone ``msg (line 3)``,
and with neither there is no parentheses at all: the function answers ``PyObject_Str(self->msg)``, which is the
message's own text and *not* ``BaseException_str`` — so ``str(SyntaxError('m', ('/f', 1, 2, 't'), 'x'))`` is
``m``, the three arguments it was made with never shown, while ``str(SyntaxError('a', 'b'))`` still says
``('a', 'b')`` because that value was never given a message to read. The message itself is ``%S``'d, which puts
nothing in quotes and reaches ``__str__`` rather than ``__repr__``: ``None`` prints as ``None``, a list as
``[1, 2]``, and a value whose ``__str__`` raises carries that failure out of ``str(e)``.

Two more of the nine fields are read by nothing in this function: ``offset`` and ``text``, which the traceback
printer uses to underline the offending characters, and the remaining ``end_lineno``, ``end_offset``,
``print_file_and_line`` and ``_metadata`` are read only by whoever put them there. Writing all nine and printing
the value is still ``m (f.py, line 3)``.

What this case does not print is the anchor line the same printer lays under the ``text``, the ``SyntaxError``
a real parse of a broken program leaves behind with its ``offset`` already set, and the ``filename`` of the file
being run.
"""


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def shaped(**fields):
    def build():
        error = SyntaxError("m")
        for name, value in fields.items():
            setattr(error, name, value)
        return str(error)

    return build


show("no fields", shaped())
show("msg only", lambda: str(SyntaxError("m")))
show("nothing at all", lambda: str(SyntaxError()))
show("name and line", shaped(filename="/a/b/f.py", lineno=3))
show("name alone", shaped(filename="/a/b/f.txt"))
show("line alone", shaped(lineno=7))
show("one separator", shaped(filename="f.py", lineno=1))
show("many separators", shaped(filename="/a/b/c/d/e.py", lineno=2))
show("trailing separator", shaped(filename="/a/b/", lineno=2))
show("leading separator only", shaped(filename="/", lineno=2))
show("empty name", shaped(filename="", lineno=2))
show("empty name only", shaped(filename=""))
show("backslashes kept", shaped(filename="C:\\tmp\\f.py", lineno=2))
show("offset ignored", shaped(filename="f.py", lineno=2, offset=9, text="  ???"))
show("all nine written", shaped(filename="f.py", lineno=2, offset=3, text="t", end_lineno=2,
                                end_offset=4, print_file_and_line=None, _metadata=None))

show("line a bool", shaped(filename="f", lineno=True))
show("line a false bool", shaped(filename="f", lineno=False))
show("line a float", shaped(filename="f", lineno=1.5))
show("line a str", shaped(filename="f", lineno="3"))
show("line a negative", shaped(filename="f", lineno=-4))
show("line two to the sixty three", shaped(filename="f", lineno=2**63))
show("line minus two to the sixty three", shaped(filename="f", lineno=-(2**63)))
show("line two to the sixty four", shaped(filename="f", lineno=2**64))
show("line two to the eighty", shaped(filename="f", lineno=2**80))
show("line a huge negative", shaped(filename="f", lineno=-(2**80)))
show("line deleted again", lambda: (lambda e: (setattr(e, "lineno", 3), delattr(e, "lineno"), str(e))[2])(SyntaxError("m")))

show("msg none", shaped(msg=None, filename="f.py", lineno=2))
show("msg a number", shaped(msg=5, filename="f.py", lineno=1))
show("msg a list", shaped(msg=[1, 2], filename="f.py", lineno=1))
show("msg a tuple", shaped(msg=(1, 2), filename="f.py", lineno=1))
show("msg bytes", shaped(msg=b"raw", filename="f.py", lineno=1))
show("msg an empty str", shaped(msg="", filename="f.py", lineno=1))


class Speaks:
    def __str__(self):
        return "spoken"

    def __repr__(self):
        return "not this"


class Silent:
    def __str__(self):
        raise RuntimeError("no text")


show("msg with a str", shaped(msg=Speaks(), filename="f.py", lineno=1))
show("msg whose str fails", shaped(msg=Silent(), filename="f.py", lineno=1))
show("name an int", shaped(filename=99, lineno=3))
show("name a list", shaped(filename=["f.py"], lineno=3))
show("name none with a line", shaped(filename=None, lineno=3))

show("three arguments", lambda: str(SyntaxError("m", ("/f", 1, 2, "t"), "x")))
show("four arguments", lambda: str(SyntaxError("m", ("/f", 1, 2, "t"), "x", "y")))
show("two arguments", lambda: str(SyntaxError("a", "b")))
show("one argument printed", lambda: "%s" % SyntaxError("m", ("/f.py", 1, 2, "t")))
show("str of a pair", lambda: str(SyntaxError("m", ("/f.py", 1, 2, "t"))))
show("str with no name but an offset", lambda: str(SyntaxError("m", (None, None, 4, None))))
show("str of a str info", lambda: str(SyntaxError("m", "abcd")))
show("str of an empty info", lambda: str(SyntaxError("m", ())))
show("row called on its own", lambda: SyntaxError.__dict__["__str__"](SyntaxError("m", ("/f.py", 1, 2, "t"))))
show("row of another class", lambda: SyntaxError.__dict__["__str__"](AttributeError("a")))
show("row of an int", lambda: SyntaxError.__dict__["__str__"](3))
show("row needs no arguments", lambda: SyntaxError("m").__str__(1))

def three(name, kind):
    def build():
        error = kind("m")
        error.lineno = 5
        return str(error)

    return build


for name, kind in (("IndentationError", IndentationError), ("TabError", TabError),
                   ("_IncompleteInputError", _IncompleteInputError)):
    show(name + " pair", (lambda k: lambda: str(k("m", ("/p/q/r.py", 2, 3, "  x"))))(kind))
    show(name + " no fields", (lambda k: lambda: str(k("m")))(kind))
    show(name + " line only", three(name, kind))
    show(name + " nothing", (lambda k: lambda: str(k()))(kind))
    show(name + " str row shared", (lambda k: lambda: k.__str__ is SyntaxError.__dict__["__str__"])(kind))


def caught():
    try:
        raise SyntaxError("m", ("/a/f.py", 4, 1, "text"))
    except SyntaxError as error:
        return (error.filename, error.lineno, str(error), error.args)


show("raised and caught", caught)
show("fields survive a raise", lambda: (lambda e: (e.msg, e.offset, e.text))(SyntaxError("m", ("/f", 1, 2, "t"))))
