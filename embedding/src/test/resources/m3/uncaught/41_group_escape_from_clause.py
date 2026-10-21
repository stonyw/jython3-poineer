def worker():
    try:
        raise ExceptionGroup("outer", [ValueError("v"), KeyError("k")])
    except* ValueError:
        raise RuntimeError("clause")


worker()
