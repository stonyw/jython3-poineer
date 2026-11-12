# Module metadata: what `__main__` says about how it was named, the spec a built-in module carries,
# the SourceFileLoader shape of the two modules this runtime builds, and what importing again rebuilds
# after a program drops an entry from `sys.modules`.

import builtins
import sys

print("== __main__ ==")
main = sys.modules["__main__"]
print(main.__name__, main.__package__, main.__spec__)
print(main.__file__)
print(main.__file__ == __file__, __file__.endswith("/07_module_metadata.py"))
print(main.__cached__)
print(type(main.__loader__).__name__, main.__loader__.name, main.__loader__.path == __file__)
print(main.__builtins__ is builtins)

print("== built-in modules ==")
for module in (sys, builtins):
    spec = module.__spec__
    print(module.__name__, spec.name, type(spec.loader).__name__, spec.origin,
          spec.has_location, spec.cached)
    print(repr(spec.parent), spec.submodule_search_locations,
          module.__loader__ is spec.loader, repr(module.__package__))
    print("__file__" in vars(module), "__cached__" in vars(module))

print("== string.templatelib ==")
import string
import string.templatelib as templatelib

print(string.__name__, repr(string.__package__), string.__file__.rsplit("/", 1)[-1])
print(string.__cached__.rsplit("/", 2)[-2:])
print(type(string.__loader__).__name__, string.__loader__.name,
      string.__loader__ is string.__spec__.loader)
print(len(string.__path__), string.__path__[0].endswith("/string"),
      string.__path__ is string.__spec__.submodule_search_locations)
print(string.templatelib is templatelib, string.__spec__.origin == string.__file__)

print(templatelib.__name__, repr(templatelib.__package__))
print(templatelib.__file__.rsplit("/", 2)[-2:])
print(templatelib.__cached__.rsplit("/", 2)[-2:])
print(type(templatelib.__loader__).__name__, templatelib.__loader__.name,
      templatelib.__loader__ is templatelib.__spec__.loader)
print(templatelib.__spec__.name, repr(templatelib.__spec__.parent),
      templatelib.__spec__.origin == templatelib.__file__,
      templatelib.__spec__.has_location, templatelib.__spec__.submodule_search_locations)
print(sorted(name for name in vars(templatelib) if name.startswith("__")))

print("== annotationlib ==")
import annotationlib

print(annotationlib.__name__, repr(annotationlib.__package__))
print(annotationlib.__file__.rsplit("/", 1)[-1])
print(annotationlib.__cached__.rsplit("/", 2)[-2:])
print(type(annotationlib.__loader__).__name__, annotationlib.__loader__.name,
      annotationlib.__loader__ is annotationlib.__spec__.loader)
print(annotationlib.__spec__.name, repr(annotationlib.__spec__.parent),
      annotationlib.__spec__.origin == annotationlib.__file__,
      annotationlib.__spec__.has_location, annotationlib.__spec__.submodule_search_locations)
print(sorted(name for name in vars(annotationlib) if name.startswith("__")))

print("== importing again ==")
first = templatelib
first_spec = first.__spec__
first_loader = first.__loader__
first_file = first.__file__
first_cached = first.__cached__
first_template = first.Template
first_convert = first.convert
del sys.modules["string.templatelib"]
print("gone", "string.templatelib" in sys.modules)
import string.templatelib

second = string.templatelib
print("new", second is not first, second is sys.modules["string.templatelib"])
print("kept", second.__file__ == first_file, second.__cached__ == first_cached,
      second.__doc__ == first.__doc__, repr(second.__package__))
print("rebuilt", second.__spec__ is not first_spec, second.__loader__ is not first_loader,
      second.__loader__ is second.__spec__.loader)
print("shared", second.Template is first_template, second.convert is first_convert)
print("bound", string.templatelib is second)

first = annotationlib
first_spec = first.__spec__
first_loader = first.__loader__
first_file = first.__file__
first_cached = first.__cached__
del sys.modules["annotationlib"]
import annotationlib

second = annotationlib
print("new", second is not first, second is sys.modules["annotationlib"])
print("kept", second.__file__ == first_file, second.__cached__ == first_cached)
print("rebuilt", second.__spec__ is not first_spec, second.__loader__ is not first_loader)

del sys.modules["__main__"]
try:
    import __main__
except ModuleNotFoundError as error:
    print(type(error).__name__, error.name, error)
