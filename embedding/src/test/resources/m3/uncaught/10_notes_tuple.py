# A tuple of notes reads the same way a list does.
err = ValueError('tuple notes')
err.__notes__ = ('first', 'second')
raise err
