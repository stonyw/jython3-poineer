"""What M5 slice 1 owes: `_ast` as a module the reference `ast.py` builds on.

The reference `ast.py` is one line of import — `from _ast import *` — and then its own Python. So once
the node classes are here, `ast.parse`, `ast.dump`, `ast.walk` and `ast.NodeVisitor` are the reference's
own code paths, and what this script measures is the seam rather than the parser: the node classes'
`_fields` and `_attributes`, a tree from each statement form `ast.parse` takes, and the dumps `ast.dump`
writes for them. The reference interpreter prints the same lines byte for byte.

Two names are absent on purpose. `ast.unparse` imports `enum` and `contextlib`, and
`ast.get_docstring` imports `inspect`, which reaches `dis` and `_opcode`; this runtime answers none of
those imports yet, so both stay named as M5 work. A third, the `SyntaxError` a bad source raises, is
missing from the `_ast` side: a refusal from the compiler is not yet turned into a catchable node here.
"""
import sys

sys.path.insert(
    0,
    "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
    "Versions/3.14/lib/python3.14",
)

from _ast import *
import ast

# The class surface: what `from _ast import *` hands `ast.py`, and what `ast.dump` reads back.
print("bases", AST._fields, AST._attributes, expr._fields, stmt._fields)
print("attributes", expr._attributes, stmt._attributes, pattern._attributes, type_param._attributes)
print("fields", Name._fields, Name._attributes, Name.__match_args__)
print("fields2", FunctionDef._fields, ClassDef._fields, arguments._fields)
print("fields3", Constant._fields, MatchMapping._fields, TypeVar._fields)
print("modules", AST.__module__, Name.__module__, FunctionDef.__module__)
print("flags", PyCF_ONLY_AST, PyCF_TYPE_COMMENTS, PyCF_ALLOW_TOP_LEVEL_AWAIT,
      PyCF_OPTIMIZED_AST)
print("repr", repr(Name("x", Load())))
print("repr2", repr(Constant(1)))
print("vars", sorted(vars(Constant(1))))

CASES = [
    "",
    "x = 1\n",
    "a = b = f(1, k=2, *args, **kw)\n",
    "del x[0]\n",
    "def f(a, b=1, /, c=2, *args, d, e=3, **kw) -> int:\n    return a\n",
    "@decorator(1)\nclass C(Base, metaclass=M):\n    '''doc'''\n    x: int = 0\n\n"
    "    def m(self):\n        pass\n",
    "if a:\n    x = 1\nelif b:\n    x = 2\nelse:\n    x = 3\n",
    "for i in range(3):\n    continue\nelse:\n    pass\n",
    "while x:\n    break\n",
    "import os, sys as s\nfrom .pkg import name as alias\nfrom .. import other\n",
    "try:\n    f()\nexcept ValueError as e:\n    g(e)\nexcept Exception:\n    raise\n"
    "else:\n    h()\nfinally:\n    i()\n",
    "with a() as x, b():\n    pass\n",
    "async def g():\n    await h()\n    async for i in xs:\n        pass\n    async with c():\n"
    "        pass\n",
    "lambda x, *ys, k=1, **kw: x\n",
    "[i for i in xs if i]\n{k: v for k, v in items}\n{f(i) for i in xs}\n",
    "g = (x for x in xs)\n",
    "s = f'{a!r:>{w}}'\n",
    "1 + 2 * 3 ** -4 // 5 % 6 @ m\nnot (a and b) or c\n",
    "x = y if a else z\n",
    "x[1:2:3]\ny.z\n",
    "def f():\n    global q\n    q = 1\n",
    "match v:\n    case [1, 2]:\n        pass\n    case {'k': w} if w:\n        pass\n"
    "    case Point(x=0) | None:\n        pass\n    case _:\n        pass\n",
    "def f[T](x: T) -> T:\n    return x\n",
    "type X = int\n",
    "def f():\n    yield 1\n    yield from g()\n",
]

for source in CASES:
    tree = ast.parse(source)
    print("--")
    print(repr(source))
    print(ast.dump(tree))
    print(ast.dump(tree, include_attributes=True))
    print(ast.dump(tree, annotate_fields=False))
    print(ast.dump(tree, indent=2, show_empty=True))

print("== modes ==")
print(ast.dump(ast.parse("1 + 2", mode="eval")))
print(ast.dump(ast.parse("x = 1", mode="single")))
print(ast.dump(ast.parse("(int, str) -> bool", mode="func_type")))

print("== literal_eval ==")
for text in ["1", "-1", "1+2j", "[1, {'a': (2, None, True)}]", "'s'", "b'b'", "...", "set()",
             "-0.5"]:
    print(text, repr(ast.literal_eval(text)))
try:
    ast.literal_eval("f(1)")
except ValueError as error:
    print("literal-error", str(error))

print("== walking ==")
tree = ast.parse("x = f(1) + g(y)")
print([type(node).__name__ for node in ast.walk(tree)])


class Gather(ast.NodeVisitor):

    def __init__(self):
        self.names = []

    def visit_Name(self, node):
        self.names.append(node.id)


gather = Gather()
gather.visit(tree)
print(gather.names)
print([(name, type(value).__name__) for name, value in ast.iter_fields(tree.body[0])])
print([type(node).__name__ for node in ast.iter_child_nodes(tree.body[0])])

print("== positions ==")
bare = ast.fix_missing_locations(ast.Name("x", ast.Load()))
print(ast.dump(bare, include_attributes=True))
print(ast.dump(ast.copy_location(ast.Name("y", ast.Load()), tree.body[0].value),
               include_attributes=True))
ast.increment_lineno(tree, 10)
print(ast.dump(tree, include_attributes=True))
