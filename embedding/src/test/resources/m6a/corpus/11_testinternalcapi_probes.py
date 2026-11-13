# The internal API probes `test_class` imports, and the `test.support` helpers standing on them.
import sys

# The reference interpreter's own library and this repository's `test` package, exactly as the other
# corpora reach theirs, so both sides read the same `support/__init__.py`.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
ROOT = "/Users/stony/qoder/jython3-poineer/lib-python/3.14"
sys.path.insert(0, STD)
sys.path.insert(0, ROOT)

import _testinternalcapi as internal
import test.support as support

# 1. get_recursion_depth(): the module level is one frame and each Python call adds one. `support`
#    subtracts its own frame before answering, so both spellings agree at every depth.
print("module_depth:", internal.get_recursion_depth(), support.get_recursion_depth())


def descend(n):
    if n == 0:
        return internal.get_recursion_depth(), support.get_recursion_depth()
    return descend(n - 1)


print("nested:", descend(3))
print("available:", support.get_recursion_available())

# 2. has_inline_values(): a slots instance and a variable-sized tuple subclass keep their attributes
#    outside the object in both interpreters. The regular-class answers differ — this runtime always
#    says False because it has no inline values array at all — so those are not printed here.
class Slots:
    __slots__ = ('a',)


class VarSized(tuple):
    pass


print("inline:", internal.has_inline_values(Slots()), internal.has_inline_values(VarSized()))

# 3. get_static_builtin_types() and the support walk over it: the core builtin types are present once
#    each. The full list is each interpreter's own set and is not comparable line by line.
kinds = internal.get_static_builtin_types()
print("static:", type(kinds).__name__,
      type in kinds, object in kinds, int in kinds, BaseException in kinds,
      len(kinds) == len({id(t) for t in kinds}))

walked = list(support.iter_builtin_types())
print("iter:", type(walked).__name__,
      type in walked, object in walked, int in walked,
      len(walked) == len({id(t) for t in walked}))
