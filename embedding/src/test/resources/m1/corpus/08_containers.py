items = [3, 1, 4]
items.append(1)
items.extend([5, 9])
items += [2, 6]
items.insert(0, 0)
print(items, len(items), items.count(1), items.index(4))
popped = items.pop()
print(popped, items, items.pop(0), items)
items.remove(1)
print(items)
del items[1]
print(items)
items[1:3] = ["a", "b", "c"]
print(items)
items[1:3] = []
print(items)
del items[1:2]
print(items)
items.reverse()
print(items)
numbers = [9, 2, 7]
numbers.sort()
print(numbers)
numbers.sort(reverse=True)
print(numbers)
numbers.sort(key=lambda n: -n % 5)
print(numbers)
numbers[0:0] = [100]
print(numbers)
print([n for n in numbers if isinstance(n, int)], sorted(set("abc")), list({1: "x"}))

pair = (1, 2)
print(pair, pair[0], pair[-2], len(pair), pair + (3,), pair * 2, 1 in pair, tuple(sorted([2, 1])))
one = (1,)
print(one, () == tuple(), list(pair), pair.index(2), pair.count(1))
left, (middle, *right) = (1, (2, 3, 4))
print(left, middle, right)

book = {"title": "Dune", "year": 1965}
book["author"] = "Herbert"
book.update({"price": 9.5}, rating=4.5)
book["year"] = 1984
print(book, len(book))
print(sorted(book), sorted(book.keys()), sorted(book, key=len), len(book.values()))
print(book.get("missing"), book.get("missing", "dflt"), book.setdefault("rating", 1), book.setdefault("isbn", "x"))
print("title" in book, "price" in book.keys(), 1984 in book.values())
print(book.pop("isbn"), sorted(book))
print(dict([("a", 1), ("b", 2)]), dict(a=1, b=2), dict({"a": 1}, b=2), {**book, "extra": 1})
print({x: x * x for x in range(5)})
counts = {}
for letter in "abracadabra":
    counts[letter] = counts.get(letter, 0) + 1
print(sorted(counts.items()), sorted(counts))

seen = {1, 2, 2, 3}
seen.add(4)
seen.discard(1)
seen.update([5, 5])
print(sorted(seen), len(seen), 2 in seen, sorted(frozenset(sorted(seen))))
other = {3, 4, 5, 6}
print(sorted(seen | other), sorted(seen & other), sorted(seen - other), sorted(seen ^ other))
print(other.issuperset(seen), seen.issubset(other), seen.isdisjoint({90}))
print({n for n in range(10) if n % 2 == 0}, sorted(set("hello")), set([1, 1, 1, 2]))
print(list(zip("abc", (1, 2, 3))), list(zip("abc", [1, 2])), list(enumerate("xy", 3)), list(enumerate(["only"])))
print(list(reversed([1, 2, 3])), list(reversed("abc")), list(iter([7, 8])), next(iter([9, 10])))
