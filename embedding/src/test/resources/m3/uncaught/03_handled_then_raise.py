# An exception a handler finished with is not the context of the next one.
def run():
    try:
        1 / 0
    except ZeroDivisionError:
        print('handled zero')
    raise KeyError('after')

run()
