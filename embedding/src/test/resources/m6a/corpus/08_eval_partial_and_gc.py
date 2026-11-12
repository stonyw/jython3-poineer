"""What M6-a slice 4 added: the code builtins, functools.partial, the gc module, math.gamma.

`unittest` is the goal of this slice but not yet importable -- it reaches `from _ast import *` -- so
this file pins the names that had to land while that gap was being measured. Nothing printed here is
an address, an object id, a byte of urandom, or a gc count, because the two interpreters cannot agree
on those; gamma and lgamma are rounded because this runtime uses a Lanczos sum where the reference
calls libm.
"""

import functools
import gc
import itertools
import math
import sys

# The reference interpreter's own library, exactly as the other M6-a corpora do it, so both sides
# read the same `os.py` -- this runtime has no `os` of its own.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os

# --- eval / exec / compile, the three names the import machinery reads -----------------------------

print(eval("1 + 2"))
print(eval("a * b", {"a": 3, "b": 4}))
namespace = {}
exec("answer = 6 * 7", namespace)
print(namespace["answer"])
code = compile("x + 1", "<probe>", "exec")
print(code.co_name, code.co_filename)

# --- functools.partial ---------------------------------------------------------------------------

bound = functools.partial(pow, 2, mod=1000)
print(bound(10), bound(10, mod=7))
print(bound.func.__name__, bound.args, sorted(bound.keywords.items()))
print(repr(functools.partial(sum, [1, 2])))
try:
    functools.partial()
except TypeError as error:
    print("TypeError:", error)

# --- the gc module -------------------------------------------------------------------------------

print(gc.get_threshold(), gc.get_debug(), gc.isenabled(), gc.DEBUG_LEAK)
print(gc.freeze(), gc.unfreeze(), gc.get_freeze_count())
print(len(gc.get_stats()), sorted(gc.get_stats()[0]))
print(gc.collect(2) >= 0, type(gc.garbage).__name__, gc.garbage)
print(len(gc.callbacks))

# --- gamma and lgamma, rounded because they are not libm here --------------------------------------

print(round(math.gamma(0.5), 12), round(math.lgamma(2.5), 12))
for bad in (0.0, -3.0):
    try:
        math.gamma(bad)
    except ValueError as error:
        print("ValueError:", error)

# --- itertools.permutations and product, which tokenize.py reads ----------------------------------

print(list(itertools.permutations([1, 2, 3], 2)))
print(list(itertools.product("ab", repeat=2)))

# --- os.urandom, without printing a byte of it ----------------------------------------------------

print(len(os.urandom(16)), type(os.urandom(0)).__name__)
try:
    os.urandom(-1)
except ValueError as error:
    print("ValueError:", error)
