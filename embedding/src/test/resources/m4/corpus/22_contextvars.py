# Context variables. `warnings` is the first stdlib module in this tree that has to keep per-call
# state outside the call: `_py_warnings` creates a context variable at module scope, so `_contextvars`
# has to answer before `import warnings` can finish. What follows is the surface a program uses --
# a variable with and without a default, the token `set` hands back, a context a callable runs in,
# and the copy `copy_context` makes -- and then the import itself.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import re
import _contextvars
import contextvars
import warnings


def clean(value):
    return re.sub(" at 0x[0-9a-f]+", "", repr(value))


def message(error):
    return re.sub(" at 0x[0-9a-f]+", "", str(error))


def dump(context):
    return sorted((variable.name, value) for variable, value in context.items())


print("modules", _contextvars.__name__, _contextvars.__doc__, contextvars.__name__,
      contextvars.__doc__)
print("all", contextvars.__all__)
print("shared", contextvars.ContextVar is _contextvars.ContextVar,
      contextvars.Token is _contextvars.Token,
      contextvars.Context is _contextvars.Context,
      contextvars.copy_context is _contextvars.copy_context)

# --- the variable --------------------------------------------------------------------------


v = contextvars.ContextVar("v")
d = contextvars.ContextVar("d", default=5)
items = contextvars.ContextVar("l", default=[1, 2])

print("type", type(v).__name__, type(v).__module__, v.name, d.name)
print("repr", clean(v), clean(d), clean(items))
print("defaults", v.get("none"), d.get(), items.get() is items.get())
print("missing", clean(contextvars.Token.MISSING), type(contextvars.Token.MISSING).__name__,
      type(contextvars.Token.MISSING).__module__)

# --- set, get, reset -----------------------------------------------------------------------


token = v.set(1)
print("set", v.get(), token.var is v, token.old_value is token.MISSING, clean(token))
second = v.set(2)
print("second", v.get(), second.old_value)
v.reset(second)
print("restored", v.get())
v.reset(token)
print("unset", v.get("none"), clean(token))

try:
    v.reset(token)
except RuntimeError as error:
    print("reused", type(error).__name__, message(error))

w = contextvars.ContextVar("w")
token = v.set(3)
try:
    w.reset(token)
except ValueError as error:
    print("wrong var", type(error).__name__, message(error))
v.reset(token)

try:
    v.get()
except LookupError as error:
    print("lookup", type(error).__name__, message(error))

# --- contexts ------------------------------------------------------------------------------


ctx = contextvars.Context()
print("empty", len(ctx), dump(ctx), ctx.get(v), ctx.get(v, "fallback"), v in ctx,
      list(ctx.keys()), list(ctx.values()))


def body(value, offset=0):
    v.set(value)
    return v.get() + offset


print("run", ctx.run(body, 7, offset=1), dump(ctx), v.get("none"))
print("read", ctx.get(v), ctx[v], len(ctx), v in ctx, w in ctx, ctx.get(w))

ctx.run(w.set, "W")
print("mapping", dump(ctx), sorted(key.name for key in ctx.keys()),
      sorted(repr(value) for value in ctx.values()), len(ctx), ctx.get(w))


def reenter():
    ctx.run(lambda: None)


try:
    ctx.run(reenter)
except RuntimeError as error:
    print("nested", type(error).__name__, message(error))
print("after", dump(ctx))

copy = ctx.copy()
print("copy", copy is ctx, type(copy).__name__, dump(copy), v.get("none"))
copy.run(v.set, 99)
print("independent", copy.get(v), ctx.get(v), v.get("none"))

# --- copy_context --------------------------------------------------------------------------


holder = contextvars.ContextVar("holder")
before = holder.set("before")
snapshot = contextvars.copy_context()
after = holder.set("after")
print("snapshot", snapshot.get(holder), holder.get(), type(snapshot).__name__)
holder.reset(after)
print("restored", holder.get())
snapshot.run(holder.set, "inside")
print("snapshot run", snapshot.get(holder), holder.get())
holder.reset(before)
print("done", holder.get("none"))

foreign = contextvars.Context()
foreign.run(v.set, "foreign")
crossed = foreign.run(lambda: v.set("marked"))
try:
    v.reset(crossed)
except ValueError as error:
    print("cross context", type(error).__name__, message(error))

# --- refusals ------------------------------------------------------------------------------


print("--- constructors")
for label, call in [
    ("no name", lambda: contextvars.ContextVar()),
    ("default only", lambda: contextvars.ContextVar(default=1)),
    ("two positional", lambda: contextvars.ContextVar("x", 1)),
    ("three", lambda: contextvars.ContextVar("x", 1, default=2)),
    ("not a str", lambda: contextvars.ContextVar(1)),
    ("extra keyword", lambda: contextvars.ContextVar("x", other=1)),
    ("context args", lambda: contextvars.Context("x")),
    ("token", lambda: contextvars.Token()),
]:
    try:
        call()
    except BaseException as error:
        print(label, type(error).__name__, error)

print("--- methods")
for label, call in [
    ("get keyword", lambda: v.get(default=9)),
    ("get two", lambda: v.get(1, 2)),
    ("set zero", lambda: v.set()),
    ("set two", lambda: v.set(1, 2)),
    ("set keyword", lambda: v.set(value=1)),
    ("reset zero", lambda: v.reset()),
    ("reset two", lambda: v.reset(1, 2)),
    ("reset int", lambda: v.reset(1)),
    ("context get zero", lambda: ctx.get()),
    ("context get three", lambda: ctx.get(v, 1, 2)),
    ("context get keyword", lambda: ctx.get(v, default=1)),
    ("context get str", lambda: ctx.get("x")),
    ("context item str", lambda: ctx["x"]),
    ("context contains str", lambda: "x" in ctx),
    ("copy argument", lambda: ctx.copy(1)),
    ("copy keyword", lambda: ctx.copy(x=1)),
    ("copy_context argument", lambda: contextvars.copy_context(1)),
    ("copy_context keyword", lambda: contextvars.copy_context(x=1)),
]:
    try:
        call()
    except BaseException as error:
        print(label, type(error).__name__, error)

# --- the import this slice is for -----------------------------------------------------------


print("--- warnings")
print("imported", warnings.__name__, warnings.__all__[:3], len(warnings.filters))
print("context",
      isinstance(warnings._warnings_context, contextvars.ContextVar),
      warnings._use_context)
