# An exception that cannot say its own message is reported as one that failed.
class Loud(Exception):
    def __str__(self):
        raise InternalError('cannot say')

raise Loud('x')
