# An exception made inside a delegate is reported through every `yield from` that was standing on it.
def leaf():
    yield 'one'
    raise IndexError('made in the leaf')


def middle(source):
    got = yield from source
    yield 'after ' + repr(got)


def top(source):
    return (yield from middle(source))


print('driven', list(top(leaf())))
