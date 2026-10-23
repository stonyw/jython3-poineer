"""The two readings a code object gives of one position table.

``co_lines()`` walks the table's *entries* and joins the neighbours that name the same line, so it
answers triples of ``(start offset, end offset, line)`` — offsets in bytes, the same ones a traceback
records, and the line is ``None`` wherever the table names none. ``co_positions()`` walks *code units*
instead and answers one ``(line, end line, column, end column)`` tuple for each, so it has exactly one
answer per two bytes of ``co_code`` and joins nothing: what repeats in the table repeats in its output
too. A line of 0 stays 0 rather than becoming ``None``, and both readings come from the same table the
traceback of an error in that code is built from.

Both are iterators, and each is a cursor over one pass of the answer: ``iter`` of one is itself, the
items are there when it is made, and once it is spent it raises a bare ``StopIteration`` with no
arguments. Asked of the value it is bound to; asked through the type it is an unbound method descriptor
that takes a code object and nothing else. Every line number below is this script's own, so the two
interpreters have to agree about the table of a script they both read from source."""


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


def one(a):
    return a + 1


def guarded(b):
    try:
        risky = 1 / b
    except ZeroDivisionError:
        risky = None
    finally:
        cleaned = True
    return risky, cleaned


def counted(*rest):
    total = 0
    for step in rest:
        total += step
    else:
        total -= 1
    return total


def stream(n):
    for index in range(n):
        yield index
    return n


def enclosing(given):
    def inner():
        return given

    return inner


def squared(n):
    return {x * x for x in range(n)}


async def awaiting():
    total = 0
    async for step in range(2):
        total += step
    return total


def spread(n):
    return (
        n
        + 1
    )


by_lambda = lambda z: z - 1


def summarise(code):
    positions = list(code.co_positions())
    unplaced = 0
    no_columns = 0
    for place in positions:
        if place[0] is None:
            unplaced += 1
        if place[2] is None:
            no_columns += 1
    unlined = 0
    for _, _, line in code.co_lines():
        if line is None:
            unlined += 1
    return (
        "%s of %d bytes | runs %d unlined %d | units %d one per pair %s unplaced %d no columns %d"
        " | head %s %s"
        % (
            code.co_name,
            len(code.co_code),
            len(list(code.co_lines())),
            unlined,
            len(positions),
            len(positions) * 2 == len(code.co_code),
            unplaced,
            no_columns,
            positions[0],
            positions[-1],
        )
    )


def first_disagreement(code):
    """What the two readings would have to say differently, or True when neither says anything else."""

    positions = list(code.co_positions())
    runs = list(code.co_lines())
    at = 0
    for start, end, line in runs:
        if start != at:
            return "a run that does not start where the last one ended"
        if start % 2 or end % 2 or end < start:
            return "an offset that is not a whole code unit"
        for unit in range(start // 2, end // 2):
            if positions[unit][0] != line:
                return "a unit whose line is not its run's"
            if line is None and positions[unit] != (None, None, None, None):
                return "a unit that names something where the line is not named"
        at = end
    if at != len(code.co_code):
        return "runs that leave the end of the code uncovered"
    for index in range(len(runs) - 1):
        if runs[index][2] == runs[index + 1][2]:
            return "two neighbouring runs of one line"
    return True


print("--- what each code object answers")

for label, code in [
    ("a one-line body", one.__code__),
    ("a handler and a cleanup", guarded.__code__),
    ("a loop with an else", counted.__code__),
    ("a generator", stream.__code__),
    ("a closure", enclosing.__code__),
    ("the inner of a closure", enclosing(1).__code__),
    ("a comprehension of a set", squared.__code__),
    ("a coroutine", awaiting.__code__),
    ("a spread-out expression", spread.__code__),
    ("a lambda", by_lambda.__code__),
]:
    print()
    print(label, code.co_name, "line", code.co_firstlineno)
    print("  lines:", list(code.co_lines()))
    if len(code.co_code) <= 60:
        print("  positions:", list(code.co_positions()))
    else:
        print("  ", summarise(code))
    print("  agrees:", first_disagreement(code))

print("--- the module's own code, read from the frame of an error raised at this level")

try:
    raise ValueError("at module level")
except ValueError as error:
    module = error.__traceback__.tb_frame.f_code

print("  name:", module.co_name, "first line:", module.co_firstlineno)
print("  runs:", len(list(module.co_lines())), "summarised:", summarise(module))
print("  agrees:", first_disagreement(module))
print("  the frame this script runs in is the module's own:", module is not one.__code__)

print("--- an iterator, asked twice")

cursor = one.__code__.co_lines()
print("  type:", type(cursor).__name__, "and of a position read:", type(one.__code__.co_positions()).__name__)
print("  iter is it:", iter(cursor) is cursor)
print("  first:", next(cursor))
print("  the rest:", list(cursor))
print("  spent:", list(cursor))
print(describe("next of a spent one", lambda: next(cursor)))
print("  a second pass of the same read:", list(one.__code__.co_positions())[:2])
places = list(one.__code__.co_positions())
print("  two units of one entry: equal", places[3] == places[4], "one object", places[3] is places[4])

print("--- a method of the value, and a descriptor of the type")

code = one.__code__
print("  name:", code.co_lines.__name__, "qualname:", code.co_lines.__qualname__)
print("  doc:", repr(code.co_lines.__doc__))
print("  self:", getattr(code.co_lines, "__self__", "none") is code)
print("  equal to itself asked twice:", code.co_lines == code.co_lines)
print("  descriptor type:", type(type(code).co_lines).__name__, "doc:", repr(type(code).co_lines.__doc__))
print("  through the type:", list(type(code).co_lines(code)) == list(code.co_lines()))
print("  of the value, listed:", len(list(code.co_lines())), "positions:", len(list(code.co_positions())))
print("  in dir of the value:", sorted(n for n in dir(code) if n in ("co_lines", "co_positions")))
print("  in dir of the type:", sorted(n for n in dir(type(code)) if n in ("co_lines", "co_positions")))

print("--- what it refuses")

print(describe("a bound read with an argument", lambda: code.co_lines(1)))
print(describe("a bound read with a keyword", lambda: code.co_lines(x=1)))
print(describe("an unbound read with nothing", lambda: type(code).co_lines()))
print(describe("an unbound read of another type", lambda: type(code).co_lines("a string")))
print(describe("a position read of another type", lambda: type(code).co_positions(3)))
print(describe("a read written to", lambda: setattr(code, "co_lines", 1)))
print(describe("a read taken away", lambda: delattr(code, "co_lines")))
print(describe("a read asked of another value", lambda: "".co_lines()))
