"""M5 slice 19: `sys._clear_type_descriptors` and `cell.cell_contents`.

`dataclasses._add_slots` clears the `__dict__` and `__weakref__` descriptors off the original
class before it copies the class dictionary onto a slotted replacement; `inspect` reads a
closure cell through `cell_contents`. Both are reached from `test.support`'s import chain.

Nothing prints an address, and the allocation numbers (`__flags__`, `__basicsize__`) stay out:
they describe each runtime's own layout, not the reference's behaviour.
"""

import sys
import _types


class Plain:
    pass


value = Plain()
value.kept = 1
print("has both:", "__dict__" in Plain.__dict__, "__weakref__" in Plain.__dict__)
print("offsets:", Plain.__dictoffset__, Plain.__weakrefoffset__)
print("vars:", vars(value))

sys._clear_type_descriptors(Plain)
print("cleared:", "__dict__" in Plain.__dict__, "__weakref__" in Plain.__dict__)

# Only the two descriptors left the class dictionary; the rest of the class is untouched.
print("class shape:", Plain.__name__, Plain.__module__, sorted(key for key in Plain.__dict__
      if key.startswith("__") and key.endswith("__")))

# The storage the descriptors named is still there, so attributes keep working.
value.added = 2
print("attributes:", value.kept, value.added)
del value.kept
print("deleted:", hasattr(value, "kept"), hasattr(value, "added"))
print("weakref offset kept:", Plain.__weakrefoffset__ != 0)

# Reading the dictionary now goes the ordinary way and finds nothing.
try:
    value.__dict__
except AttributeError as error:
    print("instance dict:", error)
try:
    vars(value)
except TypeError as error:
    print("vars:", error)

# Clearing again is not an error: the entries are simply not there to remove.
sys._clear_type_descriptors(Plain)
print("again ok")

# A written class may be cleared; a compiled-in type may not.
for kind in (int, str, object, type):
    try:
        sys._clear_type_descriptors(kind)
    except TypeError as error:
        print("immutable:", error)

# The argument must be a type, and the sentence names the argument's class.
for bad in (42, "x", 3.5, None, object(), Plain()):
    try:
        sys._clear_type_descriptors(bad)
    except TypeError as error:
        print("not a type:", error)

try:
    sys._clear_type_descriptors()
except TypeError as error:
    print("none given:", error)
try:
    sys._clear_type_descriptors(Plain, Plain)
except TypeError as error:
    print("two given:", error)
try:
    sys._clear_type_descriptors(type=Plain)
except TypeError as error:
    print("by name:", error)

print("name:", sys._clear_type_descriptors.__name__)
print("text signature:", sys._clear_type_descriptors.__text_signature__)
print("documented:", sys._clear_type_descriptors.__doc__)


# --- the closure box -------------------------------------------------------------------------

cell = _types.CellType
print("type:", cell.__module__, cell.__qualname__, cell.__name__)
print("documented:", cell.__doc__)
print("members:", "cell_contents" in cell.__dict__)
print("descriptor doc:", cell.__dict__["cell_contents"].__doc__)

box = cell()
try:
    box.cell_contents
except ValueError as error:
    print("empty:", error)
box.cell_contents = 7
print("filled:", box.cell_contents)
box.cell_contents = "seven"
print("replaced:", box.cell_contents)
del box.cell_contents
try:
    box.cell_contents
except ValueError as error:
    print("emptied:", error)
del box.cell_contents
print("deleting an empty box is not an error")

seeded = cell("spam")
print("seeded:", seeded.cell_contents)
seeded.cell_contents = None
print("None is a value:", seeded.cell_contents)

try:
    cell(1, 2)
except TypeError as error:
    print("two:", error)
try:
    cell(contents=1)
except TypeError as error:
    print("by name:", error)
try:
    cell(1, x=3)
except TypeError as error:
    print("both:", error)

# The boxes a function's closure holds are the same type.
def outer():
    held = 1
    return lambda: held


print("closure:", len(outer().__closure__), type(outer().__closure__[0]) is cell)
