# __notes__ after the complaint: each note on its own lines, whatever its type.
def run():
    err = ValueError('with notes')
    err.__notes__ = ['first line', 'two\nlines', 5, 3.5]
    raise err

run()
