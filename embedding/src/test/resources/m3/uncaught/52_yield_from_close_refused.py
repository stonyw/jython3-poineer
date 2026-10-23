# A close the delegate refuses: the delegate's own error is what the caller is given, thrown into the
# generator that delegated, so its finally runs while the report is written.
class Refuses:
    def __iter__(self):
        return self

    def __next__(self):
        return 'endless'

    def close(self):
        raise IndexError('refused at close')


def guarded(source):
    try:
        yield from source
    finally:
        print('the outer ran its finally')


driven = guarded(Refuses())
print('first', next(driven))
driven.close()
