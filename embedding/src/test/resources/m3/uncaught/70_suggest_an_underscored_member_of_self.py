# A method that reads its own private member with the underscore lost: the object the failed read names is
# the one the frame's self is, so the private candidates are no longer hidden from it.
class Holder:
    def __init__(self):
        self._hidden = 1

    def read(self):
        return self.hiden


Holder().read()
