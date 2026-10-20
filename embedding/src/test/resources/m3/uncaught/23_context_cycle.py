# Two exceptions that name each other as context are each reported once.
a = ValueError('a')
b = ValueError('b')
a.__context__ = b
b.__context__ = a
raise a
