"""The seventy-one exception names ``builtins`` publishes, and the three of them that are not classes.

CPython's ``_PyExc_InitState`` files a class per name into the module dictionary, and the count is the
first thing to pin: reading ``dir(builtins)`` for everything that is a ``type`` and a ``BaseException``
subclass answers exactly the seventy-one names listed below, with nothing filed beside them and nothing
wanted. ``FloatingPointError``, ``BufferError``, ``EOFError``, ``ReferenceError``,
``StopAsyncIteration``, ``PythonFinalizationError``, the eleven categories under ``Warning``, the three
``SyntaxError`` descendants and the fifteen under ``OSError`` are all part of that number, and a name
missing from it is a name an ``except`` clause cannot even spell.

Three of the seventy-one are then not classes at all. ``IOError`` and ``EnvironmentError`` are
``INIT_ALIAS`` entries: the same ``OSError`` struct filed under a second and third name, so ``IOError is
OSError`` answers ``True`` and reading ``__name__`` off the alias answers ``OSError``, not the name the
look-up used. ``WindowsError`` is the fourth of those declarations and is compiled out on anything that
is not Windows, which is why this platform counts seventy-one and not seventy-two. A name that shares a
struct shares everything below it — the promotion of an ``errno`` to ``FileNotFoundError``, the ``args``
truncation, the ``[Errno …]`` text — because there is only one type there to run.

The rest is the shape each declaration has. ``__module__`` is ``builtins`` for every one of them,
``__qualname__`` equals ``__name__`` because none is nested, and ``__mro__`` ends at ``object``, which is
the half worth leaving out when a family is named. Then the two argument rules a plain class inherits —
``BaseException_str`` reading one argument as the message and the repr naming the class as its plain
``__name__`` — and finally that each new name is raisable, catchable by its own class, and a member of a
group that ``except*`` can split on.

The four classes with a structure of their own are left to their own rows: ``SyntaxError`` and its three
descendants keep ``msg``/``filename``/``lineno``/``offset``/``text``/``print_file_and_line`` and their
own ``tp_str``, and ``UnicodeError`` and its three descendants keep ``encoding``/``object``/``start``/
``end`` and take exactly five arguments. Both families are named here only for shape.
"""

import builtins

NAMES = """ArithmeticError AssertionError AttributeError BaseException BaseExceptionGroup
BlockingIOError BrokenPipeError BufferError BytesWarning ChildProcessError
ConnectionAbortedError ConnectionError ConnectionRefusedError ConnectionResetError
DeprecationWarning EOFError EncodingWarning EnvironmentError Exception ExceptionGroup
FileExistsError FileNotFoundError FloatingPointError FutureWarning GeneratorExit IOError
ImportError ImportWarning IndentationError IndexError InterruptedError IsADirectoryError
KeyError KeyboardInterrupt LookupError MemoryError ModuleNotFoundError NameError
NotADirectoryError NotImplementedError OSError OverflowError PendingDeprecationWarning
PermissionError ProcessLookupError PythonFinalizationError RecursionError ReferenceError
ResourceWarning RuntimeError RuntimeWarning StopAsyncIteration StopIteration SyntaxError
SyntaxWarning SystemError SystemExit TabError TimeoutError TypeError UnboundLocalError
UnicodeDecodeError UnicodeEncodeError UnicodeError UnicodeTranslateError UnicodeWarning
UserWarning ValueError Warning ZeroDivisionError _IncompleteInputError""".split()

STRUCTURED = ("SyntaxError", "IndentationError", "TabError", "_IncompleteInputError",
              "UnicodeError", "UnicodeDecodeError", "UnicodeEncodeError", "UnicodeTranslateError",
              "BaseExceptionGroup", "ExceptionGroup")
PLAIN = [n for n in NAMES if n not in STRUCTURED]


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


def published():
    found = []
    for name in dir(builtins):
        try:
            value = getattr(builtins, name)
        except AttributeError:
            continue
        if isinstance(value, type) and issubclass(value, BaseException):
            found.append(name)
    return found


print("== the count, and what the two directions of the difference say ==")
print(len(NAMES), len(published()))
print("wanted:", describe("f", lambda: sorted(set(NAMES) - set(published()))))
print("extra:", describe("f", lambda: sorted(set(published()) - set(NAMES))))

print("== the shape of each declaration ==")
for name in NAMES:
    kind = getattr(builtins, name)
    print("%-24s %-9s %-22s %-22s %s"
          % (name, kind.__module__, kind.__name__, kind.__qualname__,
             [base.__name__ for base in kind.__bases__]))

print("== the same walk one level deeper ==")
for name in NAMES:
    print("%-24s %s" % (name, [m.__name__ for m in getattr(builtins, name).__mro__[:-1]]))

print("== the two aliases, and the name they answer ==")
print(describe("alias", lambda: IOError is OSError),
      describe("alias", lambda: EnvironmentError is OSError),
      describe("name", lambda: IOError.__name__),
      describe("name", lambda: EnvironmentError.__name__))
print(describe("missing", lambda: builtins.WindowsError),
      describe("hasattr", lambda: hasattr(builtins, "WindowsError")))

print("== the families, read by issubclass over the whole published set ==")
for outer in (BaseException, Exception, OSError, ConnectionError, Warning, ArithmeticError,
              LookupError, SyntaxError, UnicodeError, ValueError, NameError, ImportError,
              BaseExceptionGroup, RuntimeError, OverflowError):
    members = sorted(n for n in NAMES if issubclass(getattr(builtins, n), outer))
    print("%-20s %2d %s" % (outer.__name__, len(members), members))

print("== what is *not* in a family ==")
for name, outer in [("ExceptionGroup", BaseExceptionGroup), ("GeneratorExit", Exception),
                    ("KeyboardInterrupt", Exception), ("SystemExit", Exception),
                    ("StopIteration", StopAsyncIteration), ("ReferenceError", RuntimeError),
                    ("BufferError", MemoryError), ("Warning", RuntimeError),
                    ("ImportError", OSError), ("TimeoutError", ConnectionError),
                    ("FileNotFoundError", PermissionError), ("TabError", Exception)]:
    print("%-20s under %-20s -> %s"
          % (name, outer.__name__,
             describe("f", lambda name=name, outer=outer:
                      issubclass(getattr(builtins, name), outer))))

print("== the two argument rules a plain class inherits ==")
for name in PLAIN:
    kind = getattr(builtins, name)
    print("%-24s %s" % (name, describe("repr", lambda: repr(kind()))))
    print("%-24s %s" % ("", describe("args", lambda: kind().args)))
    print("%-24s %s" % ("", describe("str", lambda: str(kind()))))
    print("%-24s %s" % ("", describe("two", lambda: (kind("a", "b").args, str(kind("a", "b")),
                                                     repr(kind("a", "b"))))))

print("== raisable, and catchable by its own name ==")
for name in PLAIN:
    kind = getattr(builtins, name)
    try:
        try:
            raise kind("m")
        except kind as error:
            print("%-24s %-24s %-18s %s"
                  % (name, type(error).__name__, repr(error.args), repr(str(error))))
    except BaseException as error:
        print("%-24s %s" % (name, describe("raised", lambda: (type(error).__name__, str(error)))))

print("== except-star splits on the new names ==")
group = BaseExceptionGroup("mixed", [Warning("w"), OSError(2, "m"), KeyError("k"), GeneratorExit()])
print(describe("str", lambda: str(group)), describe("len", lambda: len(group.exceptions)))
try:
    raise group
except* Warning as caught:
    print("warning:", describe("t", lambda: [type(e).__name__ for e in caught.exceptions]))
except* OSError as caught:
    print("oserror:", describe("t", lambda: [type(e).__name__ for e in caught.exceptions]),
          describe("errno", lambda: caught.exceptions[0].errno),
          describe("str", lambda: str(caught.exceptions[0])))
except* (KeyError, LookupError) as caught:
    print("lookup:", describe("t", lambda: [type(e).__name__ for e in caught.exceptions]))
except* BaseException as caught:
    print("rest:", describe("t", lambda: [type(e).__name__ for e in caught.exceptions]))

print("== subgroup and split, over the same group ==")
print(describe("oserror", lambda: [e.__class__.__name__ for e in group.subgroup(
    lambda e: isinstance(e, OSError)).exceptions]))
print(describe("warning", lambda: [e.__class__.__name__ for e in group.subgroup(
    lambda e: isinstance(e, Warning)).exceptions]))
print(describe("nothing", lambda: group.subgroup(lambda e: False)))
print(describe("split", lambda: [(half is None and "None" or [e.__class__.__name__ for e in half.exceptions])
                                 for half in group.split(lambda e: isinstance(e, Warning))]))
