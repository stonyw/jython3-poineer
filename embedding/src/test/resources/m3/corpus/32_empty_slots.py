"""The two sentences a frame gives for a name that holds nothing.

A slot that has never been filled, or one that `del` has emptied, is not a missing dictionary entry:
the compiler turned that name into a slot, so the answer comes from the frame and it says which frame
owns the name. A name this frame owns is an `UnboundLocalError` about this scope; a name that belongs
to the frame which made the closure is a `NameError` about an enclosing one. Nothing here is a number
the reader writes down: each sentence is what the interpreter that compiled the file says back."""


def describe(label, thunk):
    try:
        return label + " -> " + repr(thunk())
    except BaseException as error:
        return label + " -> " + type(error).__name__ + ": " + str(error)


print("--- a name this frame owns")


def only_sometimes(kept):
    if kept:
        carried = "here"
    return carried


def unmade():
    del gone
    gone = "here"
    return gone


def deleted():
    carried = "here"
    del carried
    return carried


print(describe("read where the branch did not bind it", lambda: only_sometimes(False)))
print(describe("read after the binding", lambda: only_sometimes(True)))
print(describe("a name deleted before it was bound", unmade))
print(describe("a name deleted after it was bound", deleted))


print("--- a name the enclosing frame owns")


def never_bound(shown):
    def reads():
        return missing
    if shown:
        missing = "here"
    return reads


def emptied():
    def reads():
        return carried
    carried = "here"
    del carried
    return reads


def left_behind():
    def reads():
        return carried
    carried = "here"
    del carried
    return reads


print(describe("a cell no branch ever filled", lambda: never_bound(False)()))
print(describe("a cell filled by the other branch", lambda: never_bound(True)()))
print(describe("a cell emptied while the maker still runs", lambda: emptied()()))
print(describe("a cell emptied before the closure was handed out", lambda: left_behind()()))


print("--- the same name reached through another frame")


def enclosing(how):
    def rows():
        return [carried for _ in range(1)]

    def items():
        yield carried

    def inline():
        return lambda: carried

    def deep():
        def inner():
            return carried
        return inner

    carried = "here"
    del carried
    return {"comprehension": rows, "generator": items, "lambda": inline, "nested": deep}[how]


for shape in ["comprehension", "generator", "lambda", "nested"]:
    made = enclosing(shape)
    if shape == "generator":
        print(describe(shape + " reading it", lambda: next(made())))
    elif shape == "comprehension":
        print(describe(shape + " reading it", made))
    else:
        print(describe(shape + " reading it", lambda: made()()))
