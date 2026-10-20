# An exception made inside a generator body keeps the generator frame in the report.
def deep(x):
    return 1 / x


def generator(depth):
    yield 'parked'
    return deep(depth)


for entry in generator(0):
    print('saw', entry)
