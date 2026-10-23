# A name that is not text is refused before any candidate is looked at: the chooser wants a string, and
# a value that could not be compared to a name is not one.
value = 1
carried = NameError('nothing')
carried.name = 5
raise carried
