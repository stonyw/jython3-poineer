# `from None` keeps the cause out of the report and the context with it.
def run():
    try:
        int('zz')
    except ValueError as err:
        raise OSError('no') from None

run()
