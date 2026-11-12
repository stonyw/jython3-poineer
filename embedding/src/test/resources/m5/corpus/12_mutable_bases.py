"""`type.__bases__` assignment: the write, the order it recomputes, and every refusal.

The refusals are checked as they read, because the texts are CPython's: the tuple
shape, a non-class item, an inheritance cycle, an immutable type, an unacceptable
base, a deallocator change and a lay-out conflict.  The order is checked on the
class and on its subclasses, and the last block checks that a failing custom mro
leaves the hierarchy as it was.  The one message this file cannot reach is
"object layout differs": two classes differ in instance layout only when one of
them declares __slots__, which is the next slice.
"""


class A:
    def who(self):
        return "A"


class B:
    def who(self):
        return "B"


class C(A):
    pass


class D(C):
    pass


c = C()
d = D()
print("before", C.__bases__, C.__base__, C.__mro__)
print("before", D.__mro__)
C.__bases__ = (B,)
print("after", C.__bases__, C.__base__, type(C.__bases__).__name__)
print("after", C.__mro__)
print("after", D.__mro__)
print("call", c.who(), d.who())
print("subs", B.__subclasses__(), A.__subclasses__())
C.__bases__ = (A,)
print("back", C.__mro__, c.who(), d.who(), A.__subclasses__())


class P:
    def tag(self):
        return "P"


class Q:
    def extra(self):
        return "Q"


class M(P):
    pass


class N(M):
    pass


m = M()
print("one", M.__base__, M.__bases__)
M.__bases__ = (P, Q)
print("two", M.__mro__)
print("two", N.__mro__, M.__base__, isinstance(m, Q), m.tag(), m.extra())
M.__bases__ = (Q, P)
print("swap", M.__mro__)
M.__bases__ = (P, Q)
print("same", M.__mro__, M.__bases__)


class Plain:
    pass


class Other:
    pass


class Kid(Plain):
    pass


class Grand(Kid):
    pass


def refusal(label, action):
    try:
        action()
    except Exception as exc:
        print(label, type(exc).__name__, str(exc))
    else:
        print(label, "accepted")


refusal("empty", lambda: setattr(Kid, "__bases__", ()))
refusal("none", lambda: setattr(Kid, "__bases__", None))
refusal("list", lambda: setattr(Kid, "__bases__", [Plain]))
refusal("item", lambda: setattr(Kid, "__bases__", (Plain, 42)))
refusal("cycle", lambda: setattr(Plain, "__bases__", (Kid,)))
refusal("self", lambda: setattr(Kid, "__bases__", (Kid,)))
refusal("delete", lambda: delattr(Kid, "__bases__"))
refusal("builtin", lambda: setattr(int, "__bases__", (object,)))
refusal("nonebase", lambda: setattr(Kid, "__bases__", (type(None),)))
refusal("boolbase", lambda: setattr(Kid, "__bases__", (bool,)))
refusal("dealloc", lambda: setattr(Kid, "__bases__", (int,)))
refusal("conflict", lambda: setattr(Kid, "__bases__", (int, str)))
print("kept", Kid.__bases__, Kid.__mro__, Grand.__mro__)
refusal("replace", lambda: setattr(Kid, "__bases__", (Other,)))
print("moved", Kid.__bases__, Kid.__mro__, Grand.__mro__)


class WorkOnce(type):
    def __new__(self, name, bases, ns):
        self.flag = 0
        return super(WorkOnce, self).__new__(WorkOnce, name, bases, ns)

    def mro(self):
        if self.flag > 0:
            raise RuntimeError("bozo")
        return type.mro(self)


class Base:
    pass


class NewBase:
    pass


class Middle(Base):
    pass


class Below(Middle):
    pass


class Failing(Middle, metaclass=WorkOnce):
    pass


Failing.flag = 1
print("rollback", Middle.__mro__, Below.__mro__, Failing.__mro__)
refusal("mro", lambda: setattr(Middle, "__bases__", (NewBase,)))
print(
    "rollback",
    Middle.__bases__,
    Middle.__mro__,
    Below.__mro__,
    Failing.__mro__,
    Base.__subclasses__(),
    NewBase.__subclasses__(),
)
