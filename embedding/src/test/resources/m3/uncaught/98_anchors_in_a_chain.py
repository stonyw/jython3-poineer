# Each exception in a chain carries its own anchors.
def inner():
    raise ValueError('inner')

def outer():
    try:
        inner()
    except ValueError as error:
        raise RuntimeError('outer') from error

outer()
