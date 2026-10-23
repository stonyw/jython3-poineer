import sys

# A name that is there but holds nothing is still read and still called, and the call is what fails.
sys.excepthook = None
raise ValueError("reported by the interpreter")
