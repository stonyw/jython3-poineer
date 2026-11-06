"""`code.__new__`: the thirty-seventh row of `code`'s book, and the eighteen fields it takes.

CPython files this row itself. A type's `tp_new` is a creator, and `update_one_slot` writes a wrapper over it
into the dictionary beside the slots it came from, which is why `__new__` is the first name of thirty-seven
rather than one of the eight wrappers `add_operators` copies out of slots; why the row is a
`builtin_function_or_method` bound to the class instead of a descriptor bound to a value; why its own sentence is
the generic one about creating an object while the eighteen fields are the class's `__text_signature__`; and why
`dir(code)` counted fifty names before it arrived — `object` brought that name in already, so the book gains a
row where the name list gains nothing.

What the row takes is eighteen positional-only values in the order clinic converts them, and that order is not
the order of `replace()`'s table: `co_nlocals` is fourth here and sixth there, `co_firstlineno` fourteenth here
and seventh there, and the refusal of a wrong type names a position (`code() argument 7 must be bytes, not str`)
where `replace` names a parameter. Seven of the eighteen are read into a C `int`, so a float is refused as an
integer rather than as a count, a `__index__` object is accepted, `2 ** 70` overflows, and a negative count is a
value that travels rather than a refusal met here. Two refusals come before any of that — the keyword guard, then
the count of arguments, which wants sixteen and takes no more than eighteen — and all three name `code`, not
`code.__new__`, because the wrapper hands the tuple down before it says whose it was.

Four negatives are the constructor's own: `argcount`, `posonlyargcount`, `kwonlyargcount` and `nlocals` each
answer `code: X must not be negative`, in that order, and only once every converter has run. The two counts left
over are not refused here but by the builder, which answers the `SystemError` that names the C file and line it
was written at — the same sentence a position-only count wider than the argument count reaches, and the one this
runtime used to leave half of. What the builder makes of the numbers that do arrive is the rest of the section
below: nought stack comes back as one, a `co_nlocals` that disagrees with the tuple it was handed does not build,
and a first line of `-1` is accepted, since nothing in the arithmetic asks it to be a line anyone wrote.

There is no receiver here to take a code format from, and this runtime keeps a format in the front-end that read
one (decision D2), so the constructor asks the frame that made the call for its own code object and builds
through that. The two routes to a builder therefore differ in who answers the question and in nothing that reads
back: what comes out of `code(*fields)` and out of `code.replace()` compare equal to each other, to the body the
compiler wrote, and answer the same three cursors from the same two tables — which is what the last four sections
ask of nine bodies, one of them a nested scope whose consts hold another code object.

What is left to the unit case is what the two runtimes answer differently and no script can put politely. The
first is the identity of a copy: the reference hashes a code object by its content and keeps the tuples and
octets it was handed, so there `copy.co_names is names` is false while `hash(copy) == hash(body)` is true, and
this runtime hashes by object and keeps what it is given, so both answers come out the other way. The second is
the audit event the reference opens its building with, `sys.addaudithook` being a face of `sys` this runtime has
not landed. A repr's `at 0x…` is masked wherever a repr is printed, `class Sub(code)` is not asked at all, since
the two word that refusal differently and neither wording is what this row is about.
"""


def plain(a, b):
    if a:
        b = 1
    return a + b


def looped(n):
    total = 0
    for i in range(n):
        if i:
            total += 1
    return total


def starred(a, *rest, b=2, **kw):
    return a, rest, b, kw


def shared(a):
    b = a + 1

    def inner():
        return b + a

    return inner


def tried(a):
    try:
        return a + 1
    except TypeError:
        return 0


def gen(n):
    for i in range(n):
        yield i


def counted(n):
    return [i for i in range(n) if i]


def matched(command):
    match command:
        case ["go", n]:
            return n
        case other:
            return other


def documented(a):
    """A sentence the compiler files as the first const."""
    return a


FIELDS = (
    "co_argcount",
    "co_posonlyargcount",
    "co_kwonlyargcount",
    "co_nlocals",
    "co_stacksize",
    "co_flags",
    "co_code",
    "co_consts",
    "co_names",
    "co_varnames",
    "co_filename",
    "co_name",
    "co_qualname",
    "co_firstlineno",
    "co_linetable",
    "co_exceptiontable",
    "co_freevars",
    "co_cellvars",
)

PLAIN = plain.__code__
KIND = type(PLAIN)
OUTER = shared.__code__
BOOK = list(vars(KIND))

BODIES = [
    ("plain", PLAIN),
    ("looped", looped.__code__),
    ("starred", starred.__code__),
    ("closure", shared(1).__code__),
    ("outer", OUTER),
    ("tried", tried.__code__),
    ("generator", gen.__code__),
    ("matched", matched.__code__),
    ("comprehension", counted.__code__),
]


def fields_of(code):
    return [getattr(code, name) for name in FIELDS]


def swap(code, pairs):
    values = fields_of(code)
    for index, value in pairs:
        values[index] = value
    return values


def told(action):
    try:
        return "ok " + repr(action())
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def refusal(action):
    try:
        action()
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)
    return "no error"


def shown(code):
    head, _, rest = repr(code).partition(" at 0x")
    return head + " at 0x…" + rest[rest.index(","):]


def read_back(code):
    return (
        code.co_argcount,
        code.co_posonlyargcount,
        code.co_kwonlyargcount,
        code.co_nlocals,
        code.co_stacksize,
        code.co_flags,
        code.co_name,
        code.co_qualname,
        code.co_firstlineno,
        len(code.co_consts),
        code.co_names,
        code.co_varnames,
        code.co_freevars,
        code.co_cellvars,
    )


print("== the row at the head of the book")
row = vars(KIND)["__new__"]
print("  index in the book              %d" % BOOK.index("__new__"))
print("  rows and names                 %d / %d" % (len(BOOK), len(dir(KIND))))
print("  first three of the book        %s" % BOOK[:3])
print("  kind of the row                %s" % type(row).__name__)
print("  name and qualname              %s / %s" % (row.__name__, row.__qualname__))
print("  module                         %r" % (row.__module__,))
print("  self is the class              %s" % (row.__self__ is KIND,))
print("  its own sentence               %r" % (row.__doc__,))
print("  signature                      %r" % (row.__text_signature__,))
print("  is object's row                %s" % (row is object.__new__,))
print("  asks for a class of itself     %s" % refusal(lambda: row.__objclass__))
print("  in a value's dir               %s" % ("__new__" in dir(PLAIN),))
print("  reached through a value        %s" % type(PLAIN.__new__).__name__)
print("  bound to the value             %s" % (PLAIN.__new__.__self__ is PLAIN,))
print("  the row twice                  %s" % (KIND.__new__ is KIND.__new__,))
print("  the name once                  %s" % ([n for n in dir(KIND) if n == "__new__"],))

print()
print("== the sentence above the table, and the fields below it")
print("  the class sentence             %r" % (KIND.__doc__,))
print("  its lines                      %d" % len(KIND.__doc__.splitlines()))
print("  the eighteen, as filed         %r" % (KIND.__text_signature__,))
print("  their lines                    %d" % len(KIND.__text_signature__.splitlines()))
print("  two of them default            %d" % KIND.__text_signature__.count("=()"))
print("  closed by the marker           %s" % (KIND.__text_signature__.rstrip().endswith("/)"),))
print("  a value's doc is its class's   %s" % (PLAIN.__doc__ == KIND.__doc__,))
print("  init is object's               %s" % (KIND.__init__ is object.__init__,))

print()
print("== the two ways to reach the constructor")
made = KIND(*fields_of(PLAIN))
print("  from the type                  %s" % shown(made))
print("  from the row                   %s" % shown(KIND.__new__(KIND, *fields_of(PLAIN))))
print("  type of what comes back        %s" % (type(made) is KIND,))
print("  equal to the compiler's body   %s" % (made == PLAIN,))
print("  not the body itself            %s" % (made is not PLAIN,))
print("  read back alike                %s" % (read_back(made) == read_back(PLAIN),))
print("  the three runs                 %s" % (read_back(made)[10:],))
print("  from replace                   %s" % (made == PLAIN.replace(),))
print("  seven reads of the tables      %s" % ([
    made.co_code == PLAIN.co_code,
    made.co_linetable == PLAIN.co_linetable,
    made.co_exceptiontable == PLAIN.co_exceptiontable,
    list(made.co_lines()) == list(PLAIN.co_lines()),
    list(made.co_positions()) == list(PLAIN.co_positions()),
    list(made.co_branches()) == list(PLAIN.co_branches()),
    made.__sizeof__() == PLAIN.__sizeof__(),
],))
print("  called through type(code)      %s" % (type(PLAIN)(*fields_of(PLAIN)) == PLAIN,))
print("  the type of the type           %s" % (type(KIND).__name__,))

print()
print("== writes to the row")
print("  written on the type            %s" % refusal(lambda: setattr(KIND, "__new__", 1)))
print("  deleted on the type            %s" % refusal(lambda: delattr(KIND, "__new__")))
print("  written on a value             %s" % refusal(lambda: setattr(PLAIN, "__new__", 1)))
print("  deleted on a value             %s" % refusal(lambda: delattr(PLAIN, "__new__")))
print("  still a row after all          %s" % ("__new__" in vars(KIND)))
print("  the book is still              %d" % (len(vars(KIND)),))

print()
print("== sixteen, seventeen, eighteen")
print("  fields of a body               %d" % len(fields_of(PLAIN)))
for count in (16, 17, 18):
    made = KIND(*fields_of(OUTER)[:count])
    print("  %d of an outer   free %-8s cell %s" % (count, made.co_freevars, made.co_cellvars))
for count in (16, 17, 18):
    made = KIND(*fields_of(shared(1).__code__)[:count])
    print("  %d of a closure   free %-8s cell %s" % (count, made.co_freevars, made.co_cellvars))
print("  a seventeenth, given a name    %s" % told(lambda: KIND(*fields_of(OUTER)[:16], ("a",)).co_freevars))
print("  an eighteenth with a number    %s" % refusal(lambda: KIND(*swap(OUTER, [(17, (1,))]))))

print()
print("== arity, before anything is looked at")
print("  none                           %s" % refusal(lambda: KIND()))
print("  fifteen                        %s" % refusal(lambda: KIND(*fields_of(PLAIN)[:15])))
print("  nineteen                       %s" % refusal(lambda: KIND(*(fields_of(PLAIN) + [1]))))
print("  twenty                         %s" % refusal(lambda: KIND(*(fields_of(PLAIN) + [1, 2]))))
print("  a keyword with the eighteen    %s" % refusal(lambda: KIND(*fields_of(PLAIN), co_argcount=1)))
print("  a keyword alone                %s" % refusal(lambda: KIND(co_argcount=1)))
print("  a keyword with no name for it  %s" % refusal(lambda: KIND(*fields_of(PLAIN), x=1)))
print("  a keyword to the row           %s" % refusal(lambda: KIND.__new__(KIND, *fields_of(PLAIN), co_argcount=1)))

print()
print("== the row's own four gates")
print("  the row on another class       %s" % refusal(lambda: KIND.__new__(str)))
print("  the row on a class, with fields %s" % refusal(lambda: KIND.__new__(str, *fields_of(PLAIN))))
print("  the row on its class, with nothing %s" % refusal(lambda: KIND.__new__(KIND)))
print("  the row on a value             %s" % refusal(lambda: KIND.__new__(PLAIN)))
print("  the row on nothing             %s" % refusal(lambda: KIND.__new__(None)))
print("  object's own row of the class  %s" % refusal(lambda: object.__new__(KIND)))
print("  object's row with an argument  %s" % refusal(lambda: object.__new__(KIND, 1)))
print("  the row still builds           %s" % (type(KIND.__new__(KIND, *fields_of(PLAIN))) is KIND,))

print()
print("== the twelve converters, in the order the table sets them")
for index in range(18):
    print("  %-2d %-20s %s" % (index, FIELDS[index], refusal(lambda: KIND(*swap(PLAIN, [(index, "x")])))))
print("  an int in the same places")
for index in (0, 6, 7, 10, 14, 16):
    print("  %-2d %-20s %s" % (index, FIELDS[index], refusal(lambda: KIND(*swap(PLAIN, [(index, 1)])))))
print("  two wrong, the later written first %s" % refusal(lambda: KIND(*swap(PLAIN, [(16, 1), (10, 1)]))))
print("  two wrong, the earlier written first %s" % refusal(lambda: KIND(*swap(PLAIN, [(10, 1), (16, 1)]))))
print("  a list where a tuple is wanted   %s" % refusal(lambda: KIND(*swap(PLAIN, [(7, [1])]))))
print("  bytes where a str is wanted      %s" % refusal(lambda: KIND(*swap(PLAIN, [(11, b"x")]))))
print("  a str where bytes are wanted     %s" % refusal(lambda: KIND(*swap(PLAIN, [(6, "x")]))))

print()
print("== the seven counts, read as C integers")
print("  a float                        %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, 1.0)]))))
print("  text that reads as a number    %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, "2")]))))
print("  nothing                        %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, None)]))))
print("  a bool                         %s" % told(lambda: KIND(*swap(PLAIN, [(0, False)])).co_argcount))
print("  too wide for a C int           %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, 2 ** 70)]))))
print("  the widest negative            %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, -(2 ** 31))]))))
print("  the width beyond it            %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, -(2 ** 31) - 1)]))))


class Indexed:
    def __index__(self):
        return 2


class NotAnInteger:
    def __index__(self):
        return "two"


class NoIndex:
    pass


print("  an __index__ object            %s" % told(
    lambda: KIND(*swap(PLAIN, [(0, Indexed()), (3, 2)])).co_argcount))
print("  an index that answers text     %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, NotAnInteger())]))))
print("  a firstlineno as an index      %s" % told(lambda: KIND(*swap(PLAIN, [(13, Indexed())])).co_firstlineno))
print("  an object with neither         %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, NoIndex())]))))
print("  a float at fourteenth          %s" % refusal(lambda: KIND(*swap(PLAIN, [(13, 1.0)]))))

print()
print("== the four counts the constructor refuses, and the two the builder does")
COUNTS = ("argcount", "posonlyargcount", "kwonlyargcount", "nlocals", "stacksize", "flags")
for index, label in enumerate(COUNTS):
    print("  -1 as %-16s %s" % (label, refusal(lambda: KIND(*swap(PLAIN, [(index, -1)])))))
print("  -1 with bytes missing          %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, -1), (6, 1)]))))
print("  -1 with a tuple missing        %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, -1), (7, 1)]))))
print("  -1 with a name run missing     %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, -1), (8, (1,))]))))
print("  -1 nlocals and -1 stacksize    %s" % refusal(lambda: KIND(*swap(PLAIN, [(3, -1), (4, -1)]))))
print("  -1 flags and -1 argcount       %s" % refusal(lambda: KIND(*swap(PLAIN, [(5, -1), (0, -1)]))))
print("  firstlineno -1                 %s" % told(lambda: KIND(*swap(PLAIN, [(13, -1)])).co_firstlineno))
print("  posonly wider than args        %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, 1), (1, 3)]))))
print("  posonly as wide as the arguments %s" % refusal(
    lambda: KIND(*swap(PLAIN, [(0, 3), (1, 3), (3, 3), (9, ("a", "b", "c"))]))))
print("  posonly of the two arguments   %s" % told(lambda: KIND(*swap(PLAIN, [(0, 2), (1, 2)])).co_posonlyargcount))

print()
print("== the four name runs")
for index, label in ((8, "co_names"), (9, "co_varnames"), (16, "co_freevars"), (17, "co_cellvars")):
    print("  a number in %-14s %s" % (label, refusal(lambda: KIND(*swap(OUTER, [(index, (1,))])))))
print("  two of a kind and one not      %s" % refusal(lambda: KIND(*swap(OUTER, [(8, ("a", "b", 3))]))))
print("  a negative count and a bad run %s" % refusal(lambda: KIND(*swap(OUTER, [(3, -1), (17, (2,))]))))
print("  a bad run and a negative count %s" % refusal(lambda: KIND(*swap(OUTER, [(17, (2,)), (3, -1)]))))
print("  an empty run                   %s" % told(lambda: KIND(*swap(OUTER, [(16, ())])).co_freevars))
print("  a name with a space in it      %s" % told(lambda: KIND(*swap(OUTER, [(16, ("a b",))])).co_freevars))
print("  text where a tuple is wanted   %s" % refusal(lambda: KIND(*swap(OUTER, [(17, "ab")]))))

print()
print("== what the builder makes of the numbers")
print("  stacksize nought               %s" % told(lambda: KIND(*swap(PLAIN, [(4, 0)])).co_stacksize))
print("  nought on the other route      %s" % (KIND(*swap(PLAIN, [(4, 0)])) == PLAIN.replace(co_stacksize=0)))
print("  nlocals short of the run       %s" % refusal(lambda: KIND(*swap(PLAIN, [(3, 1)]))))
print("  the run short of nlocals       %s" % refusal(lambda: KIND(*swap(PLAIN, [(3, 3)]))))
print("  names wider than the body      %s" % told(lambda: KIND(*swap(PLAIN, [(8, ("z", "y", "x"))])).co_names))
print("  code of no instructions        %s" % told(lambda: len(KIND(*swap(PLAIN, [(6, b"")])).co_code)))
print("  code of one odd octet          %s" % refusal(lambda: KIND(*swap(PLAIN, [(6, b"\x00")]))))
print("  consts emptied                 %s" % told(lambda: KIND(*swap(PLAIN, [(7, ())])).co_consts))
print("  flags zeroed                   %s" % told(lambda: KIND(*swap(PLAIN, [(5, 0)])).co_flags))
print("  a docstring dropped with them  %s" % told(lambda: KIND(*swap(documented.__code__, [(7, ())])).co_consts))
print("  its first const as compiled    %r" % (documented.__code__.co_consts[0],))
print("  rebuilt with its consts        %s" % (KIND(*fields_of(documented.__code__)) == documented.__code__,))

print()
print("== body by body, through the constructor and back")
for label, code in BODIES:
    print("  %-14s %s %s" % (label, fields_of(code)[:6], fields_of(code)[16:]))
print("  every field read back alike")
for label, code in BODIES:
    print("  %-14s %s" % (label, fields_of(KIND(*fields_of(code))) == fields_of(code)))
print("  equal to what was compiled")
for label, code in BODIES:
    made = KIND(*fields_of(code))
    print("  %-14s %-5s %-5s %s" % (
        label, made == code, read_back(made) == read_back(code), shown(made) == shown(code)))

print()
print("== the two routes to one builder, on one change")
for label, code in BODIES:
    made = KIND(*swap(code, [(11, "other")]))
    print("  %-14s %-5s %-8s %s" % (
        label, made == code.replace(co_name="other"), made.co_name, made.co_qualname))
print("  two changes at once            %s" % (KIND(*swap(PLAIN, [(11, "x"), (12, "y")])) ==
                                               PLAIN.replace(co_name="x", co_qualname="y")))
print("  a field named without its prefix %s" % refusal(lambda: PLAIN.replace(argcount="x")))
print("  the same field named there       %s" % refusal(lambda: PLAIN.replace(co_argcount="x")))
print("  the same field named here        %s" % refusal(lambda: KIND(*swap(PLAIN, [(0, "x")]))))

print()
print("== the cursors of a rebuilt body")
for label, code in BODIES:
    made = KIND(*fields_of(code))
    print("  %-14s %s" % (label, [
        list(made.co_lines()) == list(code.co_lines()),
        list(made.co_positions()) == list(code.co_positions()),
        list(made.co_branches()) == list(code.co_branches()),
    ]))
print("  the exception table it was handed %r" % (KIND(*fields_of(tried.__code__)).co_exceptiontable,))
print("  as compiled                       %r" % (tried.__code__.co_exceptiontable,))
print("  a line table of octets nothing wrote %s" % refusal(lambda: KIND(*swap(PLAIN, [(14, b"\xff\xff")]))))

print()
print("== consts that are themselves bodies")
for label, code in BODIES:
    nested = [c for c in code.co_consts if type(c) is KIND]
    if nested:
        made = KIND(*fields_of(code))
        print("  %-12s %-9s consts alike %-5s rebuilt %s" % (
            label, [n.co_name for n in nested], made.co_consts == code.co_consts,
            [KIND(*fields_of(n)) == n for n in nested]))
        print("      their own runs %s / %s" % (
            [n.co_varnames for n in nested], [n.co_freevars for n in nested]))
print("  reached through the copy         %s" % told(lambda: KIND(*fields_of(OUTER)).co_consts[1].co_name))
COPIED = KIND(*fields_of(shared(1).__code__))
print("  the copy of a copy               %s" % told(lambda: (KIND(*fields_of(COPIED)).co_freevars,
                                                             KIND(*fields_of(COPIED)).co_cellvars)))
