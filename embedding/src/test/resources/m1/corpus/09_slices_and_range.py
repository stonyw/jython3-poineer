letters = ["a", "b", "c", "d", "e", "f"]
numbers = list(range(10))
print(numbers, letters[1:4], letters[-3:], letters[:2], letters[2:], letters[5:1], letters[7:9])
print(letters[::2], letters[::-1], letters[::-2], letters[1::-1], numbers[2:9:3], numbers[::3])
print(letters[-1:0], letters[0:-1], numbers[8:2:-1], numbers[100:], numbers[:100], numbers[-100:])
letters[1] = "B"
letters[1:3] = ["X"]
print(letters)
letters[:2] = []
print(letters)
del letters[-1]
print(letters)
text = "abcdefgh"
print(text[2:5], text[5:2], text[::-1], text[1:7:2], text[-3:], len(text[100:]), repr(text[100:]))
data = (0, 1, 2, 3, 4, 5)
print(data[1:4], data[::-1], data[-2:], data[:0], list(data[1:3]))
print(data.index(3), data.count(2), len(data), max(data), min(data))

for value in range(0):
    print("never")
else:
    print("empty range loop")
print(list(range(0)), list(range(1)), list(range(-1)), list(range(3)), list(range(0, 5)), list(range(5, 0)))
print(list(range(0, 10, 3)), list(range(10, 0, -3)), list(range(1, 10, 3)), list(range(-5, 5, 2)))
print(list(range(10, 0, 1)), list(range(0, 10, -1)), list(range(5, 5, 2)), list(range(3, 3, -3)))
print(list(range(2**70, 2**70 + 3)), list(range(0, 2**70, 2**69)), len(range(0, 2**70, 2**69)))
print(len(range(0)), len(range(1)), len(range(-3)), len(range(0, 10, 3)), len(range(10, 0, -2)))
print(range(5)[0], range(5)[-1], range(1, 20, 3)[-1], range(5) == range(5), range(5) == range(4), repr(range(3)))
print(4 in range(10), 10 in range(10), -1 in range(10), 3 in range(0, 9, 3), 4 in range(0, 9, 3))
print(len(list(range(3))) * len(range(3, 0, -1)), sum(range(101)), max(range(100)), min(range(100, 0, -3)))
print(list(range(3, 3)), list(range(-1, -4, -1)))
print([i for i in range(5) if i % 2], {i: i * i for i in range(3)}, tuple([i for i in range(3)]))
