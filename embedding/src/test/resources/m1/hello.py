"""M1's done criterion in one file: hello world, arithmetic, branches, loops, function calls.

Scope note: ``try``/``except``, generator expressions and classes are deliberately absent. Catching is
M3's own surface and is measured by the m3 corpus; generator frames and classes are still later M3
slices, where reaching one would fail with a NotImplementedError naming the opcode rather than with a
wrong answer, which is the point.
"""

import sys


def fib(n):
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)


def countdown(start):
    out = []
    while start > 0:
        out.append(start)
        start -= 1
    return out


def outer(base):
    def inner(step):
        return base + step

    return inner


total = 0
for i in range(1, 11):
    if i % 2 == 0:
        continue
    total += i

print("hello world")
print("fib(10) =", fib(10))
print("countdown(4) =", countdown(4))
print("closure:", outer(1)(2), outer(10)(20))
print("total =", total)
print("sum(range(100)) =", sum(range(100)))
print("sorted:", sorted([3, 1, 2], reverse=True))
print("dict:", {"a": 1, "b": 2}["b"], list({"a": 1}.items()))
print("fstring:", f"{fib(5)} and {3 * 4.5:.2f}")
print("join:", ", ".join([str(x) for x in range(3)]))
first, *rest = [1, 2, 3, 4]
print("unpack:", first, rest)
print("slice:", [0, 1, 2, 3, 4][1:4], "abc"[1:])
parts = [1, 2, 3]
parts[1:2] = [20, 21]
print("store_slice:", parts)
print("comprehension:", [x * x for x in range(5) if x % 2])
print("lambda:", (lambda a, b=2: a * b)(21))
print("ternary:", "even" if total % 2 else "odd", "|", total)
print("and/or/not:", 0 or "fallback", "and", 1 and 2, "not", not [])
print("argv:", sys.argv)
print("version:", sys.version_info[0], sys.version_info[1])
