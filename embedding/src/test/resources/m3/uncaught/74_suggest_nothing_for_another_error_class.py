# A name written onto a class that is neither of the two the report asks is left alone: the chooser is
# reached for a NameError or an AttributeError, and an IndexError that carries a name is not one of them.
value = 1
carried = IndexError('slot')
carried.name = 'valu'
raise carried
