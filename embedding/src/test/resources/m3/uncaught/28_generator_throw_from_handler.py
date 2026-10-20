# A throw the generator answers with a raise of its own: the thrown one becomes the context, and the
# report of what escaped carries only the frame that was resumed.
def catcher():
    try:
        yield 'parked'
    except ValueError:
        raise KeyError('made from the thrown one')


driven = catcher()
next(driven)
driven.throw(ValueError('thrown'))
