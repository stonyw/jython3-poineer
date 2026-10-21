"""How a group is made, which class answers for it, and what the two classes keep.

`type(g)` is often not the class that was called. `BaseExceptionGroup("m", [ValueError()])` is promoted to
`ExceptionGroup` because every child is an `Exception`, an `ExceptionGroup` refuses to hold a
`KeyboardInterrupt` at all, and a class a person wrote under `BaseExceptionGroup` is never promoted — so
the same children answer with three different type names depending on the class the call started from.

The order of the refusals is the order `BaseExceptionGroup_new` makes them: the two arguments, the
sequence, each child, the class, and only then the keyword arguments, which is why a keyword is refused in
the name of the class the value *would have been* made as. Three values are kept and each answers a
different question: `message` and `exceptions` are the group's own and read-only, `args` is the pair as it
arrived, and `repr` shows the children the group holds in the shape of the sequence the call was given.
"""


def names(values):
    return [type(each).__name__ + ": " + str(each) for each in values]


def show(label, function):
    try:
        print("  " + label + " -> " + repr(function()))
    except BaseException as error:
        print("  " + label + " -> " + type(error).__name__ + ": " + str(error))


V = ValueError("v")
T = TypeError("t")
KI = KeyboardInterrupt("ki")
SE = SystemExit("exit")

print("=== which class answers for the children")
show("EG all Exception", lambda: type(ExceptionGroup("m", [V, T])).__name__)
show("BEG all Exception", lambda: type(BaseExceptionGroup("m", [V])).__name__)
show("BEG one base", lambda: type(BaseExceptionGroup("m", [V, KI])).__name__)
show("BEG all base", lambda: type(BaseExceptionGroup("m", [KI, SE])).__name__)
show("EG a base", lambda: type(ExceptionGroup("m", [KI])).__name__)
show("isinstance EG of Exception", lambda: isinstance(ExceptionGroup("m", [V]), Exception))
show("isinstance base group of Exception", lambda: isinstance(BaseExceptionGroup("m", [KI]), Exception))
show("nested group is a leaf to the check", lambda: type(ExceptionGroup("m", [ExceptionGroup("i", [V])])).__name__)
show("nested group holding a base", lambda: type(ExceptionGroup("m", [BaseExceptionGroup("i", [KI])])).__name__)

print("=== the refusals, in the order they are made")
show("no arguments", lambda: BaseExceptionGroup())
show("one argument", lambda: BaseExceptionGroup("m"))
show("three arguments", lambda: BaseExceptionGroup("m", [V], 1))
show("first not a string", lambda: BaseExceptionGroup(1, [V]))
show("second not a sequence", lambda: BaseExceptionGroup("m", 3))
show("empty sequence", lambda: BaseExceptionGroup("m", []))
show("a child that is not an exception", lambda: BaseExceptionGroup("m", [1]))
show("second child not an exception", lambda: BaseExceptionGroup("m", [V, "x"]))
show("keyword, promoted", lambda: BaseExceptionGroup("m", [V], flag=1))
show("keyword, plain", lambda: BaseExceptionGroup("m", [KI], flag=1))
show("keyword, subclass", lambda: type(BaseExceptionGroup("m", [V], flag=1)))

print("=== what is a sequence here")


class OnlyIter:
    def __iter__(self):
        return iter([V, T])


class OnlyItem:
    def __getitem__(self, index):
        if index > 1:
            raise IndexError(index)
        return [V, T][index]

    def __len__(self):
        return 2


class Both:
    def __iter__(self):
        return iter([V])

    def __getitem__(self, index):
        return KI


class KeyedByString:
    def __getitem__(self, key):
        raise KeyError(key)


show("list", lambda: len(BaseExceptionGroup("m", [V, T]).exceptions))
show("tuple", lambda: len(BaseExceptionGroup("m", (V, T)).exceptions))
show("iterable only", lambda: len(BaseExceptionGroup("m", OnlyIter()).exceptions))
show("indexed only", lambda: len(BaseExceptionGroup("m", OnlyItem()).exceptions))
show("both protocols", lambda: names(BaseExceptionGroup("m", Both()).exceptions))
show("string keyed", lambda: names(BaseExceptionGroup("m", KeyedByString()).exceptions))
show("generator", lambda: BaseExceptionGroup("m", (x for x in [V])))
show("set", lambda: BaseExceptionGroup("m", {V}))
show("dict", lambda: BaseExceptionGroup("m", {"a": V}))
show("string", lambda: BaseExceptionGroup("m", "ab"))

print("=== the three values the group keeps")
g = BaseExceptionGroup("m", [V, KI])
show("message", lambda: g.message)
show("exceptions is a tuple", lambda: type(g.exceptions).__name__)
show("str", lambda: str(g))
show("str of one", lambda: str(BaseExceptionGroup("m", [V])))
show("str of an empty message", lambda: str(BaseExceptionGroup("", [V])))
show("repr of a list", lambda: repr(BaseExceptionGroup("m", [V, KI])))
show("repr of a tuple", lambda: repr(BaseExceptionGroup("m", (V, KI))))
given = [V]
snapshot = BaseExceptionGroup("m", given)
given.append(T)
show("repr follows the children", lambda: repr(snapshot))
show("args keeps the object", lambda: snapshot.args[1] is given)
show("args after the change", lambda: repr(snapshot.args[1]))
show("exceptions after the change", lambda: len(snapshot.exceptions))

print("=== the two read-only members")
show("write message", lambda: setattr(g, "message", "x"))
show("write exceptions", lambda: setattr(g, "exceptions", ()))
show("delete message", lambda: delattr(g, "message"))
show("write something else", lambda: (setattr(g, "extra", 1), g.extra))
show("read message off the dictionary", lambda: "message" in g.__dict__)

print("=== names the two classes publish")


class Bundle(ExceptionGroup):
    pass


class BaseBundle(BaseExceptionGroup):
    pass


show("EG module", lambda: ExceptionGroup("m", [V]).__module__)
show("BEG module", lambda: BaseExceptionGroup("m", [KI]).__module__)
show("EG doc", lambda: ExceptionGroup.__doc__)
show("BEG doc", lambda: BaseExceptionGroup.__doc__)
show("BEG new is EG new", lambda: ExceptionGroup.__new__ is BaseExceptionGroup.__new__)
show("EG bases", lambda: [base.__name__ for base in ExceptionGroup.__bases__])
show("BEG bases", lambda: [base.__name__ for base in BaseExceptionGroup.__bases__])
show("EG mro", lambda: [each.__name__ for each in ExceptionGroup.__mro__])
show("subclass dict", lambda: sorted(Bundle.__dict__))
show("subclass module", lambda: Bundle("m", [V]).__module__)
show("subclass is not promoted", lambda: type(BaseBundle("m", [V])).__name__)
show("subclass holds a base", lambda: type(BaseBundle("m", [KI])).__name__)
show("subclass of ExceptionGroup holds a base", lambda: type(Bundle("m", [KI])).__name__)
show("subclass keyword", lambda: BaseBundle("m", [V], flag=1))
show("subclass len", lambda: len(Bundle("m", [V, T])))
show("subclass iterate", lambda: [type(each).__name__ for each in Bundle("m", [V, T])])
