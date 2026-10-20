# A cause that was itself made inside a handler carries both messages at once.
def run():
    try:
        1 / 0
    except ZeroDivisionError as z:
        try:
            int('x')
        except ValueError as v:
            raise KeyError('nested') from v

run()
