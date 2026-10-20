# A close refused by the generator: the exit it caught is the context of what it raised instead.
def raises_from_handler():
    try:
        yield 'parked'
    except GeneratorExit:
        raise IndexError('raised instead of accepting the exit')


closing = raises_from_handler()
next(closing)
closing.close()
