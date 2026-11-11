# PEP 649's other half: `annotationlib` names the four formats, reads annotations in each of them,
# and lets a caller run an annotate function by hand. `Format` is an `IntEnum`, so its members are
# also their integers; nothing here prints a function's repr, which carries an address.

import annotationlib
from annotationlib import (
    Format,
    annotations_to_string,
    call_annotate_function,
    get_annotate_from_class_namespace,
    get_annotations,
    type_repr,
)

print("== Format ==")
print(Format)
print(Format.VALUE, repr(Format.VALUE))
print(Format.VALUE.name, Format.VALUE.value)
print(Format.VALUE == 1, hash(Format.VALUE) == hash(1), {1: "one"}[Format.VALUE])
print(Format.VALUE + 1, isinstance(Format.VALUE, int), int(Format.STRING))
print(Format.VALUE.STRING is Format.STRING, Format(4) is Format.STRING)
print(Format(True) is Format.VALUE, Format(1.0) is Format.VALUE, Format(1, module="m") is Format.VALUE)
print(Format.__name__, Format.__doc__)
print(Format.__module__, type_repr(Format))

for arguments in [(), (0,), (2.5,), ("1",), (None,), (1, 2), (1, 2, 3)]:
    try:
        Format(*arguments)
    except Exception as error:
        print(type(error).__name__, error)

for keywords in [dict(value=2), dict(names="X")]:
    try:
        Format(1, **keywords)
    except Exception as error:
        print(type(error).__name__, error)

try:
    Format(1, value=2)
except Exception as error:
    print(type(error).__name__, error)

try:
    Format(x=1)
except Exception as error:
    print(type(error).__name__, error)

member = Format.STRING
member.extra = 5
print(member.extra)
del member.extra
try:
    print(member.extra)
except AttributeError as error:
    print("extra gone:", error)

for name in ["name", "value"]:
    try:
        setattr(member, name, 1)
    except AttributeError as error:
        print(type(error).__name__, error)
    try:
        delattr(member, name)
    except AttributeError as error:
        print(type(error).__name__, error)

print("== get_annotations ==")


class C:
    pass


def f(x: int, y: "C", *args: float, **kw: bool) -> list[int]:
    pass


print(get_annotations(f))
print(get_annotations(f, format=Format.VALUE) == f.__annotations__)
print(get_annotations(f, format=Format.FORWARDREF) == f.__annotations__)
print(get_annotations(f, format=Format.STRING))
print(get_annotations(f) is f.__annotations__)
print(get_annotations(obj=f, format=Format.VALUE) == f.__annotations__)


def g():
    pass


print(get_annotations(g), get_annotations(g, format=Format.STRING))
print(get_annotations(len), get_annotations(int))

for value in [3, "x", None]:
    try:
        get_annotations(value)
    except TypeError as error:
        print(type(error).__name__, error)

for value in [0, None, "x"]:
    try:
        get_annotations(f, format=value)
    except ValueError as error:
        print(type(error).__name__, error)

try:
    get_annotations(f, format=Format.VALUE_WITH_FAKE_GLOBALS)
except ValueError as error:
    print(type(error).__name__, error)

try:
    get_annotations(f, format=Format.STRING, eval_str=True)
except ValueError as error:
    print(type(error).__name__, error)

print("== call_annotate_function ==")


def every_format(format):
    return {"format": format}


print(call_annotate_function(every_format, Format.VALUE))
print(call_annotate_function(every_format, 0))


def only_value(format):
    if format is not Format.VALUE:
        raise NotImplementedError
    return {"a": int}


print(call_annotate_function(only_value, Format.VALUE))
print(call_annotate_function(only_value, Format.STRING))
print(call_annotate_function(only_value, Format.FORWARDREF))


def refuses(format):
    raise NotImplementedError


try:
    call_annotate_function(refuses, Format.VALUE)
except RuntimeError as error:
    print(type(error).__name__, error)

try:
    call_annotate_function(refuses, 7)
except ValueError as error:
    print(type(error).__name__, error)

try:
    call_annotate_function(every_format, Format.VALUE_WITH_FAKE_GLOBALS)
except ValueError as error:
    print(type(error).__name__, error)

print(call_annotate_function(lambda format: 7, Format.VALUE))

print("== type_repr and annotations_to_string ==")
print(type_repr(int), type_repr(len), type_repr(list[int]))
print(type_repr("C"), type_repr(3), type_repr(None), type_repr(Ellipsis))


class Outer:
    class Inner:
        pass


print(type_repr(Outer.Inner))
print(annotations_to_string({"a": int, "b": "C", "c": 3, "d": None, "e": Ellipsis}))

try:
    annotations_to_string(3)
except AttributeError as error:
    print(type(error).__name__, error)

print("== get_annotate_from_class_namespace ==")
print(get_annotate_from_class_namespace({"__annotate__": 5}))
print(get_annotate_from_class_namespace({"__annotate_func__": 6}))
print(get_annotate_from_class_namespace({}))
print(get_annotate_from_class_namespace({"__annotate__": None, "__annotate_func__": 6}))

try:
    get_annotate_from_class_namespace(1)
except TypeError as error:
    print(type(error).__name__, error)

print("== the module itself ==")
print(annotationlib.__doc__)
