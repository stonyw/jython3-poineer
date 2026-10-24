"""The name ``__call__`` files, and the two questions a call asks.

A value a class statement made answers a call in two steps, and they have different answers. ``callable(x)``
reads the type's ``tp_call``, which the class machinery fills only when the body filed a ``__call__`` — so
``callable(NoCall())`` is ``False`` and ``NoCall()()`` says ``'NoCall' object is not callable``. The call
itself is ``slot_tp_call``, which looks the name up on the type and runs whatever the lookup hands back, with
the value prepended. That is why ``callable(Nothing())`` is ``True`` while ``Nothing()()`` says ``'NoneType'
object is not callable``: ``Nothing`` files ``__call__ = None``, so the type is callable and the call reaches
``None``, and ``None`` has no ``tp_call`` of its own. The same split gives ``__call__ = 5`` the sentence of an
int and ``__call__ = str.upper`` the receiver test of a method descriptor —
``descriptor 'upper' for 'str' objects doesn't apply to a 'Uppered' object`` — because the lookup runs the
descriptor and the descriptor refuses the receiver it was handed.

What the lookup hands back decides the arity. A Python function filed as ``__call__`` is bound, so
``Declared()(21)`` is ``42`` and a keyword reaches the parameter by name; read from the value, the same name is
a bound method and read from the type it is the plain function that still wants the receiver first, so all
three answer ``6``. ``__call__ = len`` is a builtin rather than a function, so the instance arrives as *its*
argument and the complaint is len's own: ``len() takes exactly one argument (0 given)``, and ``(3 given)`` when
the call passes three. A ``__call__`` that returns ``NotImplemented`` hands that value back to the caller — the
refusal belongs to the function, not to the call machinery, and nothing turns it into an error. A
``classmethod`` is passed the type, so ``cls.__name__`` answers ``'Classed'``; a ``property`` is run and its
result is called, so ``Through`` answers ``'through'``; a ``staticmethod`` wrapper hands back the plain
function, which then sees the instance as an unfilled parameter and says
``Stated.<lambda>() missing 1 required positional argument: 'self'`` — a wrapper made in Python, so it also
knows its own qualified name, which the one filed by a method table does not.

The metatype's ``__call__`` is a third question, asked of a class rather than of one of its values:
``Built()`` runs the metaclass and answers ``'meta'``, while ``callable(Built())`` is ``False`` because
``Built`` itself files no ``__call__`` — the class is callable since ``type`` has a ``tp_call``, its values are
not since it does not.

The rest is what the entries of the two dictionaries from the last two cases say about being called. A slot
wrapper is callable: ``bytes.__dict__["__len__"]`` answers ``True`` to ``callable`` and ``3`` to ``(b"abc")``.
So is the bound wrapper a read of ``b"ab".__eq__`` gives, and a ``method_descriptor`` such as ``str.upper``, and
a class-method read such as ``str.maketrans`` — already bound, and of kind ``builtin_function_or_method`` — and
``str.__new__``, which takes the type it is to build first. A ``mappingproxy`` is not callable, nor a ``property``
value, nor a ``classmethod`` value, nor a docstring; the types ``property``, ``staticmethod`` and ``super`` are,
and so is every class of a value that can be called. No line below prints a ``repr()`` of a bound method or of a
built-in read, because CPython's version of those names an address.
"""


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


class NoCall:
    pass


class Declared:
    def __call__(self, n):
        return n * 2


class Answered:
    def __call__(self):
        return NotImplemented


class OneLength:
    __call__ = len


class ANumber:
    __call__ = 5


class Uppered:
    __call__ = str.upper


class Nothing:
    __call__ = None


class Inherited(Declared):
    pass


class Stated:
    __call__ = staticmethod(lambda self: "stated")


class Classed:
    __call__ = classmethod(lambda cls: cls.__name__)


class Through:
    @property
    def __call__(self):
        return lambda: "through"


class Bound:
    def shout(self):
        return "shout"

    __call__ = shout


class Meta(type):
    def __call__(cls):
        return "meta"


class Built(metaclass=Meta):
    pass


print("== the callable question and the call ==")
show("nocall", lambda: NoCall()())
show("callable(nocall)", lambda: callable(NoCall()))
show("nothing", lambda: Nothing()())
show("callable(nothing)", lambda: callable(Nothing()))
show("a number", lambda: ANumber()())
show("callable(a number)", lambda: callable(ANumber()))
show("uppered", lambda: Uppered()())
show("callable(uppered)", lambda: callable(Uppered()))

print("== a function filed as __call__ ==")
show("declared", lambda: Declared()(21))
show("declared kwargs", lambda: Declared()(n=4))
show("declared read", lambda: Declared().__call__(3))
show("declared unbound", lambda: Declared.__call__(Declared(), 3))
show("inherited", lambda: Inherited()(3))
show("callable(inherited)", lambda: callable(Inherited()))
show("answered", lambda: Answered()())
show("answered is NotImplemented", lambda: Answered()() is NotImplemented)
show("bound", lambda: Bound()())
show("bound kind", lambda: type(Bound().__call__).__name__)
show("bound twice", lambda: Bound()().bound())

print("== the kinds a body may file under that name ==")
show("one length", lambda: OneLength()())
show("one length many", lambda: OneLength()(1, 2, 3))
show("stated", lambda: Stated()())
show("stated qualname", lambda: Stated.__dict__["__call__"].__qualname__)
show("classed", lambda: Classed()())
show("through", lambda: Through()())

print("== the metatype answers for a class, not for its values ==")
show("built", lambda: Built())
show("callable(built class)", lambda: callable(Built))
show("callable(built value)", lambda: callable(Built()))
show("instance", lambda: callable(NoCall()))
show("type", lambda: callable(NoCall))
show("function", lambda: callable(Declared.__call__))
show("method", lambda: callable(Bound().shout))

print("== what the filed descriptors answer ==")
show("bytes __len__ entry", lambda: bytes.__dict__["__len__"])
show("callable(bytes __len__)", lambda: callable(bytes.__dict__["__len__"]))
show("bytes __len__ run", lambda: bytes.__dict__["__len__"](b"abc"))
show("callable(b'ab'.__eq__)", lambda: callable(b"ab".__eq__))
show("b'ab'.__eq__ run", lambda: b"ab".__eq__(b"ab"))
show("callable(str.upper)", lambda: callable(str.upper))
show("str.upper run", lambda: str.upper("ab"))
show("callable(str.maketrans)", lambda: callable(str.maketrans))
show("str.maketrans kind", lambda: type(str.maketrans).__name__)
show("callable(str.__new__)", lambda: callable(str.__new__))
show("str.__new__ run", lambda: str.__new__(str, "ab"))
show("callable(dict proxy)", lambda: callable(str.__dict__))
show("proxy kind", lambda: type(str.__dict__).__name__)
show("callable(property)", lambda: callable(property))
show("callable(property value)", lambda: callable(property(lambda s: s)))
show("callable(staticmethod value)", lambda: callable(staticmethod(len)))
show("callable(classmethod value)", lambda: callable(classmethod(len)))
show("callable(super)", lambda: callable(super))
show("callable(str.__doc__)", lambda: callable(str.__doc__))
show("callable(object doc entry)", lambda: callable(object.__dict__["__doc__"]))

print("== values that answer no ==")
show("None", lambda: callable(None))
show("int", lambda: callable(5))
show("str value", lambda: callable("x"))
show("bytes value", lambda: callable(b"x"))
show("list value", lambda: callable([]))
show("dict value", lambda: callable({}))
show("generator", lambda: callable((x for x in ())))
show("int type", lambda: callable(int))
show("built by type()", lambda: callable(type("T", (), {})))
