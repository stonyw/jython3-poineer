# A send that belongs to a delegate which never promised one: the AttributeError is made inside the
# delegation, so the report carries the frame the delegation was parked on.
class Plain:
    def __iter__(self):
        return self

    def __next__(self):
        return 'endless'


def over(source):
    got = yield from source
    yield 'after ' + repr(got)


driven = over(Plain())
print('first', next(driven))
driven.send('handed over')
