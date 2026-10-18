import sys

print("version tuple", sys.version_info[:2], sys.version_info[0], sys.version_info[1] >= 14,
      len(sys.version_info), sys.version_info[-1] == 0)
print("platform type", isinstance(sys.platform, str), isinstance(sys.version, str), sys.byteorder)
print("argv", sys.argv)
print("sep/end", 1, 2, sep="|", end="#\n")
print("multi", ["a"], {"b": 1}, (2, 3), 4.5, None, True, False, sep=" & ")
print("no args")
print(sep="X", end="Y\n")
print("to", "stdout", file=sys.stdout, sep=",")

print(len(""), len("abc"), len([1, 2]), len({"a": 1}), len((1,)), len({1, 2, 3}), len(b"abcd"), len(range(7)))
print(str(1), str("a"), str(None), str(True), str([1, "a"]), str({"k": [1]}))
print(repr("a'b"), repr(None), repr(1.5), repr([None, (1,)]), repr({"a": 1}), repr(range(2)), repr(b"x"))
print(bool(""), bool(0), bool([]), bool({}), bool(None), bool(0.0), bool(complex(0, 0)))
print(int("10"), int("10", 2), int(10.9), int("-0x1f", 16), float("1e3"), str(3), list("ab"), tuple([1]), set([1]))
print(chr(65), chr(233), ord("A"), ord("é"), chr(0x1f600))
print(bin(10), oct(10), hex(10), format(255, "x"), format(3.14159, ".3f"), format(1234567, ","), format(7, "04b"))
print(abs(-5), abs(-5.5), abs(0), round(1.4545, 2), round(15, -1), pow(3, 4), pow(10, 2, 7), divmod(17, 5))
print(sorted(range(10), reverse=True)[:3], sorted(["bbb", "a", "cc"], key=len))
print(min("abc"), max("abc"), sum(range(4)), sorted({"b": 1, "a": 2}), sorted([3, 1], key=str))
print(list(enumerate("ab", 1)), list(zip([1, 2], "ab", [True])), list(map(abs, [-1, 2])), list(filter(None, [0, 1, "", "a"])))
print(type(1) is int, callable(print), callable("s"), hasattr("s", "upper"), getattr("s", "upper")() == "S")
d = {"k": 1}
print(isinstance(d, dict), isinstance(sys, type(sys)), issubclass(float, (int, float)))
print(sys.maxsize > 2**31, isinstance(sys.path, list))
