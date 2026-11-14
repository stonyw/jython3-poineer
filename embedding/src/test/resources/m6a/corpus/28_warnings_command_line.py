"""What -W does to a program, exercised once per option combination by the launcher test."""

import sys

# The reference interpreter's own library, exactly as the other M6-a corpora do it.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import warnings

print("WARNOPTIONS", sys.warnoptions)
warnings.warn("top", UserWarning)
warnings.warn("dep", DeprecationWarning)
warnings.warn_explicit("explicit", UserWarning, "cli.py", 1, module="cli")
print("DONE")
