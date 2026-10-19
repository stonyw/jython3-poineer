"""MVP② — Java's own built-in types, introspected from Python.

Nothing here is a binding, a stub or a generated wrapper: ``java.lang.Integer`` is the JDK's class, read
by reflection when the import asks for it. What a Java class offers Python is what it offers Java — its
static fields, its static methods, its constructors and the members of the instances it returns — and
``isinstance`` answers along Java's hierarchy rather than Python's.

Numbers and strings are the exception, and the mapping is Jython's own: a Java ``Integer`` arrives as a
Python ``int`` and a ``String`` as a ``str``, because that is what a Python reader wants to do arithmetic
with. An object of any other Java class stays itself, methods and all.
"""

from java.lang import Integer, Long, Math, String, System
from java.util import ArrayList, List

# The fields a class declares, read off the class.
print(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.SIZE, Integer.TYPE.getName())
print(Long.MAX_VALUE, Math.PI, Math.max(3, 7), Math.sqrt(2.0))

# Static methods are chosen by argument type, the way Java chooses them.
print(Integer.parseInt("42"), Integer.valueOf("ff", 16), Integer.toHexString(255))

# A boxed number crosses as a Python number, so the unboxing methods it carries have nothing to unbox.
print(Integer.valueOf(7) + 1, type(Integer.valueOf(7)).__name__)

# Any other Java object stays the Java object, with Java's own methods on it.
items = ArrayList()
items.add("one")
print(type(items).__name__, type(items).__module__, items.getClass().getName())
print(isinstance(items, List), isinstance(ArrayList(), List), isinstance(items, ArrayList))

# Java's String arrives as Python's str, which is what makes the two languages' text interchangeable.
text = String.valueOf(42)
print(text + "-two", type(text).__name__, len(text))

print(System.getProperty("java.version") is not None, System.lineSeparator() == "\n")

# Java's own reflection API answers on the class, in Java's own names.
print(Integer.getName(), List.isInterface(), Integer.TYPE.isPrimitive())
print("value" in [field.getName() for field in Integer.getDeclaredFields()])
