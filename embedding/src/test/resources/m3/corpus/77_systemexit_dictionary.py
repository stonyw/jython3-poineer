"""The three rows ``SystemExit`` files, and the field that says what the interpreter should end with.

``SystemExit.__dict__`` is ``__init__``, ``code`` and ``__doc__``, and ``dir(SystemExit)`` counts thirty-four:
the same shape as ``StopIteration`` beside it, with a field called ``code`` in place of one called ``value``
and a sentence of its own. It sits under ``BaseException`` rather than under ``Exception``, which is why a
bare ``except Exception`` never catches an exit and why the two classes that end a program — this one and
``KeyboardInterrupt`` — are the two the family keeps out of ``Exception``.

The initialiser is the one of the family that leaves its field standing when a call hands it nothing.
``SystemExit_init`` reads the arguments, keeps them, and fills ``code`` only when there is exactly one to
take, or a tuple of them when there are several: ``SystemExit(1).code`` is the ``1`` itself and
``SystemExit('a', 2).code`` is the pair, while ``SystemExit().__init__()`` on a value that had a code keeps
the code it had — the opposite of what the same call does to ``StopIteration.value``. The creator is still
``BaseException``'s and files no row, so ``SystemExit.__new__(SystemExit)`` has the empty arguments and no
code at all.

Keywords are refused rather than parsed, by the same ``_PyArg_NoKeywords`` that ``StopIteration`` answers
with, and the sentence names the class the value was made as: ``SystemExit(x=1)`` is refused as
``SystemExit()`` and a subclass is refused as itself. The field is a ``_Py_T_OBJECT`` offset, so an unset code
reads ``None``, a written ``None`` reads ``None``, and deleting is not an error but the same empty pointer —
which is what makes ``sys.exit()`` with no argument and ``sys.exit(None)`` the same exit.

What this case does not print is the status the launcher ends with, which this runtime reads off the arguments
rather than off this field and so is a named deviation for a code written by hand after the call, the
``Did you mean`` sentence a name the table does not have is answered with — this initialiser parses no names
at all, so there is nothing to suggest — and the address in the ``repr`` of the row.
"""

import sys


OWNED = SystemExit.__dict__


def show(label, thunk):
    try:
        return print("%s -> %r" % (label, thunk()))
    except BaseException as error:
        return print("%s !! %s: %s" % (label, type(error).__name__, error))


def line(name):
    value = OWNED[name]
    return "%-22s %-24s %-28s set=%-5s get=%-5s doc=%s" % (
        name,
        type(value).__name__,
        getattr(value, "__qualname__", "-"),
        hasattr(value, "__set__"),
        hasattr(value, "__get__"),
        repr(getattr(value, "__doc__", "-")),
    )


print(len(OWNED), len(dir(SystemExit)), list(OWNED))
for row in list(OWNED):
    print(line(row))

show("doc", lambda: SystemExit.__doc__)
show("under base", lambda: SystemExit.__mro__)
show("not an exception", lambda: issubclass(SystemExit, Exception))
show("bare code", lambda: SystemExit().code)
show("one code", lambda: SystemExit(1).code)
show("one code is the object", lambda: (lambda e: e.code is e.args[0])(SystemExit(1)))
show("two codes are a tuple", lambda: SystemExit("a", 2).code)
show("two arguments kept", lambda: SystemExit("a", 2).args)
show("str of one", lambda: str(SystemExit("a")))
show("str of two", lambda: str(SystemExit("a", 2)))
show("repr of one", lambda: repr(SystemExit("a")))
show("repr of nothing", lambda: repr(SystemExit()))
show("new has no code", lambda: SystemExit.__new__(SystemExit).code)
show("new has no arguments", lambda: SystemExit.__new__(SystemExit).args)
show("named refused", lambda: SystemExit(code=1))
show("any name refused", lambda: SystemExit(x=1))
show("name refused after positional", lambda: SystemExit("a", name="n"))
show("reduce is two places", lambda: SystemExit(1).__reduce__())

show("write code", lambda: (lambda e: (setattr(e, "code", 3), e.code))(SystemExit()))
show("write over", lambda: (lambda e: (setattr(e, "code", 3), e.code, e.args))(SystemExit(1)))
show("delete code", lambda: (lambda e: (delattr(e, "code"), e.code))(SystemExit(1)))
show("delete twice", lambda: (lambda e: (delattr(e, "code"), delattr(e, "code"), e.code))(SystemExit(1)))
show("write none", lambda: (lambda e: (setattr(e, "code", None), e.code))(SystemExit(1)))
show("code is not the dict", lambda: (lambda e: (setattr(e, "code", 1), dict(e.__dict__)))(SystemExit()))
show("re-init with none", lambda: (lambda e: (e.__init__(), (e.args, e.code)))(SystemExit("a")))
show("re-init with one", lambda: (lambda e: (e.__init__(2), (e.args, e.code)))(SystemExit("a")))
show("re-init with two", lambda: (lambda e: (e.__init__("b", 3), (e.args, e.code)))(SystemExit("a")))


class Exit(SystemExit):
    pass


show("subclass names itself", lambda: Exit(x=1))
show("subclass keeps the field", lambda: Exit(3).code)
show("subclass doc", lambda: Exit.__doc__)
show("subclass dict", lambda: list(Exit.__dict__))
show("row of another class", lambda: SystemExit.__dict__["__init__"](StopIteration("a")))


def exits():
    try:
        sys.exit(4)
    except SystemExit as leave:
        return (leave.code, leave.args)


show("sys exit hands the code", exits)
