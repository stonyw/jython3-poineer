# A class body that reads a name the enclosing function has not filled in yet: the cell is asked and the
# report carries the body's own frame, named for the class it was building.
def build():
    class Body:
        read = filled_later

    filled_later = "an answer"
    return Body


print('built', build())
