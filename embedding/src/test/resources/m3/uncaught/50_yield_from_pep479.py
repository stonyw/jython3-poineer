# A StopIteration raised by hand inside a delegate ends the delegation as a RuntimeError, with the
# raised one kept as its cause and both frames of the delegation in the report.
def raiser():
    yield 'one'
    raise StopIteration('raised by hand')


def over(source):
    got = yield from source
    yield 'after ' + repr(got)


print('driven', list(over(raiser())))
