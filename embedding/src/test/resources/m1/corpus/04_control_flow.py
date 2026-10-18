def classify(n):
    if n > 0:
        result = "positive"
    elif n < 0:
        result = "negative"
    else:
        result = "zero"
    return result


print([classify(n) for n in (3, -3, 0)])

total = 0
count = 0
while count < 6:
    count += 1
    if count == 3:
        continue
    if count == 6:
        break
    total += count
print("while", count, total)

for outer in range(3):
    for inner in "ab":
        if inner == "b":
            continue
        if outer == 2:
            break
        print("nested", outer, inner)
else:
    print("outer for-else ran")

for value in (10, 20, 30, 40):
    if value == 25:
        print("found")
        break
else:
    print("for-else on no break")

letters = {}
for index, letter in enumerate("banana"):
    letters[letter] = letters.get(letter, 0) + 1
print(sorted(letters.items()))

numbers = [5, 3, 9, 1]
for index in range(len(numbers)):
    for offset in range(len(numbers) - index - 1):
        if numbers[offset] > numbers[offset + 1]:
            numbers[offset], numbers[offset + 1] = numbers[offset + 1], numbers[offset]
print("bubble", numbers)

accumulator = []
step = 1
while True:
    accumulator.append(step)
    step *= 2
    if step > 100:
        break
print(accumulator)

matrix = [[1, 2], [3, 4], [5, 6]]
flat = [cell for row in matrix for cell in row if cell != 4]
print(flat, [row[1] for row in matrix])
print({key: len(key) for key in ("a", "bb", "ccc")})
print(sorted({letter for letter in "mississippi"}))
pairs = [(x, y) for x in range(3) for y in range(3) if x != y]
print(pairs)
print(len([n for n in range(1000) if n % 7 == 0 and n % 3 == 0]))
