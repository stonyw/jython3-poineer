# A method that reads a bare name the instance itself carries: the answer the report gives is the reading
# that would have worked, with the self in front of it.
class Holder:
    def __init__(self):
        self.counter = 1

    def read(self):
        return counter


Holder().read()
