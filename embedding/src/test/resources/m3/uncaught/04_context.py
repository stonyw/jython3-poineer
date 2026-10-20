# A raise inside a handler names the handled exception as its context.
def run():
    try:
        nonexistent
    except NameError:
        raise TypeError('wrapped')

run()
