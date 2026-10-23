# Deleting the name takes the sentence with it, because the report reads the member rather than keeping
# the one the interpreter wrote: what is left of the error is the complaint it arrived with.
value = 1
try:
    valu
except NameError as missed:
    del missed.name
    raise
