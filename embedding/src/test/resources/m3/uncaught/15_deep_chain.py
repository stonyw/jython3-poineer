# Three exceptions in a row: the oldest is printed first, each block with its own header.
def level_one():
    try:
        int('no')
    except ValueError as err:
        raise OSError('one') from err

def level_two():
    try:
        level_one()
    except OSError as outer:
        raise RuntimeError('two') from outer

level_two()
