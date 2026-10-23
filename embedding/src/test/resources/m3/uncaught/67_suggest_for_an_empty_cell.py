# An empty cell of an enclosing scope does name what it lost, so the report asks about that name even
# though the sentence it complains in is not the plain "is not defined" one. The name near enough to be
# suggested is kept in the frame that failed rather than taken from a built-in, because the two runtimes
# have different lists of those.
def outer():
    held = 0

    def reads():
        held2 = 1
        return held

    del held
    return reads


reads = outer()
del outer
reads()
