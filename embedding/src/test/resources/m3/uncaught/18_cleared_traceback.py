# An exception whose traceback was put away is a complaint with no frames to introduce.
def run():
    raise ValueError('cleared')

try:
    run()
except ValueError as err:
    err.__traceback__ = None
    print('cleared:', err.__traceback__)
    raise err
