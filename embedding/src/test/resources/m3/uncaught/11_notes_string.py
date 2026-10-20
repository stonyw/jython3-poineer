# A bare string is not a sequence of notes, so the report prints what it is.
err = ValueError('bare note')
err.__notes__ = 'just a string'
raise err
