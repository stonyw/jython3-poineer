# The descriptor protocol runs in more places than an instance's own dictionary:
# a super object is itself a descriptor, a member that can write but cannot bind
# does not own the read, a name in the class dictionary fills a slot whatever its
# value is, and a classmethod __new__ is bound before the class is prepended.


class A:
    def meth(self):
        return "A"


class B(A):
    def meth(self):
        return "B" + self.__super.meth()


# A valueless super stored on the class acquires the value it walks for; one that
# already holds a value is handed back unchanged, and the class read never binds.
B._B__super = super(B)
print("valueless:", B().meth())
print("class read:", type(B._B__super).__name__, B._B__super is B._B__super)
bound = super(B, B())
B._bound = bound
print("pre-bound:", B()._bound is bound, B._bound is bound)


class Descr:
    def __set__(self, obj, value):
        obj.__dict__["a"] = value


class X:
    a = Descr()


x = X()
print("unset:", type(x.a).__name__)
x.a = 42
print("set then read:", x.a)


class Meta(type):
    pass


class Y(metaclass=Meta):
    pass


Y.a = 42
Meta.a = Descr()
print("metatype:" , Y.a)


class P:
    __getitem__ = property(
        lambda self: (_ for _ in ()).throw(ZeroDivisionError("boom")))


try:
    list(P())
except ZeroDivisionError as error:
    print("getitem slot:", type(error).__name__, error)


class C:
    @classmethod
    def __new__(cls, *args):
        return (cls.__name__, args)


print("classmethod new:", C(1, 2))


class D:
    @staticmethod
    def __new__(cls, *args):
        return (cls.__name__, args)


print("staticmethod new:", D(3, 4))

items = []
print("wrapper hash:", hash(items.__add__) == hash(items.__add__), hash(items.append) == hash(items.append))
