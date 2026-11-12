"""The Mersenne Twister behind `_random`, drawn the same way on both interpreters.

Every number here comes from a seed this script chose, so the two runs compare the
generator itself rather than the operating system's entropy: the first draw from seed 0,
the bit widths the module rounds to whole words, the state tuple and its index, and the
seeding rules that fold bools, floats and negatives onto the integer they name.
"""

import _random

r = _random.Random(0)
print(r.random())

r = _random.Random(0)
print(r.getrandbits(64))
print(r.getrandbits(5))
print(r.getrandbits(96))

r = _random.Random(12345)
print([r.random() for _ in range(3)])

r = _random.Random(0)
state = r.getstate()
print(len(state), state[624], state[0], state[623])
r.setstate(state)
print(r.random())
print(r.getstate() == state)

print(_random.Random(True).getstate() == _random.Random(1).getstate())
print(_random.Random(0.0).getstate() == _random.Random(0).getstate())
print(_random.Random(-7).getstate() == _random.Random(7).getstate())

try:
    _random.Random(1, 2)
except TypeError as error:
    print(type(error).__name__, error)

try:
    _random.Random(seed=1)
except TypeError as error:
    print(type(error).__name__, error)

try:
    _random.Random(0).getrandbits(-1)
except ValueError as error:
    print(type(error).__name__, error)

try:
    _random.Random(0).setstate((0,) * 624)
except ValueError as error:
    print(type(error).__name__, error)
