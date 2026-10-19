"""MVP④ — the Python half of an embedding: the names a Java caller reaches for.

The Java side is ``InteroperabilityDemoTest``, which runs this file through ``PythonInterpreter`` and
then calls what it defines: ``build(4)`` for a ``java.util.List``, ``shout`` for a ``String``, the two
``on…`` functions as one Java interface, and ``boom`` for a Python exception crossing back as something
a ``catch`` can act on.

Nothing here knows it is embedded. That is the point of the fourth capability: the same module a
``jy3`` process would run is what a Java program loads, and the crossing is the interpreter's business,
not the script's.
"""

log = []


def build(n):
    return [i * i for i in range(n)]


def shout(word, times):
    return " ".join([word] * times)


def counts():
    return {"one": 1, "two": 2}


def onOpen(title):
    log.append(title)


def onClose(code, reason):
    return code * 100 + len(reason)


def boom():
    raise ValueError("bad value")
