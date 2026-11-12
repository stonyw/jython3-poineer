# M6-a slice 2: the posix module's filesystem surface as `os` presents it. Every name made here is
# removed again, so the directory the script runs in is left as it was found.

import sys

# The reference interpreter's own library, exactly as the M4 corpora do it, so both sides read the
# same `os.py`, `stat.py` and `posixpath.py`.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os
import stat

print("cwd_agrees", os.getcwd() == os.path.abspath("."))

os.mkdir("m6a_dir")
print("mkdir", os.path.isdir("m6a_dir"), stat.S_ISDIR(os.stat("m6a_dir").st_mode))

with open("m6a_dir/data.txt", "w") as stream:
    stream.write("hello")

print("listdir", sorted(os.listdir("m6a_dir")))

info = os.stat("m6a_dir/data.txt")
print("stat", stat.S_ISREG(info.st_mode), info.st_size, info.st_nlink >= 1)
print("lstat", stat.S_ISREG(os.lstat("m6a_dir/data.txt").st_mode))
print(
    "access",
    os.access("m6a_dir/data.txt", os.R_OK),
    os.access("m6a_dir/data.txt", os.X_OK),
    os.access("m6a_dir/absent", os.F_OK),
)

try:
    os.rmdir("m6a_dir")
except OSError as error:
    print("rmdir_busy", type(error).__name__, error.errno)

os.rename("m6a_dir/data.txt", "m6a_dir/moved.txt")
print("rename", sorted(os.listdir("m6a_dir")))

os.remove("m6a_dir/moved.txt")
print("remove", sorted(os.listdir("m6a_dir")))

os.chdir("m6a_dir")
print("chdir", os.path.basename(os.getcwd()))
os.chdir("..")
print("chdir_back", os.path.basename(os.getcwd()) != "m6a_dir")

try:
    os.remove("m6a_dir")
except OSError as error:
    print("remove_dir", type(error).__name__, error.errno)

os.rmdir("m6a_dir")
print("rmdir", os.path.exists("m6a_dir"), os.path.isdir("m6a_dir"))

try:
    os.stat("m6a_dir")
except OSError as error:
    print("stat_absent", type(error).__name__, error.errno, error.strerror)
