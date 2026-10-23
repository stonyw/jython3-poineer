"""What `sorted()` puts first when two names share more than their first letter.

`dir()` is the same question — it answers sorted, and every name in scope goes through that ordering — so
the two are measured together here. What decides both is which number a character counts as: CPython reads
one per character, so a name carrying an emoji sorts after a name carrying a character from the planes above
it, and a longer name only wins on length once the numbers it shares have run out. A runtime that reads the
units a Java string holds instead gets the opposite answer for exactly those two names.

Everything here is what the reference interpreter answers, read off the same run.
"""


def describe(label, thunk):
    try:
        print(f"{label}: {thunk()!r}")
    except Exception as error:
        print(f"{label}: {type(error).__name__}: {error}")


print("--- two names that share an affix")

print("sorted:", sorted(["apple", "applet", "appl", "banana", "Apple", "APPLE"]))
print("min and max:", min(["pear", "fig", "Date"]), max(["pear", "fig", "Date"]))
print("as numbers:", sorted([1, 10, 2, -3, 0]))
print("mixed, and the complaint:", describe("mixed", lambda: sorted(["a", 1])))
print("two of a kind keep their order:", sorted(word for word in "a b a B".split()))


class Named:
    def __init__(self, name):
        self.name = name

    def __repr__(self):
        return self.name


print("a key sorts without sorting what it read:", sorted([Named("zebra"), Named("ant")], key=lambda n: n.name))
print("and turns the comparison round:", sorted([Named("c"), Named("a")], key=lambda n: n.name, reverse=True))
print("a name longer than a key:", describe("a key of none", lambda: sorted(["b", "a"], key=lambda word: None)))


print("--- the order a list of names is put in")

print("the underscore and a plain one:", sorted(["_", "b", "a"]))
print("upper before lower:", sorted(["Z", "a"]))
print("an accent is not its letter:", sorted(["e", "é", "E", "ë"]))
print("a plane above the emoji and a lower one:", sorted(["\U0001f041", "\U0001f042"]))
print("an emoji and a private-use character:", sorted(["\U0001f041", "\ue000"]))
print("the same two, the other way:", sorted(["\ue000", "\U0001f041"]))
print("three names sharing a prefix:", sorted(["é", "ê", "e"]))
print("length only decides after the shared part:", sorted(["ab", "a", "abc"]))


print("--- what `dir()` sorts")


class Ordered:
    def __dir__(self):
        return ["__z__", "_a", "b", "_", "A", "é", "ª", "汉", "\U0001f041", "\ue000", "ß"]


print("dir sorts:", dir(Ordered()))
print("sorted of the very same list:", sorted(Ordered().__dir__()))
print("and the two agree:", dir(Ordered()) == sorted(Ordered().__dir__()))
