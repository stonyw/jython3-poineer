# Four delegations deep and the last one standing on a plain iterator: every frame the value passed
# through is in the report, in the order the caller asked, and the caller's own frame is the first.
class Counted:
    def __init__(self, times):
        self.left = times

    def __iter__(self):
        return self

    def __next__(self):
        if self.left <= 0:
            raise ValueError('asked for one more than there is')
        self.left -= 1
        return self.left


def one(source):
    return (yield from source)


def two(source):
    return (yield from one(source))


def three(source):
    return (yield from two(source))


def drive(source):
    return list(three(source))


print('driven', drive(Counted(2)))
