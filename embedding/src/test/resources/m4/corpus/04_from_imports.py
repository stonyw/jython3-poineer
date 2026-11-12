# from-import: attributes win, submodules are loaded when they do not, and the complaint names
# what was missing and where it was looked for.
import sys

from pkg import VALUE, NAME
print("attributes:", VALUE, NAME)

from pkg import sibling
print("submodule:", sibling.VALUE, sibling is sys.modules["pkg.sibling"])
print("bookkeeping:", sys.modules["pkg"].sibling is sibling)

from pkg import sub
print("subpackage:", sub.VALUE, sub.PARENT_IN_MODULES, sub.SELF_IN_MODULES)

from pkg.sibling import VALUE as SIBLING_VALUE
print("dotted:", SIBLING_VALUE, sys.modules["pkg.sibling"].__name__)

# `import a.b` binds a, leaves b reachable through it, and binds nothing else.
import pkg.sibling
print("import-dotted:", pkg.VALUE, pkg.sibling.VALUE)


def leaf_binding():
    import pkg.sibling
    return "sibling" in locals(), "pkg" in locals()


print("binding:", leaf_binding())

# `import a.b as c` binds the leaf.
import pkg.sibling as sibling_alias
print("alias:", sibling_alias.VALUE)

# __all__ decides what a star-import binds, and loads the submodules it names on the way.
from pkall import *
print("star:", one.VALUE, two.VALUE)

# A relative import inside the package resolves against its __package__.
import pkg.rel
print("relative:", pkg.rel.VALUE, pkg.rel.SIBLING_VALUE)
print("relative-sibling:", pkg.rel.sibling is pkg.sibling)

from helper import *
print("module-star:", VALUE)

try:
    from pkg import nope
except ImportError as exc:
    print("cannot:", type(exc).__name__, exc)
    print("cannot-fields:", exc.name, exc.name_from, exc.path == sys.modules["pkg"].__file__)

try:
    from helper import nope
except ImportError as exc:
    print("cannot-module:", type(exc).__name__, exc)
    print("module-fields:", exc.name, exc.name_from, exc.path == sys.modules["helper"].__file__)

try:
    from pkbad import *
except TypeError as exc:
    print("bad-all:", type(exc).__name__, exc)
