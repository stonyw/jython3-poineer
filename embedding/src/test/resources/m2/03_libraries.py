"""MVP③ — the Java class library, used from Python.

Nothing below is a stub. Each line calls the JDK itself and gets the JDK's own answer back — the
work of the interop layer is that the caller never has to leave Python syntax to do it: a list is
indexed with ``x[i]``, a map with ``x[key]``, a length is ``len()``, ``for`` iterates, ``in``
tests membership, and a Java lambda is written as a Python one.
"""

from java.util import ArrayList, Arrays, HashMap, List, Optional
from java.util.stream import Collectors
from java.time import LocalDate, Duration

# --- collections behave like the Python types they resemble --------------------------------

items = ArrayList()
for word in ["delta", "alpha", "charlie"]:
    items.add(word)
print(len(items), items[0], items[-1])
print(list(items), "alpha" in items)
items[0] = "bravo"
print(list(items))
del items[2]
print(len(items), list(items))

sizes = HashMap()
sizes["a"] = 1
sizes["bb"] = 2
print(len(sizes), sizes["a"], sizes.get("missing"))
print(sorted(sizes), "bb" in sizes)
del sizes["a"]
print(sorted(sizes))

# --- generic factories and the views they return -------------------------------------------

fixed = List.of("3", "1", "2")
print(len(fixed), fixed[1], fixed.subList(0, 2))
print(List.of(*[str(n) for n in range(4)]))

# --- streams take Python callables where Java takes lambdas --------------------------------

shouty = fixed.stream().map(lambda text: text + "!").collect(Collectors.toList())
print(shouty)
print(fixed.stream().sorted().map(int).reduce(0, lambda total, one: total + one))
print(Optional.of("here").map(lambda text: text.upper()).orElse("absent"))
print(Optional.empty().map(lambda text: text.upper()).orElse("absent"))

# --- a library class that is not a collection still arrives as itself ----------------------

day = LocalDate.of(2026, 10, 19)
print(day, day.getDayOfWeek(), day.dayOfMonth, day.isLeapYear())
print(day.plusDays(45), day.compareTo(LocalDate.of(2026, 1, 1)))
print(Duration.ofMinutes(90).toSeconds(), Duration.ofHours(25).toString())

# --- a class from another module, and an array built the way Java builds one -----------------

from java.awt import Point
from java.awt.geom import Point2D
from java.lang import String
from java.lang.reflect import Array

corner = Point(1, 2)
print(corner, corner.x, corner.y)
corner.move(4, 5)
print(corner, corner.distance(Point(1, 1)))
print(Point2D.distance(0, 0, 3, 4), Point2D.distance(Point(), 3, 4))

names = Array.newInstance(String, 2)
names[0] = "delta"
names[1] = "alpha"
print(len(names), names[0], names[-1], Arrays.asList(names))
