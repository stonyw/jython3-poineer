# The compile() surface: modes, flags, optimize, dont_inherit and single's echo.
import sys

# The reference interpreter's own library, exactly as the other corpora do it, so both sides read
# the same `ast.py`, `__future__.py` and `subprocess.py`.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import __future__
import ast
import subprocess

# 1. exec mode: the top level answers None and writes names into the namespace it was given.
namespace = {}
exec_code = compile("total = 1 + 2\n", "<exec>", "exec")
print("exec:", exec(exec_code, namespace), namespace["total"], exec_code.co_name)

# 2. eval mode: one expression, and its value is the code object's answer.
print("eval:", eval(compile("6 * 7", "<eval>", "eval")))

# 3. single mode: a value expression is echoed through displayhook, statements are not, and None is
#    the one value the echo skips.
exec(compile("'echoed'", "<single>", "single"))
exec(compile("hushed = 5", "<single>", "single"))
exec(compile("None", "<single>", "single"))
print("single:", hushed)

# 4. each mode refuses the shape it does not take.
for mode, text in [("eval", "x = 1"), ("exec", "1 +"), ("single", "1\n2")]:
    try:
        compile(text, "<mode>", mode)
    except SyntaxError as error:
        print("mode:", mode, type(error).__name__, error.msg)

# 5. the flags the parser understands, and the tree PyCF_ONLY_AST answers instead of code.
print("flags:", ast.PyCF_ONLY_AST, ast.PyCF_TYPE_COMMENTS, ast.PyCF_ALLOW_TOP_LEVEL_AWAIT,
      ast.PyCF_OPTIMIZED_AST)
tree = compile("a * b", "<flags>", "eval", flags=ast.PyCF_ONLY_AST)
print("ast:", type(tree).__name__, tree.body.left.id, tree.body.right.id)

# 6. func_type is a parser mode: it needs the flag and has no code form.
try:
    compile("(int, str)", "<func>", "func_type")
except ValueError as error:
    print("func_type:", error)
print("func_type ast:", type(compile("(int, str) -> bool", "<func>", "func_type",
                                    flags=ast.PyCF_ONLY_AST)).__name__)

# 7. an unrecognised flag bit is refused by number, not guessed at.
for bad in (8, 1 << 20):
    try:
        compile("pass", "<bad>", "exec", flags=bad)
    except ValueError as error:
        print("bad flag:", bad, error)

# 8. optimize is visible in three places: docstrings, asserts and __debug__.
source = '''\
def f():
    """doc"""
    assert False, "boom"
    return __debug__
'''
for level in (0, 1, 2):
    namespace = {}
    exec(compile(source, "<opt>", "exec", optimize=level), namespace)
    try:
        outcome = namespace["f"]()
    except AssertionError as error:
        outcome = "AssertionError:" + str(error)
    print("opt:", level, namespace["f"].__doc__, outcome)

# 9. dont_inherit decides whether the caller's __future__ bits are carried over. The calling frame
#    is a unit compiled with the annotations bit, so the two calls differ only in that argument.
future_flag = __future__.annotations.compiler_flag
outer = ("inside = compile('value: int', '<future>', 'exec')\n"
         "outside = compile('value: int', '<future>', 'exec', dont_inherit=True)\n"
         "print('future:', %d, bool(inside.co_flags & %d), bool(outside.co_flags & %d))\n"
         % (future_flag, future_flag, future_flag))
exec(compile(outer, "<future>", "exec", flags=future_flag))

# 10. bytes go through the PEP 263 encoding declaration; str is already text.
for text in (b"# coding: latin-1\nname = '\xe9'\n", memoryview(b"name = 'view'\n"),
             bytearray(b"name = 'array'\n")):
    namespace = {}
    exec(compile(text, "<bytes>", "exec"), namespace)
    print("bytes:", namespace["name"], len(namespace["name"]))

# 11. a refusal names the file and the position it stopped at.
try:
    compile("def broken(:\n    pass\n", "/tmp/example.py", "exec")
except SyntaxError as error:
    print("syntax:", error.filename, error.lineno, error.offset, type(error).__name__)
try:
    compile("a\x00b", "<nul>", "exec")
except SyntaxError as error:
    print("nul:", type(error).__name__, error.msg, len(error.args))

# 12. -O and -OO reach a child process, and the child folds the same three differences.
child = ("import sys\n"
         "def f():\n"
         "    'doc'\n"
         "assert False, 'kept'\n"
         "print(f.__doc__, __debug__, sys.flags.optimize)\n")
for switch in ("-O", "-OO"):
    finished = subprocess.run([sys.executable, switch, "-c", child],
                              capture_output=True)
    print("child:", switch, finished.returncode, finished.stdout.decode().split())
