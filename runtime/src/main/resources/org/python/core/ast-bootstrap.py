"""The `_ast` node types, written in Python so the runtime builds them with its own class machinery.

CPython builds these in C from a table in Python/Python-ast.c; the table is the same shape here and
the values were transcribed from CPython 3.14.10, so `_fields`, `_attributes` and the per-field
`_field_types` answer what the reference answers. `ast.dump` reads all three: `_fields` for the
constructor it prints, `_attributes` for `include_attributes=True`, and `_field_types` to tell a
list-valued field from the rest, because an empty list is omitted unless `show_empty=True`.
"""


class AST:
    _fields = ()
    _attributes = ()
    _field_types = {}

    def __init__(self, *args, **kwargs):
        fields = type(self)._fields
        if len(args) > len(fields):
            raise TypeError("%s constructor takes at most %d positional argument%s" % (
                type(self).__name__, len(fields), "" if len(fields) == 1 else "s"))
        for name, value in zip(fields, args):
            setattr(self, name, value)
        for name, value in kwargs.items():
            setattr(self, name, value)

    def __repr__(self):
        parts = []
        for name in type(self)._fields:
            try:
                value = getattr(self, name)
            except AttributeError:
                continue
            parts.append("%s=%r" % (name, value))
        return "%s(%s)" % (type(self).__name__, ", ".join(parts))


class _ListField:
    """What `_field_types` holds for a list-valued field: only `__origin__` is ever read."""

    __origin__ = list


class alias(AST):
    _fields = ('name', 'asname')
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')
    _field_types = {'name': object, 'asname': object}
    asname = None


class arg(AST):
    _fields = ('arg', 'annotation', 'type_comment')
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')
    _field_types = {'arg': object, 'annotation': object, 'type_comment': object}
    annotation = None
    type_comment = None


class arguments(AST):
    _fields = ('posonlyargs', 'args', 'vararg', 'kwonlyargs', 'kw_defaults', 'kwarg', 'defaults')
    _attributes = ()
    _field_types = {'posonlyargs': _ListField, 'args': _ListField, 'vararg': object, 'kwonlyargs': _ListField, 'kw_defaults': _ListField, 'kwarg': object, 'defaults': _ListField}
    vararg = None
    kwarg = None


class boolop(AST):
    _fields = ()
    _attributes = ()


class cmpop(AST):
    _fields = ()
    _attributes = ()


class comprehension(AST):
    _fields = ('target', 'iter', 'ifs', 'is_async')
    _attributes = ()
    _field_types = {'target': object, 'iter': object, 'ifs': _ListField, 'is_async': object}


class excepthandler(AST):
    _fields = ()
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')


class expr(AST):
    _fields = ()
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')


class expr_context(AST):
    _fields = ()
    _attributes = ()


class keyword(AST):
    _fields = ('arg', 'value')
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')
    _field_types = {'arg': object, 'value': object}
    arg = None


class match_case(AST):
    _fields = ('pattern', 'guard', 'body')
    _attributes = ()
    _field_types = {'pattern': object, 'guard': object, 'body': _ListField}
    guard = None


class mod(AST):
    _fields = ()
    _attributes = ()


class operator(AST):
    _fields = ()
    _attributes = ()


class pattern(AST):
    _fields = ()
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')


class stmt(AST):
    _fields = ()
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')


class type_ignore(AST):
    _fields = ()
    _attributes = ()


class type_param(AST):
    _fields = ()
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')


class unaryop(AST):
    _fields = ()
    _attributes = ()


class withitem(AST):
    _fields = ('context_expr', 'optional_vars')
    _attributes = ()
    _field_types = {'context_expr': object, 'optional_vars': object}
    optional_vars = None


class Add(operator):
    _fields = ()


class And(boolop):
    _fields = ()


class AnnAssign(stmt):
    _fields = ('target', 'annotation', 'value', 'simple')
    _field_types = {'target': object, 'annotation': object, 'value': object, 'simple': object}
    value = None


class Assert(stmt):
    _fields = ('test', 'msg')
    _field_types = {'test': object, 'msg': object}
    msg = None


class Assign(stmt):
    _fields = ('targets', 'value', 'type_comment')
    _field_types = {'targets': _ListField, 'value': object, 'type_comment': object}
    type_comment = None


class AsyncFor(stmt):
    _fields = ('target', 'iter', 'body', 'orelse', 'type_comment')
    _field_types = {'target': object, 'iter': object, 'body': _ListField, 'orelse': _ListField, 'type_comment': object}
    type_comment = None


class AsyncFunctionDef(stmt):
    _fields = ('name', 'args', 'body', 'decorator_list', 'returns', 'type_comment', 'type_params')
    _field_types = {'name': object, 'args': object, 'body': _ListField, 'decorator_list': _ListField, 'returns': object, 'type_comment': object, 'type_params': _ListField}
    returns = None
    type_comment = None


class AsyncWith(stmt):
    _fields = ('items', 'body', 'type_comment')
    _field_types = {'items': _ListField, 'body': _ListField, 'type_comment': object}
    type_comment = None


class Attribute(expr):
    _fields = ('value', 'attr', 'ctx')
    _field_types = {'value': object, 'attr': object, 'ctx': object}


class AugAssign(stmt):
    _fields = ('target', 'op', 'value')
    _field_types = {'target': object, 'op': object, 'value': object}


class Await(expr):
    _fields = ('value',)
    _field_types = {'value': object}


class BinOp(expr):
    _fields = ('left', 'op', 'right')
    _field_types = {'left': object, 'op': object, 'right': object}


class BitAnd(operator):
    _fields = ()


class BitOr(operator):
    _fields = ()


class BitXor(operator):
    _fields = ()


class BoolOp(expr):
    _fields = ('op', 'values')
    _field_types = {'op': object, 'values': _ListField}


class Break(stmt):
    _fields = ()


class Call(expr):
    _fields = ('func', 'args', 'keywords')
    _field_types = {'func': object, 'args': _ListField, 'keywords': _ListField}


class ClassDef(stmt):
    _fields = ('name', 'bases', 'keywords', 'body', 'decorator_list', 'type_params')
    _field_types = {'name': object, 'bases': _ListField, 'keywords': _ListField, 'body': _ListField, 'decorator_list': _ListField, 'type_params': _ListField}


class Compare(expr):
    _fields = ('left', 'ops', 'comparators')
    _field_types = {'left': object, 'ops': _ListField, 'comparators': _ListField}


class Constant(expr):
    _fields = ('value', 'kind')
    _field_types = {'value': object, 'kind': object}
    kind = None


class Continue(stmt):
    _fields = ()


class Del(expr_context):
    _fields = ()


class Delete(stmt):
    _fields = ('targets',)
    _field_types = {'targets': _ListField}


class Dict(expr):
    _fields = ('keys', 'values')
    _field_types = {'keys': _ListField, 'values': _ListField}


class DictComp(expr):
    _fields = ('key', 'value', 'generators')
    _field_types = {'key': object, 'value': object, 'generators': _ListField}


class Div(operator):
    _fields = ()


class Eq(cmpop):
    _fields = ()


class ExceptHandler(excepthandler):
    _fields = ('type', 'name', 'body')
    _field_types = {'type': object, 'name': object, 'body': _ListField}
    type = None
    name = None


class Expr(stmt):
    _fields = ('value',)
    _field_types = {'value': object}


class Expression(mod):
    _fields = ('body',)
    _field_types = {'body': object}


class FloorDiv(operator):
    _fields = ()


class For(stmt):
    _fields = ('target', 'iter', 'body', 'orelse', 'type_comment')
    _field_types = {'target': object, 'iter': object, 'body': _ListField, 'orelse': _ListField, 'type_comment': object}
    type_comment = None


class FormattedValue(expr):
    _fields = ('value', 'conversion', 'format_spec')
    _field_types = {'value': object, 'conversion': object, 'format_spec': object}
    format_spec = None


class FunctionDef(stmt):
    _fields = ('name', 'args', 'body', 'decorator_list', 'returns', 'type_comment', 'type_params')
    _field_types = {'name': object, 'args': object, 'body': _ListField, 'decorator_list': _ListField, 'returns': object, 'type_comment': object, 'type_params': _ListField}
    returns = None
    type_comment = None


class FunctionType(mod):
    _fields = ('argtypes', 'returns')
    _field_types = {'argtypes': _ListField, 'returns': object}


class GeneratorExp(expr):
    _fields = ('elt', 'generators')
    _field_types = {'elt': object, 'generators': _ListField}


class Global(stmt):
    _fields = ('names',)
    _field_types = {'names': _ListField}


class Gt(cmpop):
    _fields = ()


class GtE(cmpop):
    _fields = ()


class If(stmt):
    _fields = ('test', 'body', 'orelse')
    _field_types = {'test': object, 'body': _ListField, 'orelse': _ListField}


class IfExp(expr):
    _fields = ('test', 'body', 'orelse')
    _field_types = {'test': object, 'body': object, 'orelse': object}


class Import(stmt):
    _fields = ('names',)
    _field_types = {'names': _ListField}


class ImportFrom(stmt):
    _fields = ('module', 'names', 'level')
    _field_types = {'module': object, 'names': _ListField, 'level': object}
    module = None
    level = None


class In(cmpop):
    _fields = ()


class Interactive(mod):
    _fields = ('body',)
    _field_types = {'body': _ListField}


class Interpolation(expr):
    _fields = ('value', 'str', 'conversion', 'format_spec')
    _field_types = {'value': object, 'str': object, 'conversion': object, 'format_spec': object}
    format_spec = None


class Invert(unaryop):
    _fields = ()


class Is(cmpop):
    _fields = ()


class IsNot(cmpop):
    _fields = ()


class JoinedStr(expr):
    _fields = ('values',)
    _field_types = {'values': _ListField}


class LShift(operator):
    _fields = ()


class Lambda(expr):
    _fields = ('args', 'body')
    _field_types = {'args': object, 'body': object}


class List(expr):
    _fields = ('elts', 'ctx')
    _field_types = {'elts': _ListField, 'ctx': object}


class ListComp(expr):
    _fields = ('elt', 'generators')
    _field_types = {'elt': object, 'generators': _ListField}


class Load(expr_context):
    _fields = ()


class Lt(cmpop):
    _fields = ()


class LtE(cmpop):
    _fields = ()


class MatMult(operator):
    _fields = ()


class Match(stmt):
    _fields = ('subject', 'cases')
    _field_types = {'subject': object, 'cases': _ListField}


class MatchAs(pattern):
    _fields = ('pattern', 'name')
    _field_types = {'pattern': object, 'name': object}
    pattern = None
    name = None


class MatchClass(pattern):
    _fields = ('cls', 'patterns', 'kwd_attrs', 'kwd_patterns')
    _field_types = {'cls': object, 'patterns': _ListField, 'kwd_attrs': _ListField, 'kwd_patterns': _ListField}


class MatchMapping(pattern):
    _fields = ('keys', 'patterns', 'rest')
    _field_types = {'keys': _ListField, 'patterns': _ListField, 'rest': object}
    rest = None


class MatchOr(pattern):
    _fields = ('patterns',)
    _field_types = {'patterns': _ListField}


class MatchSequence(pattern):
    _fields = ('patterns',)
    _field_types = {'patterns': _ListField}


class MatchSingleton(pattern):
    _fields = ('value',)
    _field_types = {'value': object}


class MatchStar(pattern):
    _fields = ('name',)
    _field_types = {'name': object}
    name = None


class MatchValue(pattern):
    _fields = ('value',)
    _field_types = {'value': object}


class Mod(operator):
    _fields = ()


class Module(mod):
    _fields = ('body', 'type_ignores')
    _field_types = {'body': _ListField, 'type_ignores': _ListField}


class Mult(operator):
    _fields = ()


class Name(expr):
    _fields = ('id', 'ctx')
    _field_types = {'id': object, 'ctx': object}


class NamedExpr(expr):
    _fields = ('target', 'value')
    _field_types = {'target': object, 'value': object}


class Nonlocal(stmt):
    _fields = ('names',)
    _field_types = {'names': _ListField}


class Not(unaryop):
    _fields = ()


class NotEq(cmpop):
    _fields = ()


class NotIn(cmpop):
    _fields = ()


class Or(boolop):
    _fields = ()


class ParamSpec(type_param):
    _fields = ('name', 'default_value')
    _field_types = {'name': object, 'default_value': object}
    default_value = None


class Pass(stmt):
    _fields = ()


class Pow(operator):
    _fields = ()


class RShift(operator):
    _fields = ()


class Raise(stmt):
    _fields = ('exc', 'cause')
    _field_types = {'exc': object, 'cause': object}
    exc = None
    cause = None


class Return(stmt):
    _fields = ('value',)
    _field_types = {'value': object}
    value = None


class Set(expr):
    _fields = ('elts',)
    _field_types = {'elts': _ListField}


class SetComp(expr):
    _fields = ('elt', 'generators')
    _field_types = {'elt': object, 'generators': _ListField}


class Slice(expr):
    _fields = ('lower', 'upper', 'step')
    _field_types = {'lower': object, 'upper': object, 'step': object}
    lower = None
    upper = None
    step = None


class Starred(expr):
    _fields = ('value', 'ctx')
    _field_types = {'value': object, 'ctx': object}


class Store(expr_context):
    _fields = ()


class Sub(operator):
    _fields = ()


class Subscript(expr):
    _fields = ('value', 'slice', 'ctx')
    _field_types = {'value': object, 'slice': object, 'ctx': object}


class TemplateStr(expr):
    _fields = ('values',)
    _field_types = {'values': _ListField}


class Try(stmt):
    _fields = ('body', 'handlers', 'orelse', 'finalbody')
    _field_types = {'body': _ListField, 'handlers': _ListField, 'orelse': _ListField, 'finalbody': _ListField}


class TryStar(stmt):
    _fields = ('body', 'handlers', 'orelse', 'finalbody')
    _field_types = {'body': _ListField, 'handlers': _ListField, 'orelse': _ListField, 'finalbody': _ListField}


class Tuple(expr):
    _fields = ('elts', 'ctx')
    _field_types = {'elts': _ListField, 'ctx': object}


class TypeAlias(stmt):
    _fields = ('name', 'type_params', 'value')
    _field_types = {'name': object, 'type_params': _ListField, 'value': object}


class TypeIgnore(type_ignore):
    _fields = ('lineno', 'tag')
    _field_types = {'lineno': object, 'tag': object}


class TypeVar(type_param):
    _fields = ('name', 'bound', 'default_value')
    _field_types = {'name': object, 'bound': object, 'default_value': object}
    bound = None
    default_value = None


class TypeVarTuple(type_param):
    _fields = ('name', 'default_value')
    _field_types = {'name': object, 'default_value': object}
    default_value = None


class UAdd(unaryop):
    _fields = ()


class USub(unaryop):
    _fields = ()


class UnaryOp(expr):
    _fields = ('op', 'operand')
    _field_types = {'op': object, 'operand': object}


class While(stmt):
    _fields = ('test', 'body', 'orelse')
    _field_types = {'test': object, 'body': _ListField, 'orelse': _ListField}


class With(stmt):
    _fields = ('items', 'body', 'type_comment')
    _field_types = {'items': _ListField, 'body': _ListField, 'type_comment': object}
    type_comment = None


class Yield(expr):
    _fields = ('value',)
    _field_types = {'value': object}
    value = None


class YieldFrom(expr):
    _fields = ('value',)
    _field_types = {'value': object}


_node_types = (AST, alias, arg, arguments, boolop, cmpop, comprehension, excepthandler, expr, expr_context, keyword, match_case, mod, operator, pattern, stmt, type_ignore, type_param, unaryop, withitem, Add, And, AnnAssign, Assert, Assign, AsyncFor, AsyncFunctionDef, AsyncWith, Attribute, AugAssign, Await, BinOp, BitAnd, BitOr, BitXor, BoolOp, Break, Call, ClassDef, Compare, Constant, Continue, Del, Delete, Dict, DictComp, Div, Eq, ExceptHandler, Expr, Expression, FloorDiv, For, FormattedValue, FunctionDef, FunctionType, GeneratorExp, Global, Gt, GtE, If, IfExp, Import, ImportFrom, In, Interactive, Interpolation, Invert, Is, IsNot, JoinedStr, LShift, Lambda, List, ListComp, Load, Lt, LtE, MatMult, Match, MatchAs, MatchClass, MatchMapping, MatchOr, MatchSequence, MatchSingleton, MatchStar, MatchValue, Mod, Module, Mult, Name, NamedExpr, Nonlocal, Not, NotEq, NotIn, Or, ParamSpec, Pass, Pow, RShift, Raise, Return, Set, SetComp, Slice, Starred, Store, Sub, Subscript, TemplateStr, Try, TryStar, Tuple, TypeAlias, TypeIgnore, TypeVar, TypeVarTuple, UAdd, USub, UnaryOp, While, With, Yield, YieldFrom,)
for _node_type in _node_types:
    _node_type.__match_args__ = _node_type._fields
    _node_type.__module__ = "ast"
del _node_type

PyCF_ALLOW_TOP_LEVEL_AWAIT = 8192
PyCF_ONLY_AST = 1024
PyCF_OPTIMIZED_AST = 33792
PyCF_TYPE_COMMENTS = 4096
