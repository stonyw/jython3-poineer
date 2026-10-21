shared = ValueError("shared")
first = KeyError("first")
first.__cause__ = shared
raise ExceptionGroup("top", [shared, first])
