# A complaint a __dir__ makes is not swallowed when dir() is the question: only the suggestion chooser
# forgives one, and the frame that raised is read in the report.
class Loud:
    def __dir__(self):
        raise ValueError("asked the wrong thing")


def listed(value):
    return dir(value)


print(listed(Loud()))
