import sys

sys.path.insert(0, "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14")
sys.path[:0] = ["nsb", "nsa"]

import ns_pkg

path = ns_pkg.__path__
print("path type", type(path).__module__ + "." + type(path).__name__)
print("path list", list(path))
print("path repr", repr(path))
print("path len", len(path), path[0] == list(path)[0], path[1].endswith("/nsa/ns_pkg"))
print("path membership", path[0] in path, "nowhere" in path)
print("path vars", sorted(vars(path)))

print("spec origin", ns_pkg.__spec__.origin)
print("spec shared", ns_pkg.__spec__.submodule_search_locations is path)
print("file", hasattr(ns_pkg, "__file__"), ns_pkg.__file__)
print("cached", hasattr(ns_pkg, "__cached__"))
print("package", ns_pkg.__package__)
print("module repr", repr(ns_pkg))
print("loader type", type(ns_pkg.__loader__).__name__)
print("loader path", ns_pkg.__loader__._path is path)
print("loader vars", sorted(vars(ns_pkg.__loader__)))

import ns_pkg.one
import ns_pkg.two

print("portions", ns_pkg.one.VALUE, ns_pkg.two.VALUE)
print("submodules", ns_pkg.one.__name__, ns_pkg.two.__name__)

import importlib.machinery

m = importlib.machinery
print("machinery all", m.__all__)
print("machinery suffixes", m.SOURCE_SUFFIXES, m.BYTECODE_SUFFIXES, m.EXTENSION_SUFFIXES)
print("machinery all_suffixes", m.all_suffixes())
for name in ("ModuleSpec", "BuiltinImporter", "FrozenImporter", "PathFinder", "FileFinder",
             "SourceFileLoader", "SourcelessFileLoader", "ExtensionFileLoader",
             "AppleFrameworkLoader", "NamespaceLoader", "WindowsRegistryFinder"):
    maker = getattr(m, name)
    print("machinery name", name, maker.__module__, maker.__name__)

print("hooks", len(sys.path_hooks))
for hook in sys.path_hooks:
    print("hook", hook.__name__)

hook = m.FileFinder.path_hook((m.SourceFileLoader, m.SOURCE_SUFFIXES))
print("custom hook", hook.__name__)
finder = hook("nsa")
print("finder", type(finder).__name__, finder.path.endswith("/nsa"))
print("finder repr", repr(finder))
spec = finder.find_spec("ns_pkg")
print("finder spec", spec.name, spec.origin, type(spec.submodule_search_locations).__name__,
      spec.submodule_search_locations[-1].endswith("/nsa/ns_pkg"))
print("finder miss", finder.find_spec("absent"))
try:
    hook("loose.txt")
except ImportError as failure:
    print("finder refuse", failure)

cached = sys.path_importer_cache["nsa"]
print("cache", type(cached).__name__, cached.path.endswith("/nsa"))

import zipimport

print("zip all", zipimport.__all__)
for value in (None, 5):
    try:
        zipimport.zipimporter(value)
    except TypeError as failure:
        print("zip type", failure)
for archive in ("no_such_zip", "nsa", "loose.txt"):
    try:
        zipimport.zipimporter(archive)
    except zipimport.ZipImportError as failure:
        print("zip refuse", failure)
error = zipimport.ZipImportError
print("zip error", error.__name__, error.__module__, error.__mro__[1].__name__)
