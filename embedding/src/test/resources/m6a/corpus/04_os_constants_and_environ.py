# M6-a slice 2: the constants `os` re-exports from `posix`, the wording of `strerror`, and the
# mapping semantics of `os.environ`/`os.environb`. No real environment is printed — the probe key is
# created and removed here, so neither interpreter depends on what the harness happens to export.

import sys

# The reference interpreter's own library, exactly as the M4 corpora do it, so both sides read the
# same `os.py` and `posixpath.py`.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os

print("sep", repr(os.sep), repr(os.altsep), repr(os.pathsep))
print("linesep", repr(os.linesep))
print("name", os.name, repr(os.curdir), repr(os.pardir), os.devnull)
print("flags", os.F_OK, os.X_OK, os.W_OK, os.R_OK)
print(
    "open_flags",
    os.O_RDONLY,
    os.O_WRONLY,
    os.O_RDWR,
    os.O_CREAT,
    os.O_EXCL,
    os.O_APPEND,
    os.O_TRUNC,
)
print("error", os.error is OSError)
print("strerror", os.strerror(2), "|", os.strerror(13), "|", os.strerror(999))

key = "JY3_M6A_ENV_PROBE"
print("env_absent", key in os.environ, os.getenv(key))
os.environ[key] = "value"
print("env_set", os.environ[key], os.getenv(key), key in os.environ)
print("envb", os.environb[key.encode()])
del os.environ[key]
print("env_del", key in os.environ, os.getenv(key))

print("join", os.path.join("a", "b", "c"), os.path.join("a", "/b"))
print("split", os.path.split("/a/b/c"), os.path.split("/a/b/"))
print("ext", os.path.splitext("a.tar.gz"), os.path.splitext(".bashrc"))
print("names", os.path.basename("/a/b/"), os.path.dirname("/a/b/c"))
print("abs", os.path.isabs("/x"), os.path.isabs("x"))
print("norm", os.path.normpath("a/./b/../c//d"))
print("ex", os.path.exists("."), os.path.exists("no-such-file"), os.path.isdir("."))
