# The frames a report walks, outermost first, each with the line it stopped on.
class Box:
    def get(self, i):
        return outer(i)

def inner(x):
    return 1 / x

def outer(x):
    return inner(x - 1) + inner(x)

print('asking for two')
Box().get(2)
