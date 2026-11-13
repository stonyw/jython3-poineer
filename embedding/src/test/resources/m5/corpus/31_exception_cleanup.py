# Exception state is released when a handler is left: the name it bound is
# unbound again, sys.exception() goes back to the enclosing answer, and a
# generator closed while parked in a handler stops answering with that
# handler's exception.  gc.collect() asks the runtime's heap for a collection
# without claiming a count of its own.

import gc
import sys


class Wrapped(Exception):
    pass


def raise_wrapped(message):
    raise Wrapped(message)


try:
    raise_wrapped("caught")
except Wrapped as error:
    print("during:", error.args, sys.exception() is error)

print("after:", sys.exception())


def bound_then_returned():
    try:
        raise_wrapped("local")
    except Wrapped as error:
        return error.args


print("local:", bound_then_returned())


def name_after_handler():
    try:
        raise_wrapped("gone")
    except Wrapped as gone:
        pass
    return gone


try:
    name_after_handler()
except UnboundLocalError as error:
    print("unbound:", type(error).__name__)


def parked():
    try:
        raise_wrapped("parked")
    except Wrapped:
        yield sys.exception().args


gen = parked()
print("generator:", next(gen))
gen.close()
print("closed:", sys.exception())

print("collect:", gc.collect())
