"""M5 slice 7: `importlib._bootstrap_external`, as far as `py_compile` reads it.

The module is this runtime's Java-side approximation, not the frozen reference bytecode: it answers
the magic number, the two `.pyc` header writers, the two cache-path functions and the small helpers
around them. What is printed is the whole surface `py_compile`, `importlib.util` and
`SourceFileLoader` use, then a real compile — the cache file is opened and its header checked against
the source's own stat, and the hash-based mode is checked against `importlib.util.source_hash`.

The timestamp itself is never printed: only whether the header agrees with `os.stat`, which both
interpreters can answer the same way.
"""

import sys

# The reference interpreter's own library, exactly as the other corpora do it, so both sides read
# the same `py_compile.py` and `importlib` package.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import importlib
import importlib._bootstrap_external as be
import importlib.machinery
import importlib.util
import os
import py_compile

print("magic", be.MAGIC_NUMBER)
print("magic-token", int.from_bytes(be.MAGIC_NUMBER, "little"))
print("tag", sys.implementation.cache_tag)
print("prefix", sys.pycache_prefix)
print("optimize", sys.flags.optimize)

print("cache", be.cache_from_source("pkg/mod.py"))
print("cache-opt", be.cache_from_source("pkg/mod.py", optimization=1))
print("cache-depth", be.cache_from_source("mod.py"))
print("back", be.source_from_cache("pkg/__pycache__/mod.cpython-314.pyc"))
print("back-opt", be.source_from_cache("pkg/__pycache__/mod.cpython-314.opt-2.pyc"))
print("shared", importlib.util.cache_from_source is be.cache_from_source,
      importlib.util.source_from_cache is be.source_from_cache,
      importlib.util.MAGIC_NUMBER is be.MAGIC_NUMBER)
print("get-cached", be._get_cached("mod.py"), be._get_cached("mod.pyc"),
      be._get_cached("mod.txt"))
print("loader-type", type(importlib.machinery.SourceFileLoader("<t>", "mod.py")).__name__)

with open("pyc_subject.py", "w", encoding="utf-8") as f:
    f.write("VALUE = 42\n")
source = open("pyc_subject.py", "rb").read()

loader = importlib.machinery.SourceFileLoader("<py_compile>", "pyc_subject.py")
print("data", loader.get_data("pyc_subject.py") == source)
stats = loader.path_stats("pyc_subject.py")
print("stats", stats["size"], int(stats["mtime"]) == int(os.stat("pyc_subject.py").st_mtime))

cache = py_compile.compile("pyc_subject.py", doraise=True)
print("cache-path", cache)
data = open(cache, "rb").read()
print("header", data[:4], int.from_bytes(data[4:8], "little"))
mtime = int.from_bytes(data[8:12], "little")
size = int.from_bytes(data[12:16], "little")
st = os.stat("pyc_subject.py")
print("matches", mtime == int(st.st_mtime), size == st.st_size)
print("payload", len(data) - 16 > 0)

hashed = py_compile.compile("pyc_subject.py",
                            cfile="pyc_subject_hash.pyc",
                            invalidation_mode=py_compile.PycInvalidationMode.CHECKED_HASH,
                            doraise=True)
print("hash-path", hashed)
hdata = open(hashed, "rb").read()
print("hash-header", hdata[:4], int.from_bytes(hdata[4:8], "little"))
print("hash-value", hdata[8:16] == importlib.util.source_hash(source))
