def make_counter():
    total = 0

    def step(by=1):
        nonlocal total
        total += by
        return total

    return step


counter = make_counter()
print(counter(), counter(5), counter(), make_counter()())


def make_adder(amount):
    return lambda value: value + amount


print(make_adder(3)(4), [make_adder(power)(1) for power in range(3)])


def holders():
    out = []
    for index in range(4):
        out.append(lambda: index)
    return [call() for call in out]


print(holders())


def late_bound():
    out = []
    for index in range(4):
        out.append(lambda captured=index: captured)
    return [call() for call in out]


print(late_bound())


marker = "module"


def rename():
    global marker
    previous = marker
    marker = "inside"
    return previous, marker


print(rename(), marker)


def shared_cell():
    box = [0]

    def bump():
        box[0] += 1
        return box[0]

    def peek():
        return box[0]

    return bump, peek


bump, peek = shared_cell()
print(bump(), bump(), peek(), bump(), peek())


def outer(value):
    middle = value * 2

    def inner(extra):
        deeper = middle + extra

        def deepest():
            return deeper * 3

        return deepest()

    return inner(5)


print(outer(4))


pairs = [(1, "b"), (2, "a"), (3, "c")]
print(sorted(pairs, key=lambda item: item[1]))
words = ["pear", "fig", "banana"]
print(sorted(words), sorted(words, key=len), sorted(words, key=lambda w: (-len(w), w)))
print(list(map(lambda n: n * n, range(6))))
print(list(filter(lambda n: n % 3 == 0, range(12))))
print([f(x) for x in (1, 2) for f in (lambda v: v + 1, lambda v: v * 10)])
print(sorted([(len(w), w) for w in words]))
