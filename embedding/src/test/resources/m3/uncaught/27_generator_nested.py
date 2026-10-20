# Two generators deep: each resumed frame names the step that was driving it.
def inner():
    yield 'parked'
    raise IndexError('out of the inner one')


def outer():
    for entry in inner():
        yield entry
    yield 'never reached'


print('driven', list(outer()))
