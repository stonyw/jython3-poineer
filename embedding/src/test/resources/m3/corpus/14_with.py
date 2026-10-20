"""with: the value stack that puts `__exit__` under the body, and the two instructions that read it
back when the body fails.

Nothing here looks at the third argument `__exit__` is handed: a traceback is the object that lives in
it, and the runtime has no traceback yet, so that absence is written down in the plan rather than
printed here as an answer."""


def describe(label, thunk):
    try:
        return label + " -> " + str(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


events = []


class Watched:
    def __init__(self, tag):
        self.tag = tag

    def __enter__(self):
        events.append("enter " + self.tag)
        return self.tag.upper()

    def __exit__(self, exc_type, exc_value, traceback):
        events.append("exit " + self.tag)
        return False


print("--- the order a body is entered and left in")
with Watched("a") as first:
    events.append("body " + first)
with Watched("b"):
    events.append("body b")
print(events)

print("--- what a clean exit is handed")


class Arguer:
    def __init__(self, answer):
        self.answer = answer

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        print("   handed", (exc_type, exc_value), "answered", type(self).__name__)
        return self.answer


with Arguer(False) as argued:
    print("  body", argued is not None)


def through(answer):
    with Arguer(answer):
        raise ValueError("kept")
    return "swallowed"


print("  out of a failing body:", describe("true", lambda: through(True)))
print("  out of a failing body:", describe("false", lambda: through(False)))

print("--- the truth an exit answer is judged by")


class Judge:
    def __init__(self, answer):
        self.answer = answer

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return self.answer


class LenJudge:
    def __init__(self, size):
        self.size = size

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return [0] * self.size


class BoolJudge:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return self

    def __bool__(self):
        return False


def judged(answer):
    with Judge(answer):
        raise ValueError("kept")
    return "swallowed"


def judged_with(manager):
    with manager:
        raise ValueError("kept")
    return "swallowed"


for label, answer in (("None", None), ("zero", 0), ("empty text", ""), ("one", 1), ("text", "x")):
    print("  ", label, describe(label, lambda answer=answer: judged(answer)))
for label, size in (("empty list", 0), ("one item", 1)):
    print("  ", label, describe(label, lambda size=size: judged_with(LenJudge(size))))
print("  ", "own __bool__ says no", describe("bool", lambda: judged_with(BoolJudge())))

print("--- several items, and the order they are left in")
events = []
with Watched("a") as left_item, Watched("b") as right_item, Watched("c"):
    events.append("body " + left_item + right_item)
print(events)

print("  one item failing to be entered")
events = []


class EnterFails:
    def __enter__(self):
        raise IndexError("no entry")

    def __exit__(self, exc_type, exc_value, traceback):
        events.append("exit of the one that could not enter")
        return False


def entered_once():
    with Watched("first"), EnterFails():
        events.append("body never")
    return "never reached"


print("    ", describe("enter raises", entered_once), events)

print("  a body that fails between two items")
events = []


def failing_in_the_middle():
    with Watched("outer"):
        with Watched("inner"):
            raise ValueError("middle")
    return "never reached"


print("    ", describe("nested bodies", failing_in_the_middle), events)

print("--- the body leaving by a jump")
events = []


def by_return():
    with Watched("r"):
        return "returned"


print("  return", by_return(), events)

events = []


def by_break():
    for count in range(3):
        with Watched("k" + str(count)):
            if count == 1:
                break
    return events


print("  break", by_break())

events = []


def by_continue():
    for count in range(2):
        with Watched("c" + str(count)):
            continue
    return events


print("  continue", by_continue())

print("--- targets, unpacked and not")
with Watched("u") as single:
    print("   one name", single)


class Pair:
    def __enter__(self):
        return ("first", "second")

    def __exit__(self, exc_type, exc_value, traceback):
        print("   pair exit")
        return False


with Pair() as (packed_left, packed_right):
    print("   unpacked", packed_left, packed_right)

with Watched("p"), Watched("q"):
    print("   no target at all")

print("--- inherited, and reached through the type only")


class Base:
    def __enter__(self):
        return "from base"

    def __exit__(self, exc_type, exc_value, traceback):
        print("   exit from base")
        return False


class Derived(Base):
    def __enter__(self):
        return "from derived"


with Derived() as inherited:
    print("  ", inherited)


class OnTheInstance:
    pass


def instance_only():
    held = OnTheInstance()
    held.__enter__ = lambda: "never"
    held.__exit__ = lambda *arguments: False
    with held:
        return "body never"
    return "never reached"


print("  ", describe("attributes on the value", instance_only))

print("--- the forms a member of the class can take")


class Wrapped:
    @classmethod
    def __enter__(cls):
        print("   classmethod enter for", cls.__name__)
        return "bound to the class"

    @classmethod
    def __exit__(cls, exc_type, exc_value, traceback):
        print("   classmethod exit")
        return False


with Wrapped() as member:
    print("  ", member)


class StaticWrapped:
    @staticmethod
    def __enter__():
        print("   staticmethod enter")
        return "no receiver"

    @staticmethod
    def __exit__(exc_type, exc_value, traceback):
        print("   staticmethod exit")
        return False


with StaticWrapped() as plain:
    print("  ", plain)


class PropertyWrapped:
    @property
    def __enter__(self):
        return lambda: "answered by a property"

    @property
    def __exit__(self):
        return lambda *arguments: False


with PropertyWrapped() as answered:
    print("  ", answered)


class StarArguments:
    def __enter__(self):
        return self

    def __exit__(self, *arguments):
        print("   exit saw", len(arguments), [type(a).__name__ for a in arguments[:2]])
        return False


with StarArguments():
    print("   body")

print("--- refusals")


class Bare:
    pass


class OnlyEnter:
    def __enter__(self):
        return self


class OnlyExit:
    def __exit__(self, exc_type, exc_value, traceback):
        return False


class Asynchronous:
    def __aenter__(self):
        return self

    def __aexit__(self, exc_type, exc_value, traceback):
        return False


class HalfAsynchronous:
    def __aenter__(self):
        return self


class EnterIsInt:
    __enter__ = 1

    def __exit__(self, exc_type, exc_value, traceback):
        return False


class ExitIsText:
    def __enter__(self):
        return self

    __exit__ = "not callable"


class EnterWantsMore:
    def __enter__(self, extra):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False


class ExitIsShort:
    def __enter__(self):
        return self

    def __exit__(self, exc_type):
        return False


class BothNameArguments:
    def __enter__(self):
        return self

    def __exit__(self, exc_type=1, exc_value=2):
        return False


def use(manager):
    with manager:
        pass


for label, manager in (
    ("bare", Bare()),
    ("only enter", OnlyEnter()),
    ("only exit", OnlyExit()),
    ("asynchronous", Asynchronous()),
    ("half asynchronous", HalfAsynchronous()),
    ("enter is an int", EnterIsInt()),
    ("exit is text", ExitIsText()),
    ("enter wants more", EnterWantsMore()),
    ("exit is short", ExitIsShort()),
    ("exit names its arguments", BothNameArguments()),
    ("a tuple", (1, 2)),
    ("an int", 3),
    ("a list of one", [Bare()]),
    ("a function", use),
    ("none", None),
):
    print("  ", label, describe(label, lambda manager=manager: use(manager)))


class Inside:
    class Name:
        pass


print("  ", describe("a class nested in a class", lambda: use(Inside.Name())))


def inside_function():
    class InFunction:
        pass

    return describe("a class in a function", lambda: use(InFunction()))


print("  ", inside_function())

print("--- an exit that fails")


class ExitFails:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        raise RuntimeError("from the exit")


def exit_fails():
    with ExitFails():
        raise ValueError("inner")


print("  ", describe("a body that fails", exit_fails))


def chained_after_exit_raises():
    try:
        exit_fails()
    except RuntimeError as error:
        return [str(error), type(error.__context__).__name__, str(error.__context__)]


print("   chained", chained_after_exit_raises())


class CleanExitFails:
    def __enter__(self):
        return "entered"

    def __exit__(self, exc_type, exc_value, traceback):
        raise KeyError("missing")


print("  ", describe("a clean body", lambda: use(CleanExitFails())))

print("--- a member that is not a descriptor of its own")


class UsesLen:
    __enter__ = len
    __exit__ = len


print("  ", describe("len for both halves", lambda: use(UsesLen())))


class Sized:
    def __len__(self):
        return 7

    __enter__ = len
    __exit__ = len


print("  ", describe("len on a sized value", lambda: use(Sized())))


class Reports:
    def __enter__(self):
        return 1

    __exit__ = print


print("  ", describe("print as the exit", lambda: use(Reports())))


class Appends:
    def __enter__(self):
        return 1

    __exit__ = [].append


print("  ", describe("a bound method as the exit", lambda: use(Appends())))


class Uppercased:
    def __enter__(self):
        return 1

    __exit__ = str.upper


print("  ", describe("an unbound method of another type", lambda: use(Uppercased())))


class BoundMethod:
    __enter__ = "abc".upper

    def __exit__(self, exc_type, exc_value, traceback):
        return False


def used_bound():
    with BoundMethod() as value:
        return value


print("  ", describe("a bound method as the enter", used_bound))


class BoundExit:
    def __enter__(self):
        return 1

    __exit__ = "abc".upper


print("  ", describe("a bound method as the exit", lambda: use(BoundExit())))

print("--- one value used twice, and handlers that agree with each other")


def reused():
    manager = Watched("twice")
    with manager as earlier:
        pass
    with manager as later:
        pass
    return [earlier, later, events]


events = []
print("  ", describe("reuse", reused))


def reported_after():
    with Watched("outer"):
        try:
            with Watched("inner"):
                raise IndexError("inside")
        except IndexError as caught:
            return [type(caught).__name__, str(caught), events]


events = []
print("  ", describe("a handler inside a body", reported_after))
