base = ValueError("base")
inner = KeyError("inner")
inner.__cause__ = base
raise ExceptionGroup("top", [ExceptionGroup("mid", [inner])])
