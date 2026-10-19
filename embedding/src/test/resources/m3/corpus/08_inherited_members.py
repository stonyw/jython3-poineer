"""Members reached through a type, before any value of that type has been made, and the ones a
subclass inherits."""


def describe(label, thunk):
    try:
        return label + " -> " + str(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


print(float.is_integer, int.bit_length, bool.bit_length, str.upper)
print(True.bit_length(), False.bit_length(), int.bit_length(True))
print(int.as_integer_ratio(True), True.as_integer_ratio(), (255).bit_length())
print((2.0).is_integer(), float.is_integer(3.5), float.conjugate(2.0))
print(str.upper("ab"), "ab".upper(), str.upper.__name__, str.upper.__qualname__)
print(list.append, dict.get, tuple.count, set.add, bytes.decode)
print(describe("a float method on a bool", lambda: float.is_integer(True)))
print(describe("no such method", lambda: int.nonsense))
print(describe("unbound needs its receiver", lambda: int.bit_length()))
