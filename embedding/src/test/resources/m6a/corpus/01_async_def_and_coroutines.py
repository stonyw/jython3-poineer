# M6-a slice 1: `async def` builds a coroutine function, and calling one builds a coroutine object.
# Nothing here starts an event loop. What is asked is what a program can see without one: the code
# flags, the object's type and state, `__await__`, and the transmission of a value out of a finished
# coroutine through `StopIteration.value`.

import _types


async def one():
    return 1


c = one()
print(type(c).__name__)
print(type(c) is _types.CoroutineType)
print(c.cr_running, c.cr_suspended, c.cr_await, c.cr_origin)
print(one.__code__.co_flags & 0x80 != 0, one.__code__.co_flags & 0x200 != 0)
print(isinstance(c, _types.CoroutineType))
print(c.cr_code.co_name, c.cr_code.co_flags & 0x80 != 0)
print(c.cr_frame is not None)
print(repr(c).split(' at ')[0])

# `__await__` returns a wrapper, and driving that wrapper runs the coroutine and hands its return
# value over as the exception's value.
w = c.__await__()
print(type(w).__name__)
try:
    print(w.send(None))
except StopIteration as e:
    print('StopIteration', e.value)
print(c.cr_frame)
print(c.cr_running, c.cr_suspended)


# The name attributes are writable on a coroutine, and `close` finishes one that never started.
async def named():
    return 5


c2 = named()
c2.__name__ = 'renamed'
c2.__qualname__ = 'renamed.q'
print(c2.__name__, c2.__qualname__)
c2.close()
print(c2.cr_frame, c2.cr_running)


# Suspend propagation: awaiting a custom awaitable, then awaiting a coroutine that itself awaits,
# leaves the delegate addressable in `cr_await` until the next send.
class Pause:
    def __await__(self):
        yield 'paused'
        return 42


async def inner():
    return await Pause()


async def outer():
    return await inner()


c3 = outer()
print(c3.send(None))
print(c3.cr_await is not None, type(c3.cr_await).__name__)
try:
    print(c3.send(None))
except StopIteration as e:
    print('value', e.value)
print(c3.cr_await, c3.cr_suspended)
