# M6-a slice 1, continued: the refusals. Sending into a fresh or finished coroutine, awaiting a
# value that is not awaitable, and throwing into a coroutine each answer with the reference's own
# sentence. The last two blocks show an exception thrown into an awaitable and an async generator.

import _types


async def g():
    return 2


c = g()
try:
    c.send(5)
except TypeError as e:
    print(type(e).__name__, e)
print(c.close())
try:
    c.send(None)
except RuntimeError as e:
    print(type(e).__name__, e)


async def h():
    await 7


c = h()
try:
    c.send(None)
except TypeError as e:
    print(type(e).__name__, e)
c.close()


async def k():
    await "x"


c = k()
try:
    c.send(None)
except TypeError as e:
    print(type(e).__name__, e)
c.close()


async def t():
    return 3


c = t()
try:
    c.throw(ValueError('boom'))
except ValueError as e:
    print(type(e).__name__, e)
print(c.cr_running)


# An exception thrown at the await point is caught by the coroutine's own `except`.
class Swallow:
    def __await__(self):
        yield 'x'


async def w():
    try:
        await Swallow()
    except ValueError as e:
        return 'caught ' + str(e)


c = w()
print(c.send(None))
try:
    print(c.throw(ValueError('boom')))
except StopIteration as e:
    print('value', e.value)
print(c.cr_suspended)


# An async generator is a different type, with its own flag, and its protocol methods are named
# rather than exercised here.
async def ag():
    yield 1


a = ag()
print(type(a).__name__, type(a) is _types.AsyncGeneratorType)
print(a.ag_running, a.ag_await, a.ag_frame is not None)
print(a.ag_code.co_name, a.ag_code.co_flags & 0x200 != 0)
print(repr(a).split(' at ')[0])
print(ag.__code__.co_flags & 0x200 != 0, ag.__code__.co_flags & 0x80 != 0)
