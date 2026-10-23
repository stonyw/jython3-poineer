# A name written onto an exception by hand drives the chooser the way the one the interpreter wrote
# does, and the sentence is hung on the text of the exception rather than beside it — so a complaint
# that had nothing to say gets the suggestion behind its own colon, period and all.
value = 1
carried = NameError()
carried.name = 'valu'
raise carried
