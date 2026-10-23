# A throw the delegate does not answer: the finally of the generator that delegated still runs, and the
# report carries the step that was asked and both frames the delegation joined.
def guarded(source):
    try:
        yield from source
    finally:
        print('the outer ran its finally')


def keeper():
    yield 'parked'


driven = guarded(keeper())
next(driven)
driven.throw(ValueError('thrown through'))
