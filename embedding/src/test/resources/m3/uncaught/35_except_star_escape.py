def worker():
    raise ExceptionGroup("outer", [ValueError("v"), KeyError("k"), TypeError("t")])


try:
    worker()
except* ValueError:
    raise IndexError("from clause")
