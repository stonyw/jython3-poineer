import sys

finder = sys.meta_path[-1]
print("finder-class:", finder.__name__, finder.__module__)
print("builtin-seen-by-path:", finder.find_spec("sys"))
print("missing:", finder.find_spec("no_such_module_here"))

try:
    finder.find_spec(3)
except AttributeError as exc:
    print("wrong-type:", type(exc).__name__, exc)

helper_spec = finder.find_spec("helper")
print("found-name:", helper_spec.name, helper_spec.origin == helper_spec.loader.get_filename("helper"))
print("found-flags:", helper_spec.has_location, repr(helper_spec.parent))
print("found-loader:", type(helper_spec.loader).__name__, type(helper_spec.loader).__module__)
print("filename:", helper_spec.loader.get_filename("helper"))
print("cached:", helper_spec.cached)

pkg_spec = finder.find_spec("pkg")
print("package-spec:", pkg_spec.name, pkg_spec.submodule_search_locations, repr(pkg_spec.parent))

try:
    helper_spec.loader.get_filename("other")
except ImportError as exc:
    print("wrong-name:", type(exc).__name__, exc)

import helper
print("helper:", helper.VALUE, helper.__name__, helper.__package__ == "")
print("helper-file:", helper.__file__)
print("helper-cached:", helper.__cached__)
print("helper-spec:", helper.__spec__.name, helper.__spec__.origin)
print("helper-path:", hasattr(helper, "__path__"))
import helper as helper_again
print("helper-twice:", helper_again is helper)

import pkg
print("package:", pkg.VALUE, pkg.__name__, pkg.__package__)
print("package-file:", pkg.__file__)
print("package-path:", pkg.__path__)
print("package-same-path:", pkg.__spec__.submodule_search_locations is pkg.__path__)
print("package-cached:", pkg.__cached__)

try:
    import no_such_module_here_2
except ModuleNotFoundError as exc:
    print("not-found:", type(exc).__name__, exc)
