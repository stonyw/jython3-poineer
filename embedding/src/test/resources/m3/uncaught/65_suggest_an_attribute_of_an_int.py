# getattr() over a built-in type's members: the failed read names the value it was given, and the list it
# is asked about is that type's own.
getattr(1, "bit_lenght")
