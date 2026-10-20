# Not only a raise: an error made by an operation inside a handler chains the same way.
def run():
    try:
        1 / 0
    except ZeroDivisionError:
        int('x')

run()
