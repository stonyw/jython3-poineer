# A question about a failed read is only asked while the object can answer it: a __dir__ that complains is
# swallowed, and the report says nothing about the name.
class Quiet:
    value = 1

    def __dir__(self):
        raise ValueError("nothing to say")


Quiet().valu
