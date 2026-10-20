# A line that appears again and again is folded once the third copy is printed.
def spin(n):
    if n:
        return spin(n - 1)
    raise ValueError('bottom')

spin(7)
