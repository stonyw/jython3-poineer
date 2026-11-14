import sys

# `tempfile` is standard library and this runtime has no library of its own, so the reference's is
# put on the path before the cursor is asked anything.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os
import pickle
import tempfile

root = tempfile.mkdtemp()
os.chdir(root)
os.mkdir("sub")
with open("file.txt", "w") as handle:
    handle.write("hello")
with open(os.path.join("sub", "inner.txt"), "w") as handle:
    handle.write("inner")

entries = sorted(os.scandir("."), key=lambda entry: entry.name)
print([entry.name for entry in entries])
print([entry.path for entry in entries])
print([(entry.name, entry.is_file(), entry.is_dir(), entry.is_symlink(),
        entry.is_junction()) for entry in entries])
print(os.fspath(entries[1]) == entries[1].path)
print(repr(entries[1]))
print([entry.inode() > 0 for entry in entries])
print([(entry.stat().st_size, entry.stat().st_mode & 0o777) for entry in entries])
print(type(entries[1]).__name__, type(entries[1]).__module__)

cursor = os.scandir(".")
print(type(cursor).__name__, type(cursor).__module__)
print(len(list(cursor)))
print(list(cursor))
cursor.close()
cursor.close()
with os.scandir(".") as cursor:
    first = next(cursor)
print(first.name in ("file.txt", "sub"))
print(next(iter(os.scandir(".")), None) is not None)

binary = os.scandir(b".")
names = sorted(binary, key=lambda entry: entry.name)
print([entry.name for entry in names], [entry.path for entry in names])
print(names[0].name == b"file.txt")
binary.close()

os.mkdir("gone")
gone = [entry for entry in os.scandir(".") if entry.name == "gone"][0]
os.rmdir("gone")
print(gone.inode() > 0, gone.is_dir(), gone.is_dir(follow_symlinks=False),
      gone.is_file(), gone.is_symlink())
try:
    gone.stat()
except OSError as error:
    print(type(error).__name__, error.errno)

for bad in ("", "nope", "file.txt"):
    try:
        os.scandir(bad)
    except OSError as error:
        print(repr(bad), type(error).__name__, error.errno)
for bad in (1.5, {}, []):
    try:
        os.scandir(bad)
    except TypeError as error:
        print("type", str(error))
try:
    os.DirEntry()
except TypeError as error:
    print("DirEntry", str(error))
try:
    pickle.dumps(entries[1])
except TypeError as error:
    print("pickle", str(error))
