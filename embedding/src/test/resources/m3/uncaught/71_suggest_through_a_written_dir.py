# The candidates a question about a failed read is built from are the ones the object's own __dir__ hands
# out, which a class written in Python may answer with whatever it likes.
class Map:
    def __dir__(self):
        return ["nearby"]


Map().nearby2
