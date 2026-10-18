print(1 < 2, 2 < 1, 1 <= 1, 1 > 2, 2 >= 2, 1 == 1.0, 1 != 1.0, 3 == True)
print(1 < 2 < 3, 1 < 2 > 1, 3 > 2 > 1, 1 <= 1 <= 1, 1 < 1 < 2)
print((1, 2) == (1.0, 2.0), [1, 2] == [1.0, 2.0], [1, 2] < [1, 3], [1] < [1, 0], [] < [0])
print("abc" < "abd", "ABC" < "abc", "ab" < "abc", "" < "a", "a" == "a")
print({1, 2} == {2, 1}, {1, 2} < {1, 2, 3}, {1} <= {1}, {1} > {2}, frozenset([1]) == {1})
print({"a": 1} == {"a": 1.0}, {"a": 1} == {"a": 2}, {"a": 1, "b": 2} == {"b": 2, "a": 1})
print(None is None, None is not False, False == 0, bool() == False, True is not False)
zero = 0
hand = 257
other = 257
text = "short string"
again = text
empty = ()
print(zero == 0, hand == other, again is text, empty == ())
print(7 & 3, 7 | 3, 7 ^ 3, ~7, 7 << 3, 7 >> 3, -8 >> 1, -8 // 2)
print(sorted([3, 1, 2]), sorted(["b", "a"], reverse=True), min(4, 2, 9), max([1, 5]), min([2]))
print(sum([1, 2, 3], 100), sum([], 5), sum([0.5, 0.25]), sum(iter([1, 2]), 10))
print([x for x in range(12) if x % 3 == 0 and x != 6])
print(bool([1, 2]) and bool([]) or not bool([]), bool({}), bool(0.0), bool("x"), bool(None))
print(0 or "" or [] or {} or "last", 1 and 2 and "end", None or False or 0 or "falsy-end")
print((1 if True else 2) + (0 if False else 1), not 0, not [], not "", not None)
print(isinstance(1, int), isinstance(True, int), isinstance(1.0, float), isinstance("a", str))
print(isinstance([], (list, tuple)), isinstance((), (list, tuple)), type([]) is list, type(()) is tuple)
print(issubclass(bool, int), issubclass(int, object), issubclass(str, int))
print(1 in [1, 2], "a" in "cat", "a" in {"a": 1}, 5 in range(10), 5 in range(1, 10, 2))
print((1, 2) in [(1, 2)], frozenset([3]) in {frozenset([3])}, 4 not in [1, 2])
print((3).bit_length(), (-3).bit_length(), (0).bit_length(), (255).bit_length())
print((3).bit_length(), (-3).bit_length(), (0).bit_length(), (255).bit_length(), (2**70).bit_length())
