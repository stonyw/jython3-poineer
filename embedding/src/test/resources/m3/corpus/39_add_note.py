"""What a note is, the list it goes on, and the two names a refusal of it wears.

``add_note`` is a method of ``BaseException``, filed once and inherited all the way down, and it is the
only way a value ever comes to have ``__notes__``: the name is not on the exception until the first note is
added, and what is filed is an ordinary attribute holding an ordinary list — which is why a class that
files a list under that name itself is answered by *that* list, shared by every instance of it, and why a
``__setattr__`` that throws the write away loses the note without a word. A note has to be text; the
argument table says so before anything looks at the list, and it spells a non-text value by its class with
``None`` named as the value rather than as its type. A ``__notes__`` that is already there and is not a
list is refused, and the note is not appended.

The two refusals of the argument list name the method differently, and which one a call hears depends on
how the method was reached rather than on what it was given. Reading a name and calling what comes back is
a call of the descriptor with the receiver in hand, so the complaint is written under the class that
declares the method; binding it first — to a local, to a star-unpacked call, to anything that holds the
value — makes the copy name the class of the value it was made from. The same split runs under
``with_traceback`` and the three group methods, and none of them has a keyword table, so a named argument
is refused as taking none rather than counted as one more."""


def describe(label, thunk):
    try:
        return label + ": " + repr(thunk())
    except BaseException as error:
        return label + ": " + type(error).__name__ + ": " + str(error)


def callBound(method, *arguments, **named):
    return method(*arguments, **named)


class Written(Exception):
    pass


class Shared(Exception):
    __notes__ = ["filed by the class"]


class Swallowing(Exception):
    def __setattr__(self, name, value):
        print("asked for", name, type(value).__name__)


class Unrelated:
    pass


print("--- the name arrives with the first note")
first = Written("first")
print(
    describe("before", lambda: [
        hasattr(first, "__notes__"),
        "__notes__" in vars(first),
        "add_note" in vars(Written),
    ])
)
print(describe("the answer of a note", lambda: first.add_note("a note")))
print(describe("after", lambda: [first.__notes__, list(vars(first))]))
print(describe("a second", lambda: [first.add_note("another"), first.__notes__]))
print(describe("the same list", lambda: first.__notes__ is first.__dict__["__notes__"]))
print(describe("args untouched", lambda: [first.args, str(first), type(first).__name__]))


print("--- what the argument table takes")
integers = Written("integers")
print(describe("text", lambda: [integers.add_note("plain"), integers.__notes__]))
print(describe("an int", lambda: integers.add_note(3)))
print(describe("none", lambda: integers.add_note(None)))
print(describe("bytes", lambda: integers.add_note(b"raw")))
print(describe("a written value", lambda: integers.add_note(Unrelated())))
print(describe("a list", lambda: integers.add_note(["not", "text"])))
print(describe("nothing", lambda: integers.add_note()))
print(describe("two", lambda: integers.add_note("a", "b")))
print(describe("named", lambda: integers.add_note(note="a")))
print(describe("notes after", lambda: integers.__notes__))


print("--- the two names a refusal wears")
print(describe("the method form", lambda: Written("m").add_note()))
spelled = Written("spelled")
print(describe("the attribute form", lambda: callBound(spelled.add_note)))
print(describe("a star-unpacked call", lambda: spelled.add_note(*[])))
print(describe("the descriptor itself", lambda: BaseException.add_note(spelled, "an unbound note")))
print(describe("the descriptor on a value", lambda: BaseException.add_note(1, "a note")))
print(describe("the descriptor with nothing", lambda: BaseException.add_note()))
print(describe("the descriptor on a subclass", lambda: Written.add_note(spelled, "through the subclass")))
print(describe("the descriptor with a bound copy", lambda: callBound(spelled.add_note, "text")))
print(describe("bound with a name", lambda: callBound(spelled.add_note, note="text")))
print(describe("bound with three", lambda: callBound(spelled.add_note, "a", "b", "c")))
print(describe("notes now", lambda: spelled.__notes__))


print("--- the member as it is filed")
print(describe("identity", lambda: [
    Written.add_note is BaseException.add_note,
    ValueError.add_note is TypeError.add_note,
]))
print(describe("names", lambda: [
    BaseException.add_note.__name__,
    BaseException.add_note.__qualname__,
    BaseException.add_note.__doc__,
    type(BaseException.add_note).__name__,
    repr(BaseException.add_note),
    type(spelled.add_note).__name__,
    spelled.add_note.__qualname__,
    spelled.add_note == spelled.add_note,
]))


print("--- a list that is already there")
hand = Written("hand")
hand.__notes__ = ["written by hand"]
print(describe("appended", lambda: [hand.add_note("added"), hand.__notes__]))
print(describe("the same object", lambda: hand.__notes__ is hand.__dict__["__notes__"]))

tupleNotes = Written("tuple")
tupleNotes.__notes__ = ("not", "a", "list")
print(describe("a tuple", lambda: tupleNotes.add_note("refused")))
print(describe("still the tuple", lambda: tupleNotes.__notes__))

stringNotes = Written("string")
stringNotes.__notes__ = "a plain string"
print(describe("a string", lambda: stringNotes.add_note("refused")))

noneNotes = Written("none")
noneNotes.__notes__ = None
print(describe("none", lambda: noneNotes.add_note("refused")))

objectNotes = Written("object")
objectNotes.__notes__ = Unrelated()
print(describe("an object", lambda: objectNotes.add_note("refused")))
print(describe("a refusal leaves the note out", lambda: [
    tupleNotes.__notes__, type(objectNotes.__notes__).__name__]))


print("--- a class that files the list, and one that files the write")
one = Shared("one")
two = Shared("two")
print(describe("read before", lambda: [one.__notes__, Shared.__notes__]))
print(describe("a note", lambda: [one.add_note("added by one"), one.__notes__]))
print(describe("the other instance", lambda: two.__notes__))
print(describe("where the list lives", lambda: [
    "__notes__" in vars(one), "__notes__" in vars(Shared), one.__notes__ is Shared.__notes__]))

swallowed = Swallowing("swallowed")
print(describe("a note to a swallow", lambda: swallowed.add_note("lost")))
print(describe("after", lambda: [hasattr(swallowed, "__notes__"), list(vars(swallowed))]))


print("--- a group carries them too")
group = ExceptionGroup("grouped", [ValueError("inside"), TypeError("also")])
print(describe("a note on a group", lambda: [group.add_note("on a group"), group.__notes__]))
print(describe("the group methods by name", lambda: [
    ExceptionGroup.split.__qualname__,
    ExceptionGroup.split.__name__,
    type(group.split).__name__,
]))


def refuse(thunk):
    try:
        return repr(thunk())
    except BaseException as error:
        return type(error).__name__ + ": " + str(error)


print("--- and the same split under the group's three")
children = ExceptionGroup("children", [ValueError("one")])
print("the method form   ", refuse(lambda: children.split()))
print("a bound copy       ", refuse(lambda: callBound(children.split)))
print("the descriptor     ", refuse(lambda: BaseExceptionGroup.split(children)))
print("a named argument   ", refuse(lambda: children.split(ValueError)))
print("a note never added ", refuse(lambda: children.__notes__))
