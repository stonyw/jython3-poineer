# asyncio from `import asyncio` to `asyncio.run`: the loop, the scheduler and a Future.
import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import asyncio


def show(label, fn):
    try:
        print(label, "->", fn())
    except BaseException as error:
        print(label, "!!", type(error).__name__, error)


# The pair asyncio saves and restores around run_forever. The runtime keeps it and hands it back.
hooks = sys.get_asyncgen_hooks()
print("hooks", repr(hooks), type(hooks).__name__, len(hooks))
print("hook-fields", hooks.firstiter, hooks.finalizer, hooks.n_fields)
print("hook-replace", repr(hooks.__replace__(firstiter=len)))
show("hooks-arg", lambda: sys.get_asyncgen_hooks(1))
show("hooks-callable", lambda: sys.set_asyncgen_hooks(firstiter=1))
print("finalizing", sys.is_finalizing())


async def main():
    loop = asyncio.get_running_loop()
    order = []
    loop.call_soon(order.append, "soon")
    loop.call_later(0, order.append, "later-zero")
    loop.call_at(loop.time(), order.append, "at-now")
    order.append("start")
    await asyncio.sleep(0)
    order.append("after-zero")
    first = list(order)
    loop.call_later(0.02, order.append, "later")
    start = loop.time()
    await asyncio.sleep(0.05)
    return first, list(order), loop.time() - start >= 0.04


show("run", lambda: asyncio.run(main()))


async def one():
    await asyncio.sleep(0)
    return "one"


async def two():
    return "two"


async def three():
    await asyncio.sleep(0.005)
    return "three"


async def boom():
    raise ValueError("boom")


async def gathering():
    gathered = await asyncio.gather(one(), two(), three())
    tolerated = await asyncio.gather(one(), boom(), return_exceptions=True)
    try:
        await asyncio.gather(one(), boom())
    except ValueError as error:
        raised = ("ValueError", str(error))
    return gathered, [type(item).__name__ for item in tolerated], raised


show("gather", lambda: asyncio.run(gathering()))


async def futures():
    loop = asyncio.get_running_loop()
    fut = loop.create_future()
    before = (fut.done(), fut.cancelled())
    seen = []
    fut.add_done_callback(lambda f: seen.append(("first", f.result())))
    fut.add_done_callback(lambda f: seen.append(("second", f.cancelled())))
    loop.call_soon(fut.set_result, 7)
    value = await fut
    await asyncio.sleep(0)
    failed = loop.create_future()
    failed.set_exception(KeyError("k"))
    try:
        await failed
    except KeyError as error:
        caught = ("KeyError", str(error))
    task = asyncio.ensure_future(asyncio.sleep(30))
    kind = type(task).__name__
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        cancelled = "CancelledError"
    return before, value, seen, caught, kind, task.done(), task.cancelled(), cancelled


show("future", lambda: asyncio.run(futures()))


async def timing():
    loop = asyncio.get_running_loop()
    start = loop.time()
    await asyncio.sleep(0.02)
    return loop.time() - start >= 0.015


show("sleep", lambda: asyncio.run(timing()))
show("run-bad", lambda: asyncio.run(42))
show("running-outside", asyncio.get_running_loop)
show("eventloop-outside", asyncio.get_event_loop)


async def nested():
    coroutine = one()
    try:
        asyncio.run(coroutine)
    except RuntimeError as error:
        # `run` refuses before it consumes the coroutine, so close it here: otherwise CPython emits
        # a "never awaited" warning from the coroutine's finalizer and the two stderr streams differ.
        coroutine.close()
        return str(error)


show("nested", lambda: asyncio.run(nested()))


async def twice():
    return 1


show("run-twice", lambda: (asyncio.run(twice()), asyncio.run(twice())))
show("eventloop-after", asyncio.get_event_loop)

loop = asyncio.new_event_loop()
print("new-loop", loop.is_closed(), loop.is_running())
asyncio.set_event_loop(loop)
print("current", asyncio.get_event_loop() is loop)
print("until-complete", loop.run_until_complete(twice()))
loop.close()
print("closed", loop.is_closed())
asyncio.set_event_loop(None)
show("eventloop-cleared", asyncio.get_event_loop)
