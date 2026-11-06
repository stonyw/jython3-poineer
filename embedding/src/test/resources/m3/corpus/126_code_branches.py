"""`code.co_branches()`: the three offsets a jumping instruction has, and the cursor they arrive on.

CPython files this row in `code_methods` beside the two position cursors, registered `METH_NOARGS` with no
`ml_doc`, and what it answers is a walk of the code array rather than a table: six opcodes can go two ways —
`FOR_ITER`, the four `POP_JUMP_IF_*` tests and `END_ASYNC_FOR` — and each names three offsets, the instruction
itself, where falling through begins and where jumping begins. The other jumps are not branches however far
they go, so the `JUMP_BACKWARD` that closes a loop contributes nothing of its own, and the numbers come from
the octets `co_code` holds plus the widths the compiler left behind each opcode. Nothing here asks what the
body did at run time: the offsets are ones `co_lines()` ranges over and a traceback records, so the same three
numbers are printed twice below — once as the row, and once as whether each of them lands inside a line run.

The cursor is the oddity of the family. CPython's branches iterator is a second type object with the first
one's name, so `type(co.co_branches())` prints `line_iterator` and yet is neither `is` nor `isinstance` of
`type(co.co_lines())`; both are one-shot, neither has a `__length_hint__` to ask for a count, and the walk
resumes where it stopped rather than from the top. What the three cursors' own dictionaries hold is not
measured here: this runtime files no rows in a cursor's book yet, which is a face of the cursors rather than
of the row that hands them out, and the unit case names the difference.

The rows are worth printing at their widest, because a jump past 255 code units is written with an
`EXTENDED_ARG` in front of it and the folded distance is what the row answers: the body under the last heading
has a branch whose two ways are more than 510 bytes apart, which no single argument byte could name. The
`END_ASYNC_FOR` row is the one to read closely, since its source is two units before the instruction that
decides to take it — the reference names the `END_SEND` it returns to rather than the opcode it walks past —
and one case is left out on purpose: hand a code object a `co_code` no compiler wrote and this iterator walks
off the end of it, so the only two answers the two interpreters agree on are the ones a real body gives.
"""


def nop():
    pass


def jump(a, b):
    if a:
        b = 1
    return a or b


def loops(n):
    total = 0
    for i in range(n):
        if i:
            total += 1
        else:
            total -= 1
    while total:
        total -= 1
    return total


def noneish(a):
    if a is None:
        return 1
    if a is not None:
        return 2
    return 3


def nested(rows):
    out = []
    for row in rows:
        for cell in row:
            if cell:
                out.append(cell)
    return out


def tried(a):
    try:
        return a[0]
    except TypeError:
        return None


def matched(command):
    match command:
        case ["go", n]:
            return n
        case {"key": k}:
            return k
        case other:
            return other


def counted(n):
    return [i for i in range(n) if i]


def gen(n):
    for i in range(n):
        yield i


def closure(a):
    def inner():
        if a:
            return 1
        return 2

    return inner


async def aiter(rows):
    total = 0
    async for row in rows:
        total += 1
    return total


def far(flag):
    x = 0
    if flag:
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
        x += 1
    return x


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


def rows_of(code):
    return list(code.co_branches())


def sources(code, branches):
    return [code.co_code[offset] for offset, _, _ in branches]


def landed(code, branches):
    runs = list(code.co_lines())
    return [
        [any(start <= off < end for start, end, _ in runs) for off in row] for row in branches
    ]


PLAIN = (lambda a, b: a + b).__code__
TERNARY = (lambda a: 1 if a else 2).__code__
KIND = type(PLAIN)
BOOK = list(vars(KIND))
LINECURSOR = type(PLAIN.co_lines())
BRANCHCURSOR = type(PLAIN.co_branches())
POSITIONCURSOR = type(PLAIN.co_positions())
NOP = nop.__code__
JUMP = jump.__code__
LOOPS = loops.__code__
NONE = noneish.__code__
NESTED = nested.__code__
TRIED = tried.__code__
MATCHED = matched.__code__
COUNTED = counted.__code__
GENERATOR = gen.__code__
ASYNC = aiter.__code__
FAR = far.__code__
CLOSED = closure(1).__code__

BODIES = [
    ("nop", NOP),
    ("ternary", TERNARY),
    ("plain", PLAIN),
    ("jump", JUMP),
    ("loops", LOOPS),
    ("noneish", NONE),
    ("nested", NESTED),
    ("tried", TRIED),
    ("matched", MATCHED),
    ("comprehension", COUNTED),
    ("generator", GENERATOR),
    ("async for", ASYNC),
    ("closure", CLOSED),
    ("far", FAR),
]
SEEN = sorted(set(source for _, code in BODIES for source in sources(code, rows_of(code))))
BEFORE = rows_of(LOOPS)

print("== the row, and the two ways of reaching it")
row = vars(KIND)["co_branches"]
print("  kind %s / qualname %s / objclass %s" % (
    type(row).__name__, row.__qualname__, row.__objclass__ is KIND))
print("  doc %r / sig %r" % (row.__doc__, row.__text_signature__))
print("  bound %s / self %s" % (
    type(JUMP.co_branches).__name__, JUMP.co_branches.__self__ is JUMP))
print("  names carrying it %s" % [n for n in dir(KIND) if "branch" in n])
print("  between the cursors %s" % BOOK[BOOK.index("co_lines") + 1:BOOK.index("co_positions")])
print("  the cursors in order %s" % BOOK[BOOK.index("__sizeof__"):BOOK.index("replace")])
print("  bound call %s" % told(lambda: list(JUMP.co_branches())))
print("  unbound call %s" % told(lambda: list(KIND.co_branches(JUMP))))

print()
print("== what the row refuses")
print("  called with an argument   %s" % refusal(lambda: JUMP.co_branches(1)))
print("  unbound with nothing      %s" % refusal(lambda: row()))
print("  unbound on an int         %s" % refusal(lambda: row(1)))
print("  written on a value        %s" % refusal(lambda: setattr(JUMP, "co_branches", 1)))
print("  deleted on a value        %s" % refusal(lambda: delattr(JUMP, "co_branches")))
print("  written on the type       %s" % refusal(lambda: setattr(KIND, "co_branches", 1)))
print("  deleted on the type       %s" % refusal(lambda: delattr(KIND, "co_branches")))
print("  still a row after all     %s" % ("co_branches" in vars(KIND)))

print()
print("== the cursor the row hands back")
cursor = JUMP.co_branches()
print("  name %s / is lines %s / is positions %s" % (
    type(cursor).__name__, type(cursor) is LINECURSOR, type(cursor) is POSITIONCURSOR))
print("  isinstance of the line cursor %s" % isinstance(cursor, LINECURSOR))
print("  repr opens %s" % repr(cursor).split(" object")[0])
print("  iter is itself %s" % (iter(cursor) is cursor))
print("  first row %s" % (next(cursor),))
print("  rest %s" % list(cursor))
print("  exhausted %s" % refusal(lambda: next(cursor)))
print("  reread %s" % (rows_of(JUMP) == [row for row in JUMP.co_branches()]))
print("  length hint %s" % refusal(lambda: JUMP.co_branches().__length_hint__()))
print("  has no self %s" % refusal(lambda: cursor.__self__))
print("  state %r" % (JUMP.co_branches().__getstate__(),))
print("  reduce %s" % refusal(lambda: JUMP.co_branches().__reduce__()))
print("  made into a tuple %s" % told(lambda: tuple(JUMP.co_branches())))

print()
print("== the rows, body by body")
for label, code in BODIES:
    branches = rows_of(code)
    print("  %-14s %2d %s" % (label, len(branches), branches))

print()
print("== which instruction each row starts on")
for label, code in BODIES:
    branches = rows_of(code)
    if branches:
        print("  %-14s %s" % (label, sources(code, branches)))
print("  opcode numbers seen %s" % SEEN)
print("  plain jumps among them %s" % [n for n in (75, 76, 77, 106) if n in SEEN])
print("  every offset even %s" % all(
    off % 2 == 0 for _, code in BODIES for branch in rows_of(code) for off in branch))

print()
print("== the same three numbers, asked of the line runs")
for label, code in BODIES:
    branches = rows_of(code)
    if branches:
        print("  %-14s %s" % (label, landed(code, branches)))

print()
print("== what a row does not depend on")
print("  the three cursors' names %s" % [
    type(x).__name__ for x in (JUMP.co_lines(), JUMP.co_branches(), JUMP.co_positions())])
print("  rows of a copy %s" % (
    rows_of(JUMP.replace(co_name="other", co_consts=(1, 2))) == rows_of(JUMP)))
for _ in range(200):
    loops(8)
    noneish(1)
    counted(4)
    list(gen(3))
print("  rows before and after running %s" % (rows_of(LOOPS) == BEFORE))
print("  rows of a walked iterator %s" % (
    rows_of(LOOPS) == [row for row in LOOPS.co_branches()]))
print("  the widest row, by its own numbers %s" % rows_of(FAR))
