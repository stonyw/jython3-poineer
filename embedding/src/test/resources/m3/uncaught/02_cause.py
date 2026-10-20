# `raise X from err`: the cause is named, and its own frames are printed above.
def load(name):
    try:
        return int(name)
    except ValueError as err:
        raise RuntimeError('cannot load ' + name) from err

load('abc')
