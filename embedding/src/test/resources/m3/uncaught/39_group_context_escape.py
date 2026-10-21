def leaf(name):
    try:
        raise ValueError("first " + name)
    except ValueError:
        raise KeyError("second " + name)


raise ExceptionGroup("top", [leaf("a"), leaf("b")])
