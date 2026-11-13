"""Top-level await: what `compile` accepts, and what runs the coroutine it makes.

CPython compiles a module with `PyCF_ALLOW_TOP_LEVEL_AWAIT` into a coroutine body; nothing runs it
until a driver steps it.  This file pins both halves: the flag the code object carries and the shape
of the text a reader can see, and then the values the body produces when `FunctionType(co, globals)`
or `eval(co, globals)` hands it to a plain send loop.  The awaitables it meets on the way -- an async
generator's `asend` and `athrow` wrappers included -- are asked about their names and their refusals
too, because those are the parts of the protocol a program can see.
"""

import sys

# The reference interpreter's own library, exactly as the other corpora do it.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import ast
import inspect
from types import FunctionType


def report(label, action):
    try:
        result = action()
    except SyntaxError as error:
        # The message, not the whole report: the caret line belongs to the importer that prints it.
        print(label, "ERR", type(error).__name__, error.msg)
        return
    except BaseException as error:
        print(label, "ERR", type(error).__name__)
        for line in str(error).splitlines():
            print("   ", line)
        return
    print(label, "OK", result)


async def arange(n):
    for i in range(n):
        yield i


async def slow(n):
    "slow's docstring"
    before = await sleep(0, n)
    yield before * 10


async def sleep(delay, result=None):
    return result


class Lock:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc_info):
        return False


def drive(coroutine):
    """Step a coroutine the way a send loop does, and give back what it returned."""
    value = None
    try:
        while True:
            coroutine.send(None)
    except StopIteration as stop:
        value = stop.value
    coroutine.close()
    return value


TOP = ast.PyCF_ALLOW_TOP_LEVEL_AWAIT


def compile_top(source, mode="exec", optimize=-1):
    return compile(source, "?", mode, flags=TOP, optimize=optimize)


def dot(co):
    return co.co_name, co.co_names, tuple(type(c).__name__ for c in co.co_consts), co.co_flags


# --- what the flag asks for ------------------------------------------------

await_one = compile_top("a = await sleep(0, result=1)")
print("toplevel-dot", dot(await_one))
print("toplevel-coroutine", bool(await_one.co_flags & inspect.CO_COROUTINE))
report("toplevel-plain", lambda: dot(compile("a = 1", "?", "exec")))
report("toplevel-plain-flagged", lambda: dot(compile_top("a = 1")))
report("await-without-flag", lambda: compile("a = await sleep(0)", "?", "exec"))
report("await-in-single", lambda: dot(compile_top("a = await sleep(0, 7)", "single")))

# --- the two ways a driver gets a coroutine --------------------------------

scope = {"sleep": sleep, "a": 0}
coroutine = FunctionType(await_one, scope)()
print("function-type", type(coroutine).__name__, coroutine.__name__, coroutine.__qualname__)
print("function-type-drive", drive(coroutine), scope["a"])

scope = {"sleep": sleep, "a": 0}
print("eval-drive", drive(eval(await_one, scope)), scope["a"])

# --- the statements the flag lets a module have ----------------------------

cases = [
    ("await", "a = await sleep(0, result=3)"),
    ("async-for", "a = 0\nasync for i in arange(3):\n    a = a * 10 + i"),
    ("async-with", "a = 0\nasync with Lock() as l:\n    a = l is not None"),
    ("async-comp", "a = [x async for x in arange(4)]"),
    ("async-comp-nested", "a = [x async for x in (x async for x in arange(5))]"),
    ("async-comp-await", "a = [await sleep(0, x) async for x in arange(3)]"),
    ("async-set-comp", "a = {await sleep(0, x) async for x in arange(3)}"),
    ("async-dict-comp", "a = {x: await sleep(0, x) async for x in arange(2)}"),
    ("async-comp-filter", "a = [x async for x in arange(5) if x % 2]"),
    ("await-in-comp", "a = [await sleep(0, 1) for _ in [0]]"),
    ("assert-await", "a = 1\nassert not await sleep(0)"),
]
for label, source in cases:
    for mode in ("exec", "single"):
        for optimize in (-1, 0, 1, 2):
            scope = {"sleep": sleep, "arange": arange, "Lock": Lock, "a": None}
            try:
                code = compile_top(source, mode, optimize)
            except SyntaxError as error:
                print(label, mode, optimize, "ERR", type(error).__name__, error.msg)
                continue
            got = drive(FunctionType(code, scope)())
            print(label, mode, optimize, repr(scope["a"]), got)

# The same bodies again through `eval`, which is the entry the test suite uses.
scope = {"sleep": sleep, "arange": arange, "Lock": Lock, "a": None}
print("eval-async-for", drive(eval(compile_top(cases[1][1]), scope)), scope["a"])

# --- the async generator protocol -----------------------------------------

generator = arange(2)
print("agen-type", type(generator).__name__)
print("agen-name", generator.__name__, generator.__qualname__, generator.__doc__)
print("agen-repr", repr(generator).startswith("<async_generator object arange at 0x"))
report("agen-not-iterable", lambda: iter(generator))
print("agen-aiter-self", generator.__aiter__() is generator)

step = generator.__anext__()
print("asend-type", type(step).__name__)
print("asend-await-self", step.__await__() is step)
print("asend-iter-self", iter(step) is step)
try:
    step.send(None)
except StopIteration as stop:
    print("asend-yield", stop.value)
report("asend-reuse", lambda: step.send(None))
report("asend-next-reuse", lambda: next(step))

print("agen-second", drive(generator.__anext__()))
report("agen-end", lambda: generator.__anext__().send(None))

# --- closing and throwing into the awaitables -----------------------------

closing = arange(5)
print("aclose-first", drive(closing.__anext__()))
report("aclose-drive", lambda: drive(closing.aclose()))
report("aclose-twice", lambda: drive(closing.aclose()))
report("anext-after-aclose", lambda: closing.__anext__().send(None))

throwing = arange(5)
print("athrow-first", drive(throwing.__anext__()))
thrown = throwing.athrow(ValueError)
print("athrow-type", type(thrown).__name__)
report("athrow-drive", lambda: drive(thrown))
report("athrow-after", lambda: throwing.__anext__().send(None))

ignoring = arange(5)
print("ignoring-first", drive(ignoring.__anext__()))


async def stubborn():
    try:
        yield 1
    except GeneratorExit:
        yield 2


stubborn_gen = stubborn()
print("stubborn-first", drive(stubborn_gen.__anext__()))
report("aclose-ignored", lambda: drive(stubborn_gen.aclose()))

# --- an awaitable that is not a coroutine ----------------------------------

report("await-not-awaitable", lambda: drive(eval(compile_top("await 1"), {})))

# `async for` over an object whose `__aiter__` answers nothing useful.
async def bad_aiter():
    async for value in 1:
        pass


report("async-for-int", lambda: drive(bad_aiter()))


class NoANext:
    def __aiter__(self):
        return self


async def missing_anext():
    async for value in NoANext():
        pass


report("async-for-no-anext", lambda: drive(missing_anext()))

# --- FunctionType refusals -------------------------------------------------

code = compile("a = 1", "?", "exec")
report("function-code", lambda: FunctionType("not code", {}))
report("function-globals", lambda: FunctionType(code, []))
report("function-name", lambda: FunctionType(code, {}, 3))
report("function-defaults", lambda: FunctionType(code, {}, None, []))
report("function-closure", lambda: FunctionType(code, {}, None, None, []))
report("function-kwdefaults", lambda: FunctionType(code, {}, None, None, None, []))

print("function-name-set", FunctionType(code, {}, "renamed").__name__)
print("function-qualname", FunctionType(compile("a = 1", "?", "exec"),
                                        {}, "renamed").__qualname__)
print("function-module", FunctionType(code, {"__name__": "mod"}).__module__)
print("function-doc", FunctionType(compile("'''howdy'''\na = 1", "?", "exec"), {}).__doc__)
