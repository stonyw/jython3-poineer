v = ValueError("deep")
for i in range(14):
    v = ExceptionGroup("g%d" % i, [v])
raise v
