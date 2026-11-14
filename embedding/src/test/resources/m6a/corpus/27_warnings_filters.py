"""The filter chain, the registry, and the catch_warnings stack, as a program sees them."""

import sys

# The reference interpreter's own library, exactly as the other M6-a corpora do it: `warnings.py` is
# standard library, and this runtime has no library of its own.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import warnings


def show(message, category, filename, lineno, file=None, line=None):
    print("SHOW", category.__name__, message, lineno)


# The default filter pair: DeprecationWarning is "default" in __main__ and ignored everywhere else.
warnings.warn("dep once", DeprecationWarning)
warnings.warn("dep once", DeprecationWarning)

# simplefilter(always) shows the same warning every time; from here on the module's filters change.
warnings.simplefilter("always")
warnings.warn("always one")
warnings.warn("always one")

# A string module filter matches the module name exactly.
with warnings.catch_warnings():
    warnings.simplefilter("ignore")
    warnings.filterwarnings("always", category=UserWarning, module="__main__")
    warnings.warn("module match")
    warnings.warn("module match")

# A message filter goes through re.match, so it anchors at the start of the text.
with warnings.catch_warnings():
    warnings.simplefilter("always")
    warnings.filterwarnings("error", message="^error:")
    warnings.warn("notice")
    try:
        warnings.warn("error: boom")
    except UserWarning as caught:
        print("CAUGHT", caught)

# "once" reports a message and category once, whatever callsite raised it.
with warnings.catch_warnings():
    warnings.simplefilter("once")
    warnings.warn("once", UserWarning)
    warnings.warn("once", UserWarning)

# "ignore" says nothing at all.
with warnings.catch_warnings():
    warnings.simplefilter("ignore")
    warnings.warn("quiet")

# record=True keeps WarningMessage objects instead of writing them.
with warnings.catch_warnings(record=True) as caught:
    warnings.simplefilter("always")
    warnings.warn("recorded", FutureWarning)
print("RECORDS", len(caught), caught[0].category.__name__, str(caught[0].message),
      caught[0].lineno)

# showwarning is what the display path calls; formatwarning is what it writes with.
with warnings.catch_warnings():
    warnings.simplefilter("always")
    warnings.showwarning = show
    warnings.warn("custom")
print("FORMAT", warnings.formatwarning("fmt", UserWarning, "file.py", 7).splitlines()[0])

# A registry remembers what this filter set already reported, and is emptied when the set changes.
registry = {}
warnings.warn_explicit("first", UserWarning, "reg.py", 3, module="regmod", registry=registry)
warnings.warn_explicit("first", UserWarning, "reg.py", 3, module="regmod", registry=registry)
print("REG1", "version" in registry, len([key for key in registry if key != "version"]))
warnings.simplefilter("always")
warnings.warn_explicit("first", UserWarning, "reg.py", 3, module="regmod", registry=registry)
print("REG2", "version" in registry, len([key for key in registry if key != "version"]))

print("FILTERS", len(warnings.filters))
