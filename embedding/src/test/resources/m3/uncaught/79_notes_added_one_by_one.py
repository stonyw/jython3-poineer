# add_note: the notes a chain carries, each one added by itself.
def inner():
    error = ValueError('the first')
    error.add_note('a note that fits on one line')
    error.add_note('a note that does not\nend where its line does')
    return error


def outer():
    try:
        raise inner()
    except ValueError as earlier:
        later = KeyError('the second')
        later.add_note('a note on the second')
        raise later from earlier


raise outer()
