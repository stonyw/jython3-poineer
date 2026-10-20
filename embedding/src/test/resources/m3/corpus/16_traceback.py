# The frame chain a traceback walks, and the three objects it is made of.
import sys


def chain(error):
    """(name, line, lasti) for every entry of an exception's traceback, outermost first."""
    entries = []
    tb = error.__traceback__
    while tb is not None:
        entries.append((tb.tb_frame.f_code.co_name, tb.tb_lineno, tb.tb_lasti))
        tb = tb.tb_next
    return entries


def frames(error):
    """The activation each entry names, outermost first."""
    out = []
    tb = error.__traceback__
    while tb is not None:
        out.append(tb.tb_frame)
        tb = tb.tb_next
    return out


def descend(depth, message):
    if depth:
        return descend(depth - 1, message)
    raise ValueError(message)


print("--- an entry for every frame the error left")
try:
    descend(2, "deep")
except ValueError as e:
    print("chain:", chain(e))
    print("types:", type(e.__traceback__).__name__, type(e.__traceback__.tb_frame).__name__)
    print("dir:", e.__traceback__.__dir__())
    print("kinds:", [type(v).__name__ for v in
                     (e.__traceback__.tb_lineno, e.__traceback__.tb_lasti,
                      e.__traceback__.tb_next)])
    print("at the end:", e.__traceback__.tb_next.tb_next.tb_next is None)
    print("exc_info:", sys.exc_info()[2] is e.__traceback__)
    head = frames(e)[0]
    print("module frame:", head.f_code.co_name, head.f_locals is head.f_globals,
          type(head.f_builtins).__name__, head.f_back is None, head.f_generator)
    print("back link:", frames(e)[1].f_back is head,
          frames(e)[2].f_back.f_code.co_name)
    print("one object:", e.__traceback__.tb_frame is e.__traceback__.tb_frame)
    print("live:", head.f_lineno > 0, head.f_lasti >= 0, head.f_trace is None,
          head.f_trace_lines, head.f_trace_opcodes)
    print("code:", head.f_code.co_name, head.f_code.co_qualname,
          head.f_code.co_argcount, head.f_code.co_varnames, head.f_code.co_flags)
    print("locals of the deepest:", sorted(frames(e)[2].f_locals),
          frames(e)[2].f_code.co_name)
    dead = frames(e)[2]
    dead.clear()
    print("cleared:", sorted(dead.f_locals), dead.f_lineno, len(dead.f_globals) > 0,
          dead.f_code.co_name, chain(e))
    try:
        head.clear()
    except RuntimeError as bad:
        print("clear live:", type(bad).__name__, bad)

print("--- a bare raise repeats, an explicit raise adds this frame")
try:
    descend(0, "again")
except ValueError as e:
    before = chain(e)
    try:
        raise
    except ValueError as repeated:
        print("bare:", len(before), len(chain(repeated)), repeated is e, chain(repeated))
    try:
        raise e
    except ValueError as again:
        after = frames(again)
        print("explicit:", len(before), len(chain(again)))
        print("same frame twice:", after[0] is after[1],
              [f.f_code.co_name for f in after])


print("--- a raise from a helper names the call, not the raise site alone")
def outer(n):
    return inner(n)


def inner(n):
    return 100 // n


for n in (0, 4):
    try:
        outer(n)
    except ZeroDivisionError as e:
        print("divide:", chain(e))
    else:
        print("divide:", 100 // n)

print("--- a handler's own frame is entered where it called")
def guarded(flag):
    try:
        descend(1 if flag else 0, "guarded")
    except ValueError as e:
        return chain(e)


print("caught:", guarded(True))
print("caught:", guarded(False))

print("--- the traceback is the exception's own, and it can be replaced")
def raise_one(message):
    raise KeyError(message)


fresh = KeyError("handmade")
print("none:", fresh.__traceback__, chain(fresh))
try:
    raise_one("source")
except KeyError as source:
    kept = source.__traceback__
    print("with:", type(fresh.with_traceback(kept)).__name__, chain(fresh.with_traceback(kept)))
    print("returns self:", fresh.with_traceback(kept) is fresh)
    for bad in (5, "x", fresh):
        try:
            fresh.__traceback__ = bad
        except TypeError as why:
            print("field:", type(why).__name__, why)
    fresh.__traceback__ = None
    print("reset:", fresh.__traceback__, chain(fresh))
    for bad in (5, "x", kept):
        try:
            kept.tb_next = bad
        except (TypeError, ValueError) as why:
            print("next:", type(why).__name__, why, len(chain(source)))
    print("tail kept:", len(chain(source)))
    for name in ("tb_frame", "tb_lasti", "tb_lineno", "__dir__", "nonsense"):
        try:
            setattr(kept, name, 1)
        except AttributeError as why:
            print(name, "->", type(why).__name__, why)
        try:
            delattr(kept, name)
        except (AttributeError, TypeError) as why:
            print(name, "del ->", type(why).__name__, why)
    kept.tb_next = None
    print("truncated:", chain(source))

print("--- a frame answers only the names it has")
def surface():
    try:
        raise_one("surface")
    except KeyError as here:
        frame = here.__traceback__.tb_frame
        print("names:", frame.f_code.co_name, frame.f_locals is frame.f_globals,
              sorted(frame.f_locals), frame.f_back.f_code.co_name)
        for name in ("f_back", "f_code", "f_globals", "f_locals", "f_generator", "f_lasti"):
            try:
                setattr(frame, name, 1)
            except AttributeError as why:
                print(name, "->", type(why).__name__, why)
            try:
                delattr(frame, name)
            except AttributeError as why:
                print(name, "del ->", type(why).__name__, why)
        for name in ("f_lineno", "f_trace_lines", "f_trace_opcodes"):
            try:
                setattr(frame, name, 1)
            except (ValueError, TypeError) as why:
                print(name, "->", type(why).__name__, why)
        try:
            frame.f_lineno = "seven"
        except ValueError as why:
            print("lineno type:", type(why).__name__, why)
        for name in ("f_lineno", "f_trace_opcodes", "nonsense"):
            try:
                delattr(frame, name)
            except AttributeError as why:
                print("del", name, "->", type(why).__name__, why)
        try:
            del frame.f_trace_lines
        except TypeError as why:
            print("del f_trace_lines:", type(why).__name__, why)
        try:
            frame.clear = 1
        except AttributeError as why:
            print("clear ->", type(why).__name__, why)
        frame.f_trace = 42
        frame.f_trace_lines = False
        frame.f_trace_opcodes = True
        print("trace:", frame.f_trace, frame.f_trace_lines, frame.f_trace_opcodes)
        del frame.f_trace
        print("trace gone:", frame.f_trace)
        try:
            frame.nonsense = 1
        except AttributeError as why:
            print("no dict:", type(why).__name__, why)
        try:
            frame.nonsense
        except AttributeError as why:
            print("missing:", type(why).__name__, why)
        return here.__traceback__.tb_frame.f_code


code = surface()
print("--- a code object publishes its own shape")
print("counts:", code.co_nlocals, code.co_stacksize, code.co_posonlyargcount,
      code.co_kwonlyargcount, code.co_firstlineno, code.co_cellvars, code.co_freevars)
print("names:", code.co_names[:3], len(code.co_names), len(code.co_consts))
print("consts:", [type(c).__name__ for c in code.co_consts])
print("file:", code.co_filename.split("/")[-1], type(code).__name__)
print("tables:", len(code.co_code), len(code.co_linetable), len(code.co_exceptiontable))
print("after the handler:", sys.exc_info()[2], chain(fresh))

for name in ("co_name", "co_consts", "co_varnames", "co_code", "co_lnotab", "co_lines",
             "replace", "_co_code_adaptive", "nonsense"):
    try:
        setattr(code, name, 1)
    except AttributeError as why:
        print(name, "->", type(why).__name__, why)


def with_closure(captured):
    def reads_it():
        return captured
    return reads_it


inner_co = with_closure(3).__code__
print("closure:", inner_co.co_freevars, inner_co.co_varnames, inner_co.co_nlocals,
      with_closure.__code__.co_cellvars, with_closure.__code__.co_flags)
print("qualnames:", with_closure.__qualname__, inner_co.co_qualname, inner_co.co_name)
print("generator flag:", (lambda: (yield)).__code__.co_flags & 0x20 != 0)

print("--- what __exit__ is handed")
class Watch:
    def __enter__(self):
        return self

    def __exit__(self, kind, value, tb):
        print("exit:", kind if kind is None else kind.__name__,
              value is None, type(tb).__name__ if tb is not None else tb,
              () if value is None else chain(value))
        return True


with Watch():
    pass
with Watch():
    descend(1, "inside with")

print("--- outside a handler there is no exception to look at")
print("exc_info:", sys.exc_info())
print("done")
