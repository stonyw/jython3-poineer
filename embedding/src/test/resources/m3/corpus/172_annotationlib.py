# The rest of `annotationlib`: the metatype `Format` answers through, `ForwardRef` and its `evaluate`,
# `call_evaluate_function`, `eval_str`, and the eight names `__all__` lists.

import annotationlib
from annotationlib import (
    Format,
    ForwardRef,
    call_annotate_function,
    call_evaluate_function,
    get_annotations,
)

print("== __all__ ==")
print(annotationlib.__all__)

print("== Format metatype ==")
print(list(Format))
print([member.name for member in Format], len(Format))
print(Format["VALUE"], Format["STRING"], Format.__members__["FORWARDREF"])
print(Format.__members__)
print(1 in Format, 3 in Format, 4 in Format, "VALUE" in Format, 9 in Format, "9" in Format)

for key in ["NOPE", 0, 5, "1"]:
    try:
        Format[key]
    except KeyError as error:
        print(type(error).__name__, error)

try:
    Format["VALUE"] = 1
except TypeError as error:
    print(type(error).__name__, error)

try:
    del Format["VALUE"]
except TypeError as error:
    print(type(error).__name__, error)

print(vars(Format.VALUE))
print(Format.VALUE._name_, Format.VALUE._value_, Format.VALUE._sort_order_)
print(Format.VALUE.__objclass__ is Format)

print("== ForwardRef ==")
reference = ForwardRef("Foo")
print(reference)
print(repr(reference))
print(reference.__forward_arg__, reference.__forward_module__, reference.__owner__)
print(reference.__forward_is_argument__, reference.__forward_is_class__)
print(reference.__globals__, reference.__weakref__, reference.__cell__)
print(reference.__slots__)
print(reference == ForwardRef("Foo"), reference == ForwardRef("Bar"), reference == 3)
print(hash(reference) == hash(ForwardRef("Foo")))
print(ForwardRef("Foo") == ForwardRef("Foo", is_argument=False))
print(ForwardRef("Foo") == ForwardRef("Foo", module="m"))
print(ForwardRef("Foo", module="m") == ForwardRef("Foo", module="m"))
print(ForwardRef("Foo", is_class=True) == ForwardRef("Foo"))
print(repr(ForwardRef("Foo", module="m", is_class=True)))
print(repr(ForwardRef("Foo", owner=int)))
print(ForwardRef("Foo", is_class=True).__forward_is_class__)

for argument in [42, None, b"Foo"]:
    try:
        ForwardRef(argument)
    except TypeError as error:
        print(type(error).__name__, error)

print("== ForwardRef.evaluate ==")
print(ForwardRef("int").evaluate(), ForwardRef("int").evaluate() is int)
print(ForwardRef("int").evaluate(format=Format.STRING))
fresh = ForwardRef("Foo")
print(fresh.evaluate(format=Format.FORWARDREF) is fresh)
print(fresh.evaluate(format=Format.FORWARDREF))
print(ForwardRef("X").evaluate(globals={"X": list}) is list)
print(ForwardRef("X").evaluate(globals={}, locals={"X": dict}) is dict)
print(ForwardRef("Format", module="annotationlib").evaluate() is Format)

try:
    ForwardRef("Nope").evaluate()
except NameError as error:
    print(type(error).__name__, error)

try:
    ForwardRef("1+").evaluate()
except SyntaxError as error:
    print(type(error).__name__, error)

try:
    ForwardRef("int").evaluate(format=Format.VALUE_WITH_FAKE_GLOBALS)
except NotImplementedError as error:
    print(type(error).__name__, error)

print("== call_evaluate_function ==")


def every_format(format):
    return {"format": format}


print(call_evaluate_function(every_format, Format.VALUE))
print(call_evaluate_function(every_format, Format.STRING))
print(call_evaluate_function(every_format, Format.FORWARDREF))


def only_value(format):
    if format is not Format.VALUE:
        raise NotImplementedError
    return {"a": int, "b": "C"}


print(call_evaluate_function(only_value, Format.STRING))
print(call_evaluate_function(only_value, Format.FORWARDREF))

try:
    call_evaluate_function(only_value)
except TypeError as error:
    print(type(error).__name__, error)

try:
    get_annotations()
except TypeError as error:
    print(type(error).__name__, error)

print("== eval_str ==")


class Named:
    pass


def plain(x: "Named", y: "int", z: int, w: "list") -> "dict":
    pass


resolved = get_annotations(plain, eval_str=True)
print(
    resolved["x"] is Named,
    resolved["y"] is int,
    resolved["z"] is int,
    resolved["w"] is list,
    resolved["return"] is dict,
)
print(get_annotations(plain, eval_str=True, format=Format.VALUE)["x"] is Named)
print(get_annotations(plain)["x"])


class Holder:
    field: "Named"
    other: "int"


holder = get_annotations(Holder, eval_str=True)
print(holder["field"] is Named, holder["other"] is int)


def need(x: "Undefined"):
    pass


print(get_annotations(need, globals={"Undefined": list}, locals={}, eval_str=True)["x"] is list)

try:
    get_annotations(need, eval_str=True)
except NameError as error:
    print(type(error).__name__, error)
