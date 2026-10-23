# A method of a nested class asked after the enclosing function let go of the name it closes over: the
# cell is empty at the read, and it is the frame the method runs in that says so.
def build():
    carried = "a value"

    class Body:
        def reads():
            return carried

    del carried
    return Body


driven = build()
print('asking', driven.reads())
