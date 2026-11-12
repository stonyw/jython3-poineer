# importlib: importing by name, asking for a spec without loading, and building a module from a
# file by hand. Every side effect is checked through sys.modules, which is the only place a module
# is "really" imported.
import importlib
import importlib.util
import sys

print("doc:", repr(importlib.__doc__))
print("all:", importlib.__all__)
print("package:", repr(importlib.__package__))
print("path:", type(importlib.__path__).__name__, len(importlib.__path__))
print("util:", importlib.util.__name__, repr(importlib.util.__package__),
      "importlib.util" in sys.modules)
print("import-builtin:", importlib.__import__ is __import__,
      importlib.__import__("pkg.sibling").__name__)
print("invalidate:", importlib.invalidate_caches())

import pkg
import pkg.sibling
import pkg.sub

# import_module: an absolute name is the module, a dotted name is the leaf, and a second call is
# the same object.
absolute = importlib.import_module("orderpkg")
print("absolute:", absolute.__name__, absolute is sys.modules["orderpkg"])
dotted = importlib.import_module("pkg.sibling")
print("dotted:", dotted.__name__, dotted is sys.modules["pkg.sibling"])
print("again:", importlib.import_module("pkg.sibling") is dotted)

# A leading dot is resolved against the package the caller names.
print("dot:", importlib.import_module(".sibling", "pkg") is dotted)
print("self:", importlib.import_module(".", "pkg").__name__)
print("up:", importlib.import_module("..sibling", "pkg.sub").__name__)

# An empty name, a name that is not a string, a relative name with no package, and one that
# climbs past the top level.
for label, call in [
    ("empty", lambda: importlib.import_module("")),
    ("int", lambda: importlib.import_module(3)),
    ("rel-none", lambda: importlib.import_module(".sibling")),
    ("rel-beyond", lambda: importlib.import_module("..sibling", "pkg")),
    ("missing", lambda: importlib.import_module("nope")),
    ("missing-rel", lambda: importlib.import_module("nope", "pkg")),
]:
    try:
        call()
    except Exception as exc:
        print(label, type(exc).__name__, exc, repr(getattr(exc, "name", None)))

# find_spec: a package answers its directory as search locations, a module answers None, and a
# name already imported answers the spec it was imported with.
pkg_spec = importlib.util.find_spec("pkg")
print("find-package:", pkg_spec.name, pkg_spec.submodule_search_locations,
      pkg_spec.has_location, pkg_spec.origin.endswith("/pkg/__init__.py"))
print("find-package-parent:", pkg_spec.parent)
sibling_spec = importlib.util.find_spec("pkg.sibling")
print("find-module:", sibling_spec.name, sibling_spec.submodule_search_locations,
      sibling_spec.origin.endswith("/pkg/sibling.py"), repr(sibling_spec.parent))
print("find-cached:", importlib.util.find_spec("pkg.sibling") is pkg.sibling.__spec__)
print("find-relative:", importlib.util.find_spec(".sibling", "pkg") is pkg.sibling.__spec__)
print("find-missing:", importlib.util.find_spec("nope"))
print("find-stranger:", importlib.util.find_spec("pkg.nope"))

for label, call in [
    ("find-int", lambda: importlib.util.find_spec(3)),
    ("find-rel-none", lambda: importlib.util.find_spec(".sibling")),
    ("find-deep", lambda: importlib.util.find_spec("pkg.sibling.child")),
]:
    try:
        call()
    except Exception as exc:
        print(label, type(exc).__name__, exc, repr(getattr(exc, "name", None)))

# spec_from_file_location + module_from_spec: the spec describes the file, and the module is
# built from it without being filed — exec_module runs the body but does not add it to sys.modules.
loose_path = sys.path[0] + "/loose.py"
loose_spec = importlib.util.spec_from_file_location("loose", loose_path)
print("from-file:", loose_spec.name, loose_spec.origin, loose_spec.has_location)
print("from-file-loader:", type(loose_spec.loader).__name__,
      loose_spec.submodule_search_locations)
print("from-file-cached:", loose_spec.cached.endswith("/__pycache__/loose.cpython-314.pyc"))

loose = importlib.util.module_from_spec(loose_spec)
print("from-spec:", loose.__name__, loose.__file__.endswith("/loose.py"),
      loose.__spec__ is loose_spec, loose.__loader__ is loose_spec.loader)
print("from-spec-package:", repr(loose.__package__), "loose" in sys.modules,
      hasattr(loose, "VALUE"))
loose_spec.loader.exec_module(loose)
print("from-exec:", loose.VALUE, "loose" in sys.modules)
print("from-dict:", sorted(loose.__dict__))

# reload: the body runs again in the same namespace, and the module is the same object.
import counter
print("reload-before:", counter.RUNS, "counter" in sys.modules)
reloaded = importlib.reload(counter)
print("reload-after:", counter.RUNS, reloaded is counter,
      sys.modules["counter"] is counter)

unfiled = importlib.util.module_from_spec(loose_spec)
for label, call in [
    ("reload-int", lambda: importlib.reload(3)),
    ("reload-unfiled", lambda: importlib.reload(unfiled)),
]:
    try:
        call()
    except Exception as exc:
        print(label, type(exc).__name__, exc, repr(getattr(exc, "name", None)))
