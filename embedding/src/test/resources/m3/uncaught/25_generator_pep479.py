# A StopIteration escaping a generator is rebuilt as a RuntimeError, and names where it came from.
def helper():
    raise StopIteration('from the helper')


def converts():
    yield 'parked'
    return helper()


for entry in converts():
    print('saw', entry)
