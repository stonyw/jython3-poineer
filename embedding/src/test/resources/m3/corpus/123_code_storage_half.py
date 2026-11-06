"""The twenty storage rows of `code`'s book, and the two refusals they own.

`code` is the type that publishes a compiled function's whole body as attributes, and CPython files thirty-
seven rows in its book: one `__new__`, eight wrapper descriptors, seven method descriptors, fourteen
`PyMemberDef`s, six getsets and a plain `__doc__` string. This case reads the twenty of those that are
*storage* — the fourteen fields and the six computed tables — and the two position methods that were already
filed, because the fifteen names a program cannot write are refused by the row it filed rather than by a
sentence the runtime invents.

Two refusals, and which one a name gets is decided by the table it came from. A member with no writing half
is `PyMemberDescriptor`'s own complaint, `readonly attribute`, four syllables and no name in them; a getset
whose `gd_set` is `NULL` names the attribute, the type and the fact: `attribute 'co_code' of 'code' objects is
not writable`. Both arrive for a `del` as well, since CPython hands the same pointer nothing. Read off the
class every one of those twenty rows is the row in the book — `code.co_argcount is vars(code)['co_argcount']`
— because a field asked of a type answers itself, and only a value gets the number underneath.

The two knives cut `code.__doc__` differently from the other welded types, and this case is where that shows:
the clinic text for `code` opens at the bracket with no name above it, which CPython's `find_signature`
tolerates, so `__doc__` is the sentence and `__text_signature__` the table. `type(v).major` for a field is a
`member_descriptor` whose `__qualname__` is `code.co_argcount` and whose `__objclass__` is `code` itself.

Nothing here prints a byte table's contents except `co_code`, whose instruction stream both interpreters
write the same way. `co_linetable` is not counted either: the reference compiles the script with `-X
no_debug_ranges` and this one does not, so its table is written in shorter forms and disagrees in size as
well as in bytes. `co_lnotab` is not read at all: the reference warns about the name on every read and this
runtime has no warning machinery, so its stderr can never match — the pairs that name are pinned in
`LegacyLineTableTest` instead. `_co_code_adaptive` is asked only as a row, never as a value, because the tail
of the reference's answer is the memory of a specializing interpreter this one does not have.

`vars(code)` is not counted and `dir(code)` is not printed whole: this runtime files twenty-three of the
thirty-seven rows, so those two counts are a difference of record rather than of measurement. The names
below are asked one at a time, which is how a program asks them anyway.
"""


def fn(a, b=1, *args, c, **kw):
    return a


def gen(n):
    yield n


def outer():
    total = 0

    def inner():
        return total

    return inner


class K:
    def method(self):
        return K


lam = lambda q: q
CODE = fn.__code__
KIND = type(CODE)

MEMBERS = [
    "co_argcount", "co_posonlyargcount", "co_kwonlyargcount", "co_stacksize", "co_flags",
    "co_nlocals", "co_consts", "co_names", "co_filename", "co_name", "co_qualname",
    "co_firstlineno", "co_linetable", "co_exceptiontable",
]
GETSETS = [
    "co_lnotab", "_co_code_adaptive", "co_varnames", "co_cellvars", "co_freevars", "co_code",
]
METHODS = ["co_lines", "co_positions"]


def told(action):
    try:
        return "ok " + repr(action())
    except BaseException as error:
        return "%s: %s" % (type(error).__name__, error)


def row_of(kind, name):
    """The row the book files under the name, without walking anywhere for it."""
    return vars(kind)[name]


def describe(name):
    row = row_of(KIND, name)
    return "  %-18s %-18s %-24s %-8s %-6s %s" % (
        name, type(row).__name__, row.__qualname__, row.__objclass__ is KIND,
        row.__doc__, "__text_signature__" in dir(row))


print("== the fourteen fields, as PyMemberDef rows")
for name in MEMBERS:
    print(describe(name))
print("== the six computed tables, as getset rows")
for name in GETSETS:
    print(describe(name))
print("== the two position reads, as method rows")
for name in METHODS:
    print(describe(name))
print("  co_lines  __name__ / __doc__: %s / %r" % (
    row_of(KIND, "co_lines").__name__, row_of(KIND, "co_lines").__doc__))
print("  co_positions __name__ / __doc__: %s / %r" % (
    row_of(KIND, "co_positions").__name__, row_of(KIND, "co_positions").__doc__))

print()
print("== the doc row is a string, not a descriptor")
doc_row = row_of(KIND, "__doc__")
print("  %s | %s | %s" % (type(doc_row).__name__, doc_row == KIND.__doc__, repr(doc_row)))
print("  and the first field is the row itself read off the class: %s"
      % (KIND.co_argcount is row_of(KIND, "co_argcount")))

print()
print("== one row of each kind, as it reprs")
for name in ["co_argcount", "co_consts", "co_code", "co_lnotab", "co_lines"]:
    print("  %-14s %s" % (name, repr(row_of(KIND, name))))

print()
print("== the twelve rows a value answers with a number or a name")
for name in MEMBERS[:12]:
    print("  %-20s %r" % (name, getattr(CODE, name)))
print("== the four that answer with a tuple of names")
for name in ["co_varnames", "co_cellvars", "co_freevars", "co_names"]:
    print("  %-20s %r" % (name, getattr(CODE, name)))
print("== and the two that answer with a tuple of values")
print("  co_consts            %r" % (CODE.co_consts,))

print()
print("== the same rows read through six different code objects")
for other in [fn.__code__, gen.__code__, outer.__code__, outer().__code__,
              K.__dict__["method"].__code__, lam.__code__]:
    print("  %-14s %-16s %s %s %s %s %s %s %s" % (
        other.co_name, other.co_qualname, other.co_argcount, other.co_posonlyargcount,
        other.co_kwonlyargcount, other.co_nlocals, other.co_stacksize, other.co_flags,
        other.co_firstlineno))
    print("      %r | %r | %r" % (
        other.co_varnames, other.co_cellvars, other.co_freevars))
    # A nested code object's repr carries its address, so the constants are named by kind.
    print("      consts %s" % ([type(c).__name__ for c in other.co_consts],))

print()
print("== the instruction stream counted, and the exception table beside it")
for other in [fn.__code__, gen.__code__, outer().__code__, lam.__code__]:
    print("  %-24s code %s exceptiontable %s" % (
        other.co_qualname, len(other.co_code), len(other.co_exceptiontable)))
print("  fn co_code: %s" % (fn.__code__.co_code.hex(),))

print()
print("== a write to a field: the row's own sentence, twice over")
for name in MEMBERS:
    print("  %-20s set %-52s del %s" % (
        name, told(lambda n=name: setattr(CODE, n, 1)), told(lambda n=name: delattr(CODE, n))))
print("== a write to a computed table: the field named, and the type with it")
for name in GETSETS:
    print("  %-20s set %-52s del %s" % (
        name, told(lambda n=name: setattr(CODE, n, 1)), told(lambda n=name: delattr(CODE, n))))

print()
print("== the same two refusals, reached as descriptors rather than as names")
for name in ["co_argcount", "co_consts", "co_code", "co_lnotab"]:
    row = row_of(KIND, name)
    print("  %-14s __set__ %-52s __delete__ %s" % (
        name, told(lambda r=row: r.__set__(CODE, 1)), told(lambda r=row: r.__delete__(CODE))))

print()
print("== the receiver is asked before the missing writer is noticed")
for name in ["co_argcount", "co_code", "co_lines"]:
    row = row_of(KIND, name)
    print("  %-14s __get__(7)  %-58s" % (name, told(lambda r=row: r.__get__(7))))
    print("  %-14s __set__(7, 1) %s" % (name, told(lambda r=row: r.__set__(7, 1))))

print()
print("== a method row is read-only because it has no writing half at all")
for name in METHODS:
    print("  %-14s set %-52s del %s" % (
        name, told(lambda n=name: setattr(CODE, n, 1)), told(lambda n=name: delattr(CODE, n))))
print("  and the method reached through the type: %s"
      % told(lambda: row_of(KIND, "co_lines").__set__(CODE, 1)))

print()
print("== a class cannot set its own field")
for name in ["co_argcount", "co_code", "co_lines", "__doc__"]:
    print("  %-14s %s" % (name, told(lambda n=name: setattr(KIND, n, 1))))
print("  %-14s %s" % ("co_argcount", told(lambda: delattr(KIND, "co_argcount"))))

print()
print("== the two knives over the welded table")
print("  __doc__: %r" % (KIND.__doc__,))
print("  __text_signature__: %r" % (KIND.__text_signature__,))
print("  the row in the book agrees with the read: %s" % (row_of(KIND, "__doc__") == KIND.__doc__,))

print()
print("== a field is not a method, and neither is a name nothing files")
print("  %-14s %s" % ("nonsense", told(lambda: setattr(CODE, "nonsense", 1))))
print("  %-14s %s" % ("co_branches", told(lambda: setattr(CODE, "co_branches", 1))))
print("  %-14s %s" % ("replace", told(lambda: setattr(CODE, "replace", 1))))
print("  %-14s %s" % ("__sizeof__", told(lambda: setattr(CODE, "__sizeof__", 1))))
print("  %-14s %s" % ("nonsense", told(lambda: CODE.nonsense)))

print()
print("done")
