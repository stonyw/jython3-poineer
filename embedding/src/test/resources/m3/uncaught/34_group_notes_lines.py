e = ValueError("two\nlines")
e.__notes__ = ["ends with newline\n", ""]
raise ExceptionGroup("top", [e])
