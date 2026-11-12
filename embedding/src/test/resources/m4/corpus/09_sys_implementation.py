# `sys.implementation`: the namespace the interpreter describes itself with. Every field a script
# can read is here except the two numbers that differ by build (the micro version and the
# hexversion packed from it), which this script asks about by identity and shape rather than by
# value, so the comparison stays about the namespace and not about which build answered.

import sys

namespace = sys.implementation

print("type", type(namespace).__name__, type(namespace).__module__)
print("keys", list(vars(namespace)))
print("shape", type(namespace).__mro__[1].__name__)
print("identity", namespace is sys.implementation, namespace.version is sys.version_info)
print("hexversion", namespace.hexversion == sys.hexversion,
      type(namespace.hexversion).__name__)
print("strings", type(namespace.name).__name__, namespace.name,
      type(namespace.cache_tag).__name__, namespace.cache_tag,
      type(namespace._multiarch).__name__, namespace._multiarch != "")
print("flag", type(namespace.supports_isolated_interpreters).__name__)

print("no weakref", "__weakref__" in vars(namespace))
print("dict", vars(namespace) is namespace.__dict__, type(vars(namespace)).__name__)
try:
    namespace.__dict__ = {}
except AttributeError as error:
    print("readonly", type(error).__name__, error)

print("repr", repr(namespace).startswith("namespace(name='cpython', cache_tag='cpython-314'"),
      repr(namespace)[-1])

try:
    hash(namespace)
except TypeError as error:
    print("unhashable", type(error).__name__, error)

print("equal", namespace == namespace, namespace == 3, namespace != namespace)

vars(namespace)["extra"] = 1
print("added", namespace.extra, list(vars(namespace))[-1])
del namespace.extra
print("removed", "extra" in vars(namespace))
try:
    del namespace.missing
except AttributeError as error:
    print("missing", type(error).__name__, error)

print("getattr", getattr(namespace, "name"), getattr(namespace, "missing", "default"))
print("dir", sorted(name for name in dir(namespace) if not name.startswith("__")))
