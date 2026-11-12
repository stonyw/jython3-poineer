# The package object: __init__.py runs once, __path__ is the directory it was found in, and
# every child it holds learns which package it belongs to.
import sys

# The package's own code runs before any submodule's, and only once.
import orderpkg.leaf
print("order:", orderpkg.ORDER)
print("leaf:", orderpkg.leaf.__name__, orderpkg.leaf is sys.modules["orderpkg.leaf"])
print("leaf-package:", repr(orderpkg.leaf.__package__), orderpkg.leaf.__spec__.parent)

import oncepkg
print("once:", oncepkg.RUNS)
oncepkg.RUNS = 99
import oncepkg
print("cached:", oncepkg.RUNS)
del sys.modules["oncepkg"]
import oncepkg
print("deleted:", oncepkg.RUNS)

# The package itself.
import pkg
print("pkg:", pkg.__name__, repr(pkg.__package__))
print("pkg-path:", type(pkg.__path__).__name__, pkg.__path__)
print("pkg-same-path:", pkg.__path__ is pkg.__spec__.submodule_search_locations)
print("pkg-file:", pkg.__file__.endswith("/pkg/__init__.py"))
print("pkg-cached:", pkg.__cached__.endswith("/pkg/__pycache__/__init__.cpython-314.pyc"))
print("pkg-spec:", pkg.__spec__.name, repr(pkg.__spec__.parent), pkg.__spec__.has_location)
print("pkg-loader:", pkg.__loader__ is pkg.__spec__.loader, type(pkg.__loader__).__name__)

# A module inside it, then a subpackage.
import pkg.sibling
print("module:", repr(pkg.sibling.__package__), hasattr(pkg.sibling, "__path__"))
print("module-spec:", pkg.sibling.__spec__.parent, pkg.sibling.__spec__.has_location)

import pkg.sub
print("subpackage:", pkg.sub.__name__, repr(pkg.sub.__package__))
print("subpackage-path:", pkg.sub.__path__ is pkg.sub.__spec__.submodule_search_locations,
      pkg.sub.__path__[0].endswith("/pkg/sub"))
print("subpackage-spec:", pkg.sub.__spec__.parent, pkg.sub.__file__.endswith("/pkg/sub/__init__.py"))
print("bound:", pkg.sub is sys.modules["pkg.sub"])
print("in-dir:", "sub" in dir(pkg), "sibling" in dir(pkg))

# Two levels: the middle package's __package__ is its own name, the leaf's is the middle's.
import pkg.deep.mod
print("deep:", pkg.deep.__name__, repr(pkg.deep.__package__))
print("deep-path:", pkg.deep.__path__[0].endswith("/pkg/deep"))
print("deep-mod:", pkg.deep.mod.__name__, repr(pkg.deep.mod.__package__), pkg.deep.mod.__spec__.parent)
print("filed:", [name for name in ("pkg", "pkg.deep", "pkg.deep.mod") if name in sys.modules])
print("attributes:", hasattr(pkg, "deep"), hasattr(pkg.deep, "mod"))

# A directory that holds an __init__.py wins over a .py file of the same name.
import pkg.dupe
print("precedence:", pkg.dupe.KIND, pkg.dupe.__spec__.submodule_search_locations is not None)

# A from-import of a package name that is already a submodule attribute.
from pkg import deep
print("from-package:", deep is sys.modules["pkg.deep"])

# Appending to __path__ is how a package says where else to look.
pkg.__path__.append(sys.path[0] + "/extra")
import pkg.extra
print("extra:", pkg.extra.VALUE, repr(pkg.extra.__package__), pkg.extra.__spec__.parent)
print("extra-path:", pkg.__path__[-1].endswith("/extra"), pkg.extra is sys.modules["pkg.extra"])

# A package's own body already knows its name, package, file, path, spec, and that it is filed.
import initpkg
print("init:", initpkg.VALUE)
import initpkg.child

# A package that imports one of its own submodules while it is being loaded.
import selfpkg
print("self:", selfpkg.VALUE, selfpkg.helper.__name__, selfpkg.helper is sys.modules["selfpkg.helper"])

# A failing child leaves its parent imported and itself unfiled.
try:
    import badparent.child
except KeyError as exc:
    print("bad-child:", exc)
print("bad-child-state:", "badparent" in sys.modules, "badparent.child" in sys.modules,
      hasattr(sys.modules["badparent"], "child"))

# A relative import two levels deep climbs to the package above the middle one.
import pkg.sub.inner
print("sub-relative:", pkg.sub.inner.SIBLING_VALUE, pkg.sub.inner is sys.modules["pkg.sub.inner"])

# A package whose body raises leaves nothing behind.
try:
    import badpkg
except ValueError as exc:
    print("bad:", type(exc).__name__, exc)
print("bad-filed:", "badpkg" in sys.modules)

# A submodule that raises is not bound on its package either.
try:
    import pkg.bad
except RuntimeError as exc:
    print("bad-sub:", exc)
print("bad-sub-filed:", "pkg.bad" in sys.modules, hasattr(pkg, "bad"))
