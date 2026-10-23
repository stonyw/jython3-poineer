# The object of a failed read is not enough on its own: an AttributeError that says which value was read
# but never named what was missed has nothing for the chooser to work over.
value = 1
carried = AttributeError()
carried.obj = value
raise carried
