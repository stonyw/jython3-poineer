# An attribute asked for on an instance: the report reads the members the class and its bases declare,
# and a private one of them is not offered unless the name being asked about is private too.
class Thing:
    _hidden = 1
    shown = 2


thing = Thing()
thing.hiden
