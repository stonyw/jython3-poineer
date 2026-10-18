def positional(a, b, /, c, d=4, *rest, e, f=6, **kwargs):
    return (a, b, c, d, rest, e, f, tuple(sorted(kwargs.items())))


print(positional(1, 2, 3, e=7))
print(positional(1, 2, 3, 4, 5, 6, e=7, z=8, a=9))
packed = [1, 2, 3]
named = {"e": 7, "f": 8}
print(positional(*packed, **named))
print(positional(1, 2, **{"c": 3, "e": 4, "extra": None}))


def defaults(x=None, *, y=[1, 2]):
    return x, y


print(defaults(), defaults(0, y=[]), defaults(1, y=2))


def swap(first, second):
    return second, first


a, b = swap(1, 2)
print(a, b)
print(swap(*[1, 2]), swap(**{"first": 1, "second": 2}))


def collect(*items):
    return len(items), items[0] if items else None, items[-1] if items else None


print(collect(), collect(1), collect(1, "two", 3.0, None))


def merge(**options):
    return sorted(options)


print(merge(), merge(b=2, a=1), merge(**{"z": 1, "y": 2}))


def fib(n):
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)


print([fib(n) for n in range(12)])


def deep(n):
    return 0 if n == 0 else 1 + deep(n - 1)


print(deep(100))


def apply(function, value):
    return function(value)


square = lambda value: value * value
print(apply(square, 9), apply(lambda t: t[0] + t[1], (3, 4)))
print((lambda a, b=2, *rest, **kw: (a, b, rest, sorted(kw)))(1, 2, 3, k=4))
print((lambda *args, **kw: (args, kw))())
print((lambda x, /, y=1, *z, w, **v: (x, y, z, w, sorted(v)))(1, w=2, q=3))


def shared(value=[]):
    value.append(len(value))
    return value


print(shared(), shared(), shared())


def returns_tuple():
    return 1, 2, 3


first, *middle, last = returns_tuple()
print(first, middle, last)
head, *tail = [1, 2, 3, 4]
print(head, tail)
print(*returns_tuple(), sep="|")
print(sorted([3, 1, 2], reverse=True), sorted("banana"), sorted({2, 1}))
