# The third immutable built-in, and the shape `collections.namedtuple` is built from. `tuple.__new__`
# fixes the items, `__init__` still runs afterwards, and a class that writes `__repr__` or `__eq__` is
# the one that answers, though the inherited `repr` shows no type name. An instance of a `tuple` subclass
# is also the argument tuple for `%`, exactly as a plain tuple is — so `'%s' % pair` is refused for the
# same reason `'%s' % (1, 2)` is, while `format` reads it as one value.

class Pair(tuple):
    def __new__(cls, first, second):
        return tuple.__new__(cls, (first, second))

    first = property(lambda self: self[0])
    second = property(lambda self: self[1])

    def swapped(self):
        return tuple(self)[::-1]


p = Pair(1, 2)
print(type(p).__name__, p)
print(p.first, p.second, p == (1, 2), hash(p) == hash((1, 2)))
print(isinstance(p, tuple), len(p), p[0], p.count(1), p.index(2), 2 in p)
print(list(p), p.swapped())


class Named(Pair):
    def __repr__(self):
        return 'Named(%r, %r)' % (self[0], self[1])


n = Named(3, 4)
print(n, n == (3, 4), n == Named(3, 4), type(n).__name__)


class Loud(tuple):
    def __eq__(self, other):
        return 'compared'


print(Loud((1,)) == (1,))

try:
    '%s' % Pair(1, 2)
except TypeError as error:
    print(type(error).__name__, error)

print('{}'.format(Pair(1, 2)), '{0[0]}'.format(Pair(7, 8)))
print(tuple.__new__(Pair, (5, 6)))
print(Pair(1, 2) + (3,), (1, 2) + Pair(3, 4))
print(Pair(1, 2)[:1], Pair.__bases__)
