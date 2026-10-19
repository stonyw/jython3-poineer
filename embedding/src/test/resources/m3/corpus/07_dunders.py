"""What a class can define so that the built-in operations ask it instead of refusing."""


def describe(label, thunk):
    try:
        return label + " -> " + str(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


class Bag:
    def __init__(self, *items):
        self.items = list(items)

    def __len__(self):
        return len(self.items)

    def __getitem__(self, at):
        return self.items[at]

    def __setitem__(self, at, value):
        self.items[at] = value

    def __delitem__(self, at):
        del self.items[at]

    def __contains__(self, item):
        return item in self.items

    def __iter__(self):
        return BagWalk(self)

    def __repr__(self):
        return "Bag" + repr(tuple(self.items))

    def __str__(self):
        return " + ".join([str(item) for item in self.items])


class BagWalk:
    def __init__(self, bag):
        self.pending = list(bag.items)

    def __next__(self):
        if not self.pending:
            raise StopIteration
        return self.pending.pop(0)


stuff = Bag(1, 2, 3)
print(len(stuff), stuff[0], stuff[-1], repr(stuff), str(stuff))
print(2 in stuff, 9 in stuff)
stuff[0] = 7
print(stuff[0], repr(stuff))
print([item for item in stuff], sum(stuff), list(reversed(list(stuff))))
print(stuff[0:2], repr(stuff[0:2]))
del stuff[1]
print(repr(stuff), len(stuff))
print(describe("index past the end", lambda: stuff[9]))
print(describe("no such key", lambda: Bag().__getitem__("text")))


class Empty:
    def __len__(self):
        return 0


class Full:
    def __bool__(self):
        return True

    def __len__(self):
        return 0


print(bool(Empty()), bool(Full()), bool(Bag()), bool(Bag(1)))
print("as a condition:", "yes" if Empty() else "no", "yes" if Full() else "no")


class Tagged:
    def __init__(self, tag):
        self.tag = tag

    def __eq__(self, other):
        return isinstance(other, Tagged) and other.tag == self.tag

    def __hash__(self):
        return hash(self.tag)

    def __repr__(self):
        return "Tagged(" + self.tag + ")"


a, same, other = Tagged("a"), Tagged("a"), Tagged("b")
print(a == same, a == other, a != other, a != same)
print(len({a, same, other}), a in {other, same}, Tagged("a") == a)
counts = {}
counts[a] = 1
counts[other] = 2
counts[same] = 3
print(sorted(counts.keys(), key=lambda t: t.tag), len(counts), counts[Tagged("a")])


class Unhashable:
    def __hash__(self):
        return None


class EqOnly:
    def __eq__(self, other):
        return True


print(describe("hash refuses", lambda: hash(Unhashable())))
print(describe("eq without hash", lambda: hash(EqOnly())))
print(describe("eq still answers", lambda: EqOnly() == 3))


class Logged:
    def __init__(self):
        self.__dict__["seen"] = []

    def __setattr__(self, name, value):
        self.__dict__["seen"].append(name)
        self.__dict__[name] = value

    def __delattr__(self, name):
        self.__dict__["seen"].append("-" + name)
        del self.__dict__[name]

    def __getattr__(self, name):
        return "made up " + name


noted = Logged()
noted.first = 1
print(noted.first, noted.seen, "first" in noted.__dict__)
del noted.first
print(noted.seen, "first" in noted.__dict__)
print(noted.anything, noted.seen)


class Doubler:
    def __call__(self, value):
        return value * 2


print(Doubler()(21), Doubler()(3))


class WrongLen:
    def __len__(self):
        return -1


class TextLen:
    def __len__(self):
        return "3"


class WrongRepr:
    def __repr__(self):
        return 5


class WrongStr:
    def __str__(self):
        return 5

    def __repr__(self):
        return "WrongStr()"


class WrongHash:
    def __hash__(self):
        return "text"


class NotAnIterator:
    def __iter__(self):
        return 5


class NoNext:
    def __iter__(self):
        return self


class BoolAsText:
    def __bool__(self):
        return ""


class InitReturns:
    def __init__(self):
        return "text"


print(describe("negative length", lambda: len(WrongLen())))
print(describe("text length", lambda: len(TextLen())))
print(describe("repr is not text", lambda: str(WrongRepr())))
print(describe("str is not text", lambda: str(WrongStr())))
print(describe("hash is not an integer", lambda: hash(WrongHash())))
print(describe("iter returns a number", lambda: iter(NotAnIterator())))
print(describe("iterator with no next", lambda: next(iter(NoNext()))))
print(describe("bool returns text", lambda: bool(BoolAsText())))
print(describe("__init__ answers a value", lambda: InitReturns()))
print(describe("eq is not a bool", lambda: Tagged("a") == 1))


class Loud(Tagged):
    def __repr__(self):
        return "LOUD " + self.tag


print(repr(Loud("a")), Loud("a") == Tagged("a"), isinstance(Loud("a"), Tagged))
print([t.__name__ for t in Loud.__mro__])
