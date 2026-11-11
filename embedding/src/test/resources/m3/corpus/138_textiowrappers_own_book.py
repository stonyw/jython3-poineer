r"""The text wrapper's own book, and the five parameters it retakes.

`_io.TextIOWrapper` keeps the largest book of the io family: thirty-one rows in the reference, thirty
here because `__module__` lives on the type rather than in its dictionary in this runtime — the one
deviation this corpus shares with the four books of slice 87, and the rows are compared name by name
rather than counted. Nineteen of the thirty are names the stream answers: three wrapper_descriptors
(`__repr__`, `__next__`, `__init__`), sixteen method_descriptors, and the one `str` in the `__doc__`
slot. Only two of the sixteen carry a sentence — `reconfigure`, whose text is longer than one line,
and the pair `seek` and `tell`, which are this layer's own tables rather than `_IOBase`'s because it is
this layer that restricts a cookie to what a previous `tell` answered.

The eleven rows below them are the fields, and the book files them in three shapes. Four are
member_descriptors written `Py_READONLY`: `encoding`, `line_buffering` and `write_through` refuse a
write with the bare `readonly attribute` sentence, while `_finalizing` is the one `T_BOOL` member of the
family and so refuses a value that is not a bool in its own words and a delete in the sentence a numeric
field uses. Six are getset_descriptors with no setter at all, which is why writing or deleting one names
the field and the owner; `_CHUNK_SIZE` is the exception among the six, because its getset is registered
with a deleter that refuses rather than declaring the row read-only, and so says `cannot be deleted`
instead.

`reconfigure` is the whole of the writing surface, and the order it works in is the observable part:
the five keywords are parsed and converted first, `newline` takes only the four values a text layer
accepts, the stream has to be attached and open before the codec is looked up, and the flush happens
before any field changes. A parameter handed over as `None` keeps its old value, which is what the
reference does for all five rather than refusing three of them, and an `encoding` is filed exactly as it
was spelled. Two halves of the reference's effect are not modelled here: a new `encoding` is reported
and validated but the decoder under the stream is not rebuilt, and a `newline` is validated but does not
change the translation of what is read afterwards. `__init__` parses the same six parameters, asks the
buffer it was handed to be `readable`, `writable` and `seekable` in that order, and hands back `None`
without rebuilding the layers — the wrapper keeps the buffer it was born with. The probes that hand a
buffer to `__init__` come before the stream is detached, and the one that hands an int comes last,
because the reference leaves the stream uninitialized at that point and nothing after it could be
compared.
"""

import sys

T = type(sys.stdin)


def show(label, call):
    try:
        print(label, repr(call()))
    except BaseException as exc:
        print(label, type(exc).__module__ + "." + type(exc).__name__, "|", exc)


# The book, name by name with the one deviation, and the order `dir` sorts it into.
print("vars", [key for key in vars(T) if key != "__module__"])
print("dir", [key for key in dir(T) if key != "__module__"])

# Every row's shape and sentence: the three wrappers, the sixteen methods, the eleven fields.
for key in vars(T):
    if key == "__module__":
        continue
    row = vars(T)[key]
    print("row", key, type(row).__name__, repr(row.__doc__))

for name in ("__repr__", "__next__", "__init__"):
    print("doc", name, repr(vars(T)[name].__doc__))

# The eleven fields read off the real stream, through the descriptor rather than the value.
for name in ("encoding", "line_buffering", "write_through", "_finalizing",
             "name", "closed", "newlines", "errors", "_CHUNK_SIZE", "buffer"):
    row = vars(T)[name]
    print("field", name, type(row).__name__, repr(row.__get__(sys.stdin)))

# A read-only member says so by the field alone; the bool member has its own two sentences.
show("encoding write", lambda: setattr(sys.stdin, "encoding", "x"))
show("name delete", lambda: delattr(sys.stdin, "name"))
show("buffer delete", lambda: delattr(sys.stdin, "buffer"))
show("finalizing int", lambda: setattr(sys.stdin, "_finalizing", 1))
show("finalizing delete", lambda: delattr(sys.stdin, "_finalizing"))
show("chunk delete", lambda: delattr(sys.stdin, "_CHUNK_SIZE"))
sys.stdin._finalizing = True
print("finalizing true", sys.stdin._finalizing)
sys.stdin._finalizing = False
print("finalizing false", sys.stdin._finalizing)

# The five parameters: what is parsed, what is refused, and what each one leaves behind.
show("reconfigure none", lambda: sys.stdin.reconfigure())
show("reconfigure positional", lambda: sys.stdin.reconfigure("utf-8"))
show("reconfigure unknown", lambda: sys.stdin.reconfigure(nope=1))
show("reconfigure newline bad", lambda: sys.stdin.reconfigure(newline="x"))
show("reconfigure newline empty", lambda: sys.stdin.reconfigure(newline=""))
show("reconfigure newline lf", lambda: sys.stdin.reconfigure(newline="\n"))
show("reconfigure bad encoding", lambda: sys.stdin.reconfigure(encoding="nope"))
show("reconfigure encoding", lambda: sys.stdin.reconfigure(encoding="LATIN-1"))
print("encoding", sys.stdin.encoding)
show("reconfigure errors", lambda: sys.stdin.reconfigure(errors="strict"))
print("errors", sys.stdin.errors)
show("reconfigure lb none", lambda: sys.stdin.reconfigure(line_buffering=None))
print("lb", sys.stdin.line_buffering)
show("reconfigure lb one", lambda: sys.stdin.reconfigure(line_buffering=1))
print("lb", sys.stdin.line_buffering)
show("reconfigure wt", lambda: sys.stdin.reconfigure(write_through=1))
print("wt", sys.stdin.write_through)
show("reconfigure encoding int", lambda: sys.stdin.reconfigure(encoding=1))
show("reconfigure errors none", lambda: sys.stdin.reconfigure(errors=None))
print("errors", sys.stdin.errors)

# The initializer: two refusals, one accepted re-initialization, then the int that leaves it unset.
show("init none", lambda: T.__init__(sys.stdin))
show("init real", lambda: T.__init__(sys.stdin, sys.stdin.buffer))
show("init kw", lambda: T.__init__(sys.stdin, sys.stdin.buffer, x=1))

# Handing the buffer over is what makes every later row the detached sentence.
print("detach", repr(sys.stdin.detach()))
show("reconfigure detached", lambda: sys.stdin.reconfigure())
show("flush detached", lambda: sys.stdin.flush())
show("buffer detached", lambda: sys.stdin.buffer)

show("init bad", lambda: T.__init__(sys.stdin, 1))
