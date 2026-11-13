"""M5 slice 18: `_thread._local`, the storage a thread owns a dictionary in.

A `_local` value keeps one attribute dictionary per thread rather than one per value, and a
subclass keeps that storage: `asyncio/events.py` writes `class _Local(threading.local)`.

Only one thread runs here, so the per-thread half cannot be shown; the corpus pins the parts
that are visible from a single thread — the shape of the type and its subclasses, the
dictionary each value reaches through `vars()`, when `__init__` runs, and the refusals.
Nothing prints an address or a thread identifier, which differ between runs.
"""

import _thread

local = _thread._local

# The type itself: filed under `_thread`, subclassable, and carrying no members of its own.
print(local.__module__, local.__qualname__, local.__doc__)
print(local is _thread._local, local.__bases__)
print(local.__dictoffset__, local.__weakrefoffset__, local.__basicsize__, local.__itemsize__)
print("__dict__" in local.__dict__, "mro" in dir(local))
print(issubclass(local, object))


class Plain(local):
    pass


# A plain subclass gets a value dictionary, and a weak-reference field it does not lay out.
print(Plain.__dictoffset__, Plain.__weakrefoffset__, Plain.__basicsize__, Plain.__itemsize__)
print("__dict__" in Plain.__dict__, "__weakref__" in Plain.__dict__)
value = Plain()
print(vars(value), value.__dict__ is vars(value))
value.x = 1
value.y = "two"
print(vars(value), value.x, value.y)
del value.x
print(vars(value), hasattr(value, "x"))

# Two values keep two dictionaries: the per-value half of the isolation.
other = Plain()
other.x = "other"
print(vars(value), vars(other))
print(value.__dict__ is other.__dict__)

# `__dict__` is read-only on every `_local`, and the sentence is the same for write and delete.
for action in ("set", "del"):
    try:
        if action == "set":
            value.__dict__ = {}
        else:
            del value.__dict__
    except AttributeError as error:
        print(action, str(error))

# A missing name is the ordinary sentence, because the dictionary is not empty.
for action in ("read", "delete"):
    try:
        if action == "read":
            value.nope
        else:
            del value.nope
    except AttributeError as error:
        print(action, str(error))

# The bare type takes no arguments, and the refusal is `local_new`'s own.
try:
    local(1)
except TypeError as error:
    print("bare", str(error))

# A subclass whose `__init__` reads arguments: they are kept so every thread can replay them,
# and the dictionary the constructor thread reaches already holds what `__init__` wrote.
class Sized(local):
    def __init__(self, size):
        self.size = size


sized = Sized(3)
print(vars(sized), sized.size)
for call in (lambda: Sized(), lambda: Sized(1, 2)):
    try:
        call()
    except TypeError as error:
        print("arity", str(error))

# `__init__` runs once when the value is built, not again on every read.
class Counted(local):
    calls = 0

    def __init__(self):
        Counted.calls += 1
        self.tag = Counted.calls


counted = Counted()
print(Counted.calls, counted.tag)
print(counted.tag, Counted.calls)

# `__slots__` without `__dict__`: no dictionary descriptor, but the thread dictionary still
# takes the write, which is what `_local` does rather than what a plain slots class does.
class Slotted(local):
    __slots__ = ("a",)


print(Slotted.__dictoffset__, Slotted.__weakrefoffset__, Slotted.__basicsize__)
print("__dict__" in Slotted.__dict__, "__weakref__" in Slotted.__dict__)
slotted = Slotted()
slotted.a = 1
slotted.b = 2
print(slotted.a, slotted.b, sorted(vars(slotted)))

# `__slots__` naming `__dict__` is accepted, because `_local` reserves no dictionary slot.
class WithDict(local):
    __slots__ = ("a", "__dict__")


print(WithDict.__dictoffset__, WithDict.__basicsize__)
with_dict = WithDict()
with_dict.a = 1
with_dict.q = 2
print(with_dict.a, sorted(vars(with_dict)))

# A class attribute is read when the dictionary has nothing, and a write shadows it without
# touching the class.
class WithClassAttr(local):
    shared = "class"


attributed = WithClassAttr()
print(attributed.shared)
attributed.shared = "instance"
print(attributed.shared, WithClassAttr.shared)
del attributed.shared
print(attributed.shared)

# A property is a data descriptor, so it wins over the name written into the dictionary.
class WithProperty(local):
    @property
    def prop(self):
        return "property"


propertied = WithProperty()
propertied.__dict__["prop"] = "dictionary"
print(propertied.prop, sorted(vars(propertied)))

# The shape `asyncio/events.py` writes: a nested subclass whose class attribute is the default
# and whose instance attribute lives in the thread dictionary.
class Policy:
    class _Local(local):
        _loop = None

    def __init__(self):
        self._local = self._Local()

    def get(self):
        return self._local._loop

    def set(self, loop):
        self._local._loop = loop


policy = Policy()
print(policy.get(), vars(policy._local))
policy.set("loop")
print(policy.get(), vars(policy._local))
print(policy._Local._loop)

# `isinstance` and `issubclass` walk through the subclass as for any other base.
print(isinstance(value, local), issubclass(Plain, local), issubclass(Slotted, local))
