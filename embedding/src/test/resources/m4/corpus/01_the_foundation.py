import sys
import builtins

print("path0:", sys.path[0])
print("path-is-a-list:", type(sys.path).__name__)
print("meta-is-a-list:", type(sys.meta_path).__name__)

builtin_loader = sys.meta_path[0]
print("builtins-loader:", builtin_loader is builtins.__loader__)
print("builtin-class:", builtin_loader.__name__, builtin_loader.__module__)

sys_spec = builtin_loader.find_spec("sys")
print("spec:", sys_spec)
print("spec-type:", type(sys_spec).__name__, type(sys_spec).__module__)
print("spec-attrs:", sys_spec.name, sys_spec.origin, sys_spec.submodule_search_locations)
print("spec-flags:", sys_spec.has_location, repr(sys_spec.parent), sys_spec.cached)
print("create:", builtin_loader.create_module(sys_spec) is sys)
print("load:", builtin_loader.load_module("sys") is sys)
print("exec:", builtin_loader.exec_module(sys))
print("absent:", builtin_loader.find_spec("nope"))

try:
    builtin_loader.load_module("nope")
except ImportError as exc:
    print("refused:", type(exc).__name__, exc)

try:
    builtin_loader.find_spec(3)
except TypeError as exc:
    print("wrong-type:", type(exc).__name__, exc)

try:
    builtin_loader.find_spec(None)
except TypeError as exc:
    print("none-type:", type(exc).__name__, exc)

spec_type = type(sys_spec)
print("made:", repr(spec_type("made", None)))
made_package = spec_type("made.package", None, is_package=True)
print("made-package:", made_package.submodule_search_locations, repr(made_package.parent))

try:
    spec_type("missing_loader")
except TypeError as exc:
    print("short:", type(exc).__name__, exc)

try:
    spec_type("made", None, origin="o", loader_state=None, extra=1)
except TypeError as exc:
    print("extra:", type(exc).__name__, exc)
