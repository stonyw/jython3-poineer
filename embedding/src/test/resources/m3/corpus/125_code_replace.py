"""The copy `code.replace` makes, and the eighteen names its own defaults are read from.

`code` has no constructor a program can reach, so the only way to a code object that either interpreter
builds for itself is to copy one and change what the copy carries. CPython does that as one C function,
`code_replace`, filed twice in the book: the public `replace` and the `__replace__` that the copy machinery
looks up by name. The two rows differ in nothing but the sentence above the table and in which of them a
program reached, and each takes its receiver as an argument of its own, so the unbound row wants the code
object first where the bound one has it already.

Eighteen keyword-only names, each defaulting to the receiver's own attribute read *as an attribute*, and the
order the clinic table declares them is what a program feels when two values are wrong at once: `co_consts`
is complained about as a tuple before `co_names` is looked at, and the seven counts' `must be a positive
integer` refusals come only after every converter has run. Six of the eighteen are read into a C `int` and a
seventh with it, which is why `2 ** 70` overflows where a slice bound would have clipped; of the eleven left
three want bytes, five want a tuple and three want a str, and the refusal names the parameter itself.

What happens once the eighteen are in hand is `init_code`, and it refuses what the values cannot make: an
odd instruction stream, a `co_nlocals` that disagrees with the tuple it was given, counts the three runs
cannot support, and a `SystemError` for the one case a program can reach but no front-end writes — a
position-only count wider than the argument count. That last sentence carries the C file and line it came
from, which this runtime has nothing to name, so only its type is printed here and its text is pinned in the
unit case. `co_stacksize` is the one field whose answer is not what was given: a frame that cannot hold a
value is not built at all, so nought comes back as one.

The three runs a nested scope shares are derived rather than stored, and that is where a copy can disagree
with what was compiled: a cell whose name is already a plain local joins that local's slot instead of
growing the array, so `co_cellvars` can name a parameter `co_varnames` already holds while `co_nlocals` does
not move, and the same name in the free run takes its own slot beside it anyway. The array both feed is what
`_varname_from_oparg` indexes, one slot wider than `co_varnames` wherever a cell or a capture exists.

Nothing here prints a code object, whose repr carries an address, nor the bytes either interpreter compiled
its own script from; `__sizeof__` is asked only as the difference between two copies of one body. The last
section asks an equality about a copy whose two tables came from somewhere else, which is the one thing a
`replace()` call can reach that no compiled body ever holds: the reference weighs the packed octets, this
runtime weighs the packed exception octets and the decoded line runs, and every answer printed below is what
the two routes agree on. A line table no compiler wrote is the case they do not agree on — the reference
keeps whatever octets it is handed and answers a run from them, while this reader decodes eagerly and says
`ValueError` — so it is left to the unit case that names the difference rather than shown here.
"""


def fn(a, b=1, *args, c, **kw):
    return a


def handled(a):
    try:
        return a + 1
    except ValueError:
        return 0


def merged(a):
    def get():
        return a

    return get


def local_cell(a):
    b = a

    def get():
        return b

    return get


def told(action):
    try:
        return "ok " + repr(action())
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def kind_of(action):
    """The exception's type alone, for the refusal whose sentence names a C file."""
    try:
        action()
    except BaseException as error:
        return type(error).__name__
    return "no error"


def row_of(kind, name):
    return vars(kind)[name]


def shape(code):
    return (code.co_varnames, code.co_cellvars, code.co_freevars, code.co_nlocals)


class Indexes:
    def __index__(self):
        return 1


PLAIN = (lambda a, b: a + b).__code__
SHORT = (lambda q: q).__code__
TWINS = ((lambda a, b: a + b).__code__, (lambda a, b: a + b).__code__)
KIND = type(PLAIN)
PRIVATE = row_of(KIND, "__replace__")
ARGS = fn.__code__
MERGED = merged.__code__
CLOSURE = merged(1).__code__
LOCALCELL = local_cell.__code__

FIELDS = [
    "co_argcount", "co_posonlyargcount", "co_kwonlyargcount", "co_nlocals", "co_stacksize",
    "co_flags", "co_firstlineno", "co_code", "co_consts", "co_names", "co_varnames",
    "co_freevars", "co_cellvars", "co_filename", "co_name", "co_qualname", "co_linetable",
    "co_exceptiontable",
]
COUNTS = FIELDS[:7]
TABLES = [
    ("co_code", "ab"), ("co_consts", ["a"]), ("co_names", 1), ("co_varnames", {}),
    ("co_freevars", "ab"), ("co_cellvars", 3), ("co_filename", 1), ("co_name", b"x"),
    ("co_qualname", []), ("co_linetable", "ab"), ("co_exceptiontable", 1),
]
VALUES = [
    ("str", "x"), ("float", 1.0), ("negative", -1), ("wide", 2 ** 70), ("bool", True),
    ("index", Indexes()),
]

print("== the two rows, and the one body behind them")
for name in ("replace", "__replace__"):
    row = row_of(KIND, name)
    print("  %-12s %-20s %-17s objclass %s" % (
        name, type(row).__name__, row.__qualname__, row.__objclass__ is KIND))
    print("  %-12s doc %r" % ("", row.__doc__))
    print("  %-12s sig %r" % ("", row.__text_signature__))
print("  bound %s / %s" % (type(PLAIN.replace).__name__, type(PLAIN.__replace__).__name__))
print("  self %s / %s" % (PLAIN.replace.__self__ is PLAIN, PLAIN.__replace__.__self__ is PLAIN))
print("  unbound named %s" % told(lambda: KIND.replace(PLAIN, co_name="z").co_name))
print("  unbound private %s" % told(lambda: PRIVATE(PLAIN, co_name="z").co_name))
print("  names carrying it %s" % sorted(n for n in dir(KIND) if "replace" in n))

print()
print("== a copy that changes nothing")
COPY = PLAIN.replace()
print("  is the source %s | equals the source %s" % (COPY is PLAIN, COPY == PLAIN))
print("  eighteen fields agree %s" % [getattr(COPY, n) == getattr(PLAIN, n) for n in FIELDS])
print("  private copy equals %s" % (PLAIN.__replace__() == PLAIN))
print("  all eighteen named equals %s" % (
    PLAIN.replace(**{n: getattr(PLAIN, n) for n in FIELDS}) == PLAIN))
print("  consts kept by identity %s" % (PLAIN.replace(co_consts=PLAIN.co_consts).co_consts
                                        is PLAIN.co_consts))
print("  one name changed, still equal %s" % (PLAIN.replace(co_name="z") == PLAIN))
print("  private named %s" % PLAIN.__replace__(co_name="z").co_name)
print("  a copy of a copy %s" % PLAIN.replace().replace().replace(co_argcount=1).co_argcount)

print()
print("== the seven counts, converted in the order the table declares them")
for name in COUNTS:
    for label, value in VALUES:
        print("  %-21s %-9s %s" % (
            name, label, told(lambda n=name, v=value: getattr(PLAIN.replace(**{n: v}), n))))

print()
print("== the eleven tables, and which refusal a pair reaches first")
for name, value in TABLES:
    print("  %-21s %-9s %s" % (
        name, type(value).__name__, told(lambda n=name, v=value: PLAIN.replace(**{n: v}))))
print("  %-21s %-9s %s" % ("consts before names", "both bad", told(
    lambda: PLAIN.replace(co_names=1, co_consts=1))))
print("  %-21s %-9s %s" % ("converter then check", "str + bytes", told(
    lambda: PLAIN.replace(co_argcount=-1, co_name=b"x"))))
print("  %-21s %-9s %s" % ("check after converter", "negative + bad", told(
    lambda: PLAIN.replace(co_argcount=-1, co_consts=1))))

print()
print("== arity, before any converter has looked")
print("  positional %s" % told(lambda: PLAIN.replace(1)))
print("  positional and named %s" % told(lambda: PLAIN.replace(1, co_name="x")))
print("  unknown name %s" % told(lambda: PLAIN.replace(co_nonexistent=1)))
print("  valid and unknown %s" % told(lambda: PLAIN.replace(co_argcount=1, co_nonexistent=1)))
print("  nineteen given %s" % told(lambda: PLAIN.replace(**{"c%d" % i: 1 for i in range(19)})))
print("  row unbound %s" % told(lambda: KIND.replace(co_name="x")))
print("  row on an int %s" % told(lambda: KIND.replace(3, co_name="x")))
print("  private on an int %s" % told(lambda: PRIVATE(3, co_name="x")))
print("  row __get__ %s" % told(lambda: KIND.replace.__get__(7)))
print("  private __get__ %s" % told(lambda: PRIVATE.__get__(7)))

print()
print("== what the builder refuses once the eighteen are in hand")
print("  odd instruction stream %s" % told(lambda: PLAIN.replace(co_code=b"\x01\x02\x03")))
print("  empty instruction stream %s" % told(lambda: PLAIN.replace(co_code=b"").co_stacksize))
print("  nlocals against the tuple %s" % told(lambda: PLAIN.replace(co_nlocals=1)))
print("  varnames too small %s" % told(lambda: PLAIN.replace(co_varnames=("a",))))
print("  varnames and nlocals both %s" % told(
    lambda: PLAIN.replace(co_varnames=("a",), co_nlocals=1).co_varnames))
print("  argcount past the tuple %s" % told(lambda: PLAIN.replace(co_argcount=3)))
print("  kwonly over the locals %s" % told(lambda: PLAIN.replace(co_kwonlyargcount=1)))
print("  posonly wider than args %s" % kind_of(lambda: PLAIN.replace(co_posonlyargcount=3)))
print("  names with an int %s" % told(lambda: PLAIN.replace(co_names=(1,))))
print("  varnames with an int %s" % told(
    lambda: PLAIN.replace(co_varnames=("a", 2), co_nlocals=2)))
print("  freevars with an int %s" % told(lambda: PLAIN.replace(co_freevars=("q", 3))))
print("  firstlineno nought %s" % PLAIN.replace(co_firstlineno=0).co_firstlineno)
print("  firstlineno wide %s" % told(lambda: PLAIN.replace(co_firstlineno=2 ** 31).co_firstlineno))
print("  stacksize nought reads back %s" % PLAIN.replace(co_stacksize=0).co_stacksize)

print()
print("== the localsplus array the three runs make")
print("  as compiled, an arg in a cell %s" % (shape(MERGED),))
print("  as compiled, a local in a cell %s" % (shape(LOCALCELL),))
print("  as compiled, a nested body %s" % (shape(CLOSURE),))
print("  a cell that is already an arg %s" % told(lambda: shape(PLAIN.replace(co_cellvars=("a",)))))
print("  a cell that is not %s" % told(lambda: shape(PLAIN.replace(co_cellvars=("q",)))))
print("  a free variable %s" % told(lambda: shape(PLAIN.replace(co_freevars=("q",)))))
print("  one name in both runs %s" % told(
    lambda: shape(PLAIN.replace(co_cellvars=("q",), co_freevars=("q",)))))
print("  a cell run over an arg run %s" % told(lambda: shape(MERGED.replace(co_freevars=("a",)))))
print("  a cell run over a local run %s" % told(lambda: shape(LOCALCELL.replace(co_freevars=("b",)))))
print("  the same cell named twice %s" % told(lambda: PLAIN.replace(co_cellvars=("a", "a")).co_cellvars))
print("  a duplicated local %s" % told(
    lambda: shape(PLAIN.replace(co_varnames=("a", "a"), co_nlocals=2))))
WIDER = PLAIN.replace(co_varnames=("a", "b", "c"), co_nlocals=3, co_cellvars=("c",))
print("  merged arg still indexed %s" % told(lambda: WIDER._varname_from_oparg(2)))
print("  past the array %s" % told(lambda: WIDER._varname_from_oparg(3)))
print("  the array under a compiled cell %s" % [MERGED._varname_from_oparg(i) for i in range(2)])
print("  a closure grown %s" % told(lambda: (lambda c: (
    c.co_varnames, c.co_cellvars, c.co_freevars,
    c._varname_from_oparg(0), c._varname_from_oparg(1)))(
        CLOSURE.replace(co_varnames=("z",), co_nlocals=1, co_cellvars=("q",)))))
print("  the four runs of a body with stars %s %s %s %s" % (
    shape(ARGS), ARGS.co_argcount, ARGS.co_posonlyargcount, ARGS.co_kwonlyargcount))
print("  stars grown to name themselves %s" % told(lambda: (lambda c: (
    c.co_varnames, c.co_nlocals, c._varname_from_oparg(4)))(
        ARGS.replace(co_varnames=("a", "b", "c", "args", "kw"), co_nlocals=5))))

print()
print("== the tables a rebuild reads back")
LONGER = PLAIN.replace(co_code=PLAIN.co_code + b"\x00" * 18)
print("  sizeof grows by %s" % (LONGER.__sizeof__() - PLAIN.__sizeof__()))
print("  code kept %s" % (LONGER.co_code == PLAIN.co_code + b"\x00" * 18))
RENAMED = PLAIN.replace(co_name="other", co_qualname="other")
print("  renamed equals %s | %s | %s" % (
    RENAMED == PLAIN, RENAMED.co_name, RENAMED.co_qualname))
print("  renamed lines the same %s" % (list(RENAMED.co_lines()) == list(PLAIN.co_lines())))
print("  renamed positions the same %s" % (
    list(RENAMED.co_positions()) == list(PLAIN.co_positions())))
print("  new consts %s" % told(lambda: PLAIN.replace(co_consts=(1, 2, 3)).co_consts))
print("  new names %s | empty names %s" % (
    PLAIN.replace(co_names=("print",)).co_names, PLAIN.replace(co_names=()).co_names))
print("  new filename %s" % PLAIN.replace(co_filename="other.py").co_filename)

print()
print("== an equality asked of a copy whose tables came from another body")
HANDLED = PLAIN.replace(co_exceptiontable=handled.__code__.co_exceptiontable)
print("  another body's handlers, against its source %s" % (HANDLED == PLAIN))
print("  the table it carries reads back %s" % (HANDLED.co_exceptiontable
                                                == handled.__code__.co_exceptiontable))
print("  a handler copy emptied equals its source %s" % (
    HANDLED.replace(co_exceptiontable=b"") == PLAIN))
print("  emptied copy against the body it came from %s" % (
    HANDLED.replace(co_exceptiontable=b"") == handled.__code__))
print("  a copy of the handler copy %s" % (HANDLED.replace() == HANDLED))
print("  two bodies written alike on one line %s" % (TWINS[0] == TWINS[1]))
print("  their line runs alike %s" % (list(TWINS[0].co_lines()) == list(TWINS[1].co_lines())))
print("  their positions differ %s" % (
    list(TWINS[0].co_positions()) != list(TWINS[1].co_positions())))
LINED = PLAIN.replace(co_linetable=SHORT.co_linetable)
print("  another body's line runs, against its source %s" % (LINED == PLAIN))
print("  the line table it carries reads back %s" % (LINED.co_linetable == SHORT.co_linetable))
print("  an empty line table equals its source %s" % (PLAIN.replace(co_linetable=b"") == PLAIN))
print("  an empty line table answers no runs %s" % told(
    lambda: list(PLAIN.replace(co_linetable=b"").co_lines())))
NO_LOCATION = PLAIN.replace(co_linetable=b"\xff\xff")
print("  a table of no locations equals its source %s" % (NO_LOCATION == PLAIN))
print("  a table of no locations names no line %s" % (
    all(row[2] is None for row in NO_LOCATION.co_lines())))
print("  and no column either %s" % (
    all(each == (None, None, None, None) for each in NO_LOCATION.co_positions())))

print()
print("== writes to the two rows")
for name in ("replace", "__replace__"):
    print("  %-13s value write %s" % (name, told(lambda n=name: setattr(PLAIN, n, 1))))
    print("  %-13s value delete %s" % (name, told(lambda n=name: delattr(PLAIN, n))))
    print("  %-13s class write %s" % (name, told(lambda n=name: setattr(KIND, n, 1))))
    print("  %-13s class delete %s" % (name, told(lambda n=name: delattr(KIND, n))))
