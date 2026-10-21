"""The names a group's members answer with, which are not always the name of the class they were read from.

A bound method descriptor names the class of the value it was bound to — `Named("m", [e]).split` reads
`Named.split` — while the same member read off a class names the class that declares it, so `Named.split`
is `BaseExceptionGroup.split`. A wrapper descriptor, which is what `__init__` is, keeps the declaring name
even when bound. And the arity complaint of a method written against the old interface names the declaring
class whatever the value's own class is: `Named("m", [e]).split()` says `BaseExceptionGroup.split()`.

Two of the members are the same object seen through both classes, because `ExceptionGroup` inherits them
rather than redeclaring them, and a subclass of either writes over them without owning a copy: the names a
class a person wrote files in its own dictionary are only the ones the class statement put there.
"""

V = ValueError("v")
KI = KeyboardInterrupt("ki")


class Named(BaseExceptionGroup):
    pass


class FromExceptionGroup(ExceptionGroup):
    pass


def show(label, function):
    try:
        print("  " + label + " -> " + repr(function()))
    except BaseException as error:
        print("  " + label + " -> " + type(error).__name__ + ": " + str(error))


plain = ExceptionGroup("m", [V])
base = BaseExceptionGroup("m", [KI])
named = Named("m", [V])

print("=== a bound member names the value's own class")
show("EG split", lambda: plain.split.__qualname__)
show("BEG split", lambda: base.split.__qualname__)
show("subclass split", lambda: named.split.__qualname__)
show("subclass subgroup", lambda: named.subgroup.__qualname__)
show("subclass derive", lambda: named.derive.__qualname__)
show("EG init", lambda: plain.__init__.__qualname__)
show("subclass init", lambda: named.__init__.__qualname__)
show("EG new", lambda: plain.__new__.__qualname__)
show("subclass new", lambda: named.__new__.__qualname__)
show("with_traceback", lambda: plain.with_traceback.__qualname__)
show("subclass with_traceback", lambda: named.with_traceback.__qualname__)
show("args of a bound method", lambda: plain.split.__self__ is plain)
show("function of a bound method", lambda: plain.split.__func__ is BaseExceptionGroup.split)

print("=== the same member read off a class names its declarer")
show("EG class split", lambda: ExceptionGroup.split.__qualname__)
show("BEG class split", lambda: BaseExceptionGroup.split.__qualname__)
show("subclass class split", lambda: Named.split.__qualname__)
show("subclass class with_traceback", lambda: Named.with_traceback.__qualname__)
show("EG class subgroup", lambda: ExceptionGroup.subgroup.__qualname__)
show("EG class derive", lambda: ExceptionGroup.derive.__qualname__)
show("EG class init", lambda: ExceptionGroup.__init__.__qualname__)
show("EG class new", lambda: ExceptionGroup.__new__.__qualname__)
show("type mro", lambda: type(Named).mro.__qualname__)
show("EG mro", lambda: ExceptionGroup.mro.__qualname__)

print("=== what the descriptor itself says")
show("split objclass", lambda: ExceptionGroup.split.__objclass__.__name__)
show("subgroup objclass", lambda: ExceptionGroup.subgroup.__objclass__.__name__)
show("derive objclass", lambda: ExceptionGroup.derive.__objclass__.__name__)
show("new objclass", lambda: ExceptionGroup.__new__.__objclass__.__name__)
show("split name", lambda: ExceptionGroup.split.__name__)
show("split doc", lambda: BaseExceptionGroup.split.__doc__)
show("new doc", lambda: BaseExceptionGroup.__new__.__doc__)
show("init doc", lambda: BaseExceptionGroup.__init__.__doc__)
show("same member both ways", lambda: ExceptionGroup.split is BaseExceptionGroup.split)
show("EG new is BEG new", lambda: ExceptionGroup.__new__ is BaseExceptionGroup.__new__)
show("subclass dict has nothing", lambda: [name for name in ("split", "derive", "message") if name in Named.__dict__])
show("bound methods compare equal", lambda: named.split == named.split)
show("bound methods are not the same", lambda: named.split is named.split)

print("=== the name a refusal is filed under")
show("split with nothing", lambda: plain.split())
show("split with two", lambda: plain.split(ValueError, TypeError))
show("subclass split with nothing", lambda: named.split())
show("subclass subgroup with nothing", lambda: named.subgroup())
show("subclass derive with nothing", lambda: named.derive())
show("subclass split by keyword", lambda: named.split(matcher=ValueError))
show("EG called with one", lambda: ExceptionGroup("m"))
show("subclass called with one", lambda: Named("m"))
show("subclass called with three", lambda: Named("m", [V], 1))
show("EG keyword refusal names the class", lambda: ExceptionGroup("m", [V], flag=1))
show("BEG keyword refusal names the class", lambda: BaseExceptionGroup("m", [KI], flag=1))
show("subclass keyword refusal", lambda: Named("m", [V], flag=1))

print("=== what a class a person wrote files under its own name")
show("subclass of EG doc", lambda: FromExceptionGroup.__dict__["__doc__"])
show("subclass of EG module", lambda: FromExceptionGroup.__dict__["__module__"])

print("=== a member written over by a class a person wrote")


class Overridden(ExceptionGroup):
    split = "mine"


show("overridden split", lambda: Overridden("m", [V]).split)
show("overridden unbound", lambda: Overridden.split)
show("the original still there", lambda: BaseExceptionGroup.split.__qualname__)
show("subgroup not overridden", lambda: Overridden("m", [V]).subgroup.__qualname__)
