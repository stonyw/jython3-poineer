# The first standard-library module written in Python that this runtime imports whole. `reprlib` is
# two extension modules wide -- `itertools` and `_thread` -- and the script pins both the library's
# answers and the cursors and locks that stand under it. The path is the reference interpreter's own
# (`python3.14 -S` in the harness), so the module source is the same text on both sides.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import _thread
import itertools
import reprlib


def show(label, fn):
    try:
        print(label, "ok", fn())
    except Exception as exc:
        print(label, type(exc).__name__, exc)


print("count", repr(itertools.count()), repr(itertools.count(5)), repr(itertools.count(1, 2)))
print("count walk", list(itertools.islice(itertools.count(1, 2), 4)))
print("repeat", repr(itertools.repeat("x")), repr(itertools.repeat("x", 3)))
print("repeat walk", list(itertools.repeat("x", 3)))
print("cycle", list(itertools.islice(itertools.cycle("ab"), 5)))
print("chain", list(itertools.chain([1, 2], (3,), "ab")))
print("from iterable", list(itertools.chain.from_iterable([[1], [2, 3]])))
print("islice", list(itertools.islice(range(10), 2, 8, 3)), list(itertools.islice(range(5), None)))
show("islice step zero", lambda: itertools.islice([], 0, 5, 0))
show("islice bad stop", lambda: itertools.islice([], "x"))
print("accumulate", list(itertools.accumulate([1, 2, 3])),
      list(itertools.accumulate([1, 2], initial=10)))
print("pairwise", list(itertools.pairwise("abcd")))
print("batched", list(itertools.batched(range(5), 2)),
      list(itertools.batched(range(4), 2, strict=True)))
show("batched strict", lambda: list(itertools.batched(range(5), 2, strict=True)))
show("batched zero", lambda: itertools.batched([], 0))
print("compress", list(itertools.compress("abcd", [1, 0, 1, 1])))
print("dropwhile", list(itertools.dropwhile(lambda x: x < 3, [1, 2, 3, 4, 1])))
print("filterfalse", list(itertools.filterfalse(lambda x: x % 2, range(6))))
print("takewhile", list(itertools.takewhile(lambda x: x < 3, [1, 2, 3, 1])))
print("starmap", list(itertools.starmap(pow, [(2, 3), (3, 2)])))
print("zip longest", list(itertools.zip_longest([1, 2], "ab", [9], fillvalue=0)))
print("types", type(itertools.count()).__name__, type(itertools.count()).__module__,
      type(itertools.repeat(1)).__name__, type(itertools.chain()).__name__)
show("count too many", lambda: itertools.count(1, 2, 3))
show("repeat missing", lambda: itertools.repeat())

print("ident", _thread.get_ident() > 0, _thread.get_native_id() > 0)
print("lock type", _thread.LockType.__name__, _thread.LockType.__module__)
lock = _thread.allocate_lock()
print("lock", repr(lock).startswith("<unlocked _thread.lock object at 0x"), lock.locked())
print("acquire", lock.acquire(), lock.locked())
print("release", lock.release(), lock.locked())
show("release unlocked", lambda: lock.release())
rlock = _thread.RLock()
print("rlock type", type(rlock).__name__, rlock.acquire(), rlock.acquire(), rlock.locked())
rlock.release()
rlock.release()
print("rlock repr", repr(rlock).startswith("<unlocked _thread.RLock object owner=0 count=0 at 0x"))

print("module", reprlib.__name__, type(reprlib.repr).__name__)
print("list", reprlib.repr([1, 2, 3]), reprlib.repr(list(range(10))))
print("str", reprlib.repr("abcdefghijklmnopqrstuvwxyz0123456789"))
print("tuple", reprlib.repr(tuple(range(10))))
print("dict", reprlib.repr({"a": 1, "b": 2}))
print("nested", reprlib.repr([{"a": [1, 2, 3]}, (4, 5)]))
print("levels", reprlib.repr([[[[[[[[1]]]]]]]]))
print("maxlist", reprlib.aRepr.maxlist, reprlib.aRepr.maxstring, reprlib.aRepr.maxlevel)
print("fillvalue", repr(reprlib.aRepr.fillvalue))
print("indent", reprlib.Repr(indent=2).repr([1, [2, 3]]))
print("custom", reprlib.Repr(maxlist=2).repr([1, 2, 3]))
print("shallow", reprlib.Repr(maxlevel=1).repr([[[1]]]))
recursive = []
recursive.append(recursive)
print("recursive", reprlib.repr(recursive))
