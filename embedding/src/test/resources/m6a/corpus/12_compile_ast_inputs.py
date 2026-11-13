"""compile() from an AST object, as CPython 3.14 answers it.

Every line this prints is a decision the reference makes: which root a mode accepts,
what a missing field is called, which fields a normalize pass fills in, and what
optimize folds.  The runtime does not decide any of it -- it writes the tree out and
asks the reference -- so this file is the evidence that the two agree byte for byte.
"""

import sys

# The reference interpreter's own library, exactly as the other corpora do it, so both sides read
# the same `ast.py` over the same `_ast` module.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import ast


def report(label, action):
    try:
        result = action()
    except BaseException as error:
        print(label, "ERR", type(error).__name__)
        for line in str(error).splitlines():
            print("   ", line)
        return
    if isinstance(result, ast.AST):
        print(label, "TREE", ast.dump(result))
    else:
        print(label, "CODE", result.co_consts, result.co_names,
              result.co_code.hex(), result.co_flags)


# --- the four modes, each with the root it wants ---------------------------

module = ast.parse("x = 1 + 2\n")
report("exec", lambda: compile(module, "<t>", "exec"))
report("eval", lambda: compile(ast.parse("1 + 2", mode="eval"), "<t>", "eval"))
report("single", lambda: compile(ast.parse("1 + 2", mode="single"), "<t>", "single"))
report("func-type", lambda: compile(ast.parse("(int, str) -> bool", mode="func_type"),
                                   "<t>", "func_type"))
report("module-in-eval", lambda: compile(module, "<t>", "eval"))
report("expr-in-exec", lambda: compile(ast.parse("1", mode="eval"), "<t>", "exec"))
report("module-in-single", lambda: compile(module, "<t>", "single"))
report("interactive-in-exec", lambda: compile(ast.parse("1", mode="single"), "<t>", "exec"))
report("func-type-in-exec", lambda: compile(
    ast.parse("(int) -> bool", mode="func_type"), "<t>", "exec"))

# --- AST in, tree out ------------------------------------------------------

report("only-ast", lambda: compile(module, "<t>", "exec", flags=ast.PyCF_ONLY_AST))
report("only-ast-eval", lambda: compile(
    ast.parse("1", mode="eval"), "<t>", "eval", flags=ast.PyCF_ONLY_AST))
report("optimized-ast", lambda: compile(module, "<t>", "exec",
                                        flags=ast.PyCF_OPTIMIZED_AST))
report("only-ast-src", lambda: compile("x = 1", "<t>", "exec", flags=ast.PyCF_ONLY_AST))

# The normalize pass fills in every field the caller left out.
bare = ast.Module()
bare.body = [ast.Expr(value=ast.Constant(value=7))]
report("only-ast-bare", lambda: compile(bare, "<t>", "exec", flags=ast.PyCF_ONLY_AST))

# --- optimize --------------------------------------------------------------

branched = "if __debug__:\n    y = 1\nelse:\n    y = 2\n"
report("optimize-src-0", lambda: compile(branched, "<t>", "exec", optimize=0))
report("optimize-src-1", lambda: compile(branched, "<t>", "exec", optimize=1))
report("optimize-src-2", lambda: compile(branched, "<t>", "exec", optimize=2))
report("optimize-parse-1", lambda: ast.parse(branched, optimize=1))
branched_tree = ast.parse(branched)
report("optimize-ast-0", lambda: compile(branched_tree, "<t>", "exec", optimize=0))
report("optimize-ast-1", lambda: compile(branched_tree, "<t>", "exec", optimize=1))
report("optimize-ast-1-tree", lambda: compile(branched_tree, "<t>", "exec",
                                              flags=ast.PyCF_ONLY_AST, optimize=1))

# --- what a refused tree is told ------------------------------------------

report("module-no-body", lambda: compile(ast.Module(), "<t>", "exec"))

no_lineno = ast.Module(body=[ast.Expr(value=ast.Constant(value=3))], type_ignores=[])
report("missing-lineno", lambda: compile(no_lineno, "<t>", "exec"))

no_position = ast.Expression(body=ast.Constant(value=5))
report("eval-missing-lineno", lambda: compile(no_position, "<t>", "eval"))

bad_constant = ast.Module(
    body=[ast.Expr(value=ast.Constant(value=object()))], type_ignores=[])
report("bad-constant", lambda: compile(bad_constant, "<t>", "exec"))

bad_lineno = ast.Module(
    body=[ast.Expr(value=ast.Constant(value=1, lineno="x", col_offset=0))],
    type_ignores=[])
report("bad-lineno-type", lambda: compile(bad_lineno, "<t>", "exec"))

bad_id = ast.Module(body=[ast.Assign(
    targets=[ast.Name(id=7, ctx=ast.Store(), lineno=1, col_offset=0)],
    value=ast.Constant(value=1, lineno=1, col_offset=0),
    lineno=1, col_offset=0)], type_ignores=[])
report("bad-id", lambda: compile(bad_id, "<t>", "exec"))

bad_ctx = ast.Module(
    body=[ast.Expr(value=ast.Name(id="a", ctx=None, lineno=1, col_offset=0))],
    type_ignores=[])
report("bad-ctx", lambda: compile(bad_ctx, "<t>", "exec"))

bad_ops = ast.Module(body=[ast.Expr(value=ast.BinOp(
    left=ast.Constant(value=1, lineno=1, col_offset=0),
    op=None,
    right=ast.Constant(value=2, lineno=1, col_offset=0),
    lineno=1, col_offset=0))], type_ignores=[])
report("bad-op", lambda: compile(bad_ops, "<t>", "exec"))


class Fake:
    _fields = ("body",)


report("fake-fields", lambda: compile(Fake(), "<t>", "exec"))
report("plain-object", lambda: compile(object(), "<t>", "exec"))

# --- type_ignores and type comments ---------------------------------------

ignoring = ast.parse("x = 1\n")
ignoring.type_ignores = [ast.TypeIgnore(lineno=1, tag="type: ignore")]
report("type-ignores", lambda: compile(ignoring, "<t>", "exec"))

bad_tag = ast.parse("x = 1\n")
bad_tag.type_ignores = [ast.TypeIgnore(lineno=1, tag=5)]
report("type-ignores-bad-tag", lambda: compile(bad_tag, "<t>", "exec"))

commented = ast.parse("x = 1  # type: int\n")
report("type-comments-ast-off", lambda: compile(commented, "<t>", "exec"))
report("type-comments-ast-on", lambda: compile(commented, "<t>", "exec",
                                               flags=ast.PyCF_TYPE_COMMENTS))
report("type-comments-src-on", lambda: compile("x = 1  # type: int\n", "<t>", "exec",
                                               flags=ast.PyCF_TYPE_COMMENTS))
report("type-comments-src-tree", lambda: compile(
    "x = 1  # type: int\n", "<t>", "exec",
    flags=ast.PyCF_TYPE_COMMENTS | ast.PyCF_ONLY_AST))

bad_comment = ast.parse("x = 1\n")
bad_comment.body[0].type_comment = 42
report("type-comments-bad", lambda: compile(bad_comment, "<t>", "exec"))

incomplete = ast.parse("x = 1\n")
report("incomplete-ast", lambda: compile(incomplete, "<t>", "exec",
                                         flags=ast.PyCF_ALLOW_INCOMPLETE_INPUT))

# --- feature_version -------------------------------------------------------

report("feature-version-3", lambda: compile(ast.parse("1"), "<t>", "exec",
                                            _feature_version=3))
report("feature-version-14-tree", lambda: compile(ast.parse("1"), "<t>", "exec",
                                                  _feature_version=(3, 14),
                                                  flags=ast.PyCF_ONLY_AST))

# --- values that cross the seam -------------------------------------------

payload = ast.Module(body=[ast.Expr(value=ast.Tuple(elts=[
    ast.Constant(value="text"),
    ast.Constant(value=b"bytes"),
    ast.Constant(value=1.5),
    ast.Constant(value=1e999),
    ast.Constant(value=-0.0),
    ast.Constant(value=complex(1, -2)),
    ast.Constant(value=frozenset({1, 2})),
    ast.Constant(value=(None, True, Ellipsis)),
    ast.Constant(value=None, kind="u"),
], ctx=ast.Load(), lineno=1, col_offset=0))], type_ignores=[])
report("values", lambda: compile(payload, "<t>", "exec"))
report("values-tree", lambda: compile(payload, "<t>", "exec", flags=ast.PyCF_ONLY_AST))

# --- a subclass the reference does not know --------------------------------

class SmallName(ast.Name):
    _fields = ("id", "ctx")


subclassed = ast.Module(body=[ast.Expr(value=SmallName(
    id="value", ctx=ast.Load(), lineno=1, col_offset=0))], type_ignores=[])
report("subclass-name", lambda: compile(subclassed, "<t>", "exec"))


def run_code(code):
    scope = {"value": 41}
    exec(code, scope)
    return scope.get("out")


# --- the text round trip ---------------------------------------------------

def stable(constants):
    """A constant tuple with the code objects made address-free."""
    return [("<code>", value.co_name) if hasattr(value, "co_code") else value
            for value in constants]


source = "def f(a, /, b=1, *args, c, **kw):\n    return a\n"
parsed = ast.parse(source)
from_text = compile(source, "<t>", "exec", flags=ast.PyCF_ONLY_AST)
print("roundtrip-dump-equal", ast.dump(parsed) == ast.dump(from_text))
print("roundtrip-compile-tree", stable(compile(parsed, "<t>", "exec").co_consts))
print("roundtrip-compile-text", stable(compile(from_text, "<t>", "exec").co_consts))

summed = ast.parse("out = value + 1\n")
print("run-tree", run_code(compile(summed, "<t>", "exec")))
print("run-text", run_code(compile("out = value + 1\n", "<t>", "exec")))

carried = compile(summed, "<t>", "exec", flags=ast.PyCF_ONLY_AST)
print("run-rebuilt", run_code(compile(carried, "<t>", "exec")))
