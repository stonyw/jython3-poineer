# M6-a slice 3: `copy` importing and doing its work. The module needs `weakref.ref` to be derivable
# before it can even load, and then it needs `object.__reduce_ex__` to describe an ordinary instance
# as a call plus its state. The script walks the containers the module dispatches itself, then the
# two hooks a class may write, then a plain instance both shallowly and deeply, and finally a value
# the recipe is deliberately refused for.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import copy
import weakref


print("imported", copy.__name__, copy.Error.__name__)
print("atomic", copy.copy(3), copy.copy("s"), copy.copy(b"b"), copy.copy((1, 2)))
print("frozen", copy.copy(frozenset({1, 2})) == frozenset({1, 2}))
try:
    copy.copy(copy)
except TypeError as error:
    print("module refusal", error)


class Holder:
    def __init__(self, value):
        self.value = value
        self.pair = [value, value]


class Custom:
    def __copy__(self):
        return ("copy", self.value)

    def __deepcopy__(self, memo):
        return ("deep", self.value, isinstance(memo, dict))


class Node:
    def __init__(self):
        self.name = "node"


holder = Holder(1)
shallow = copy.copy(holder)
deep = copy.deepcopy(holder)
print("shallow", type(shallow).__name__, shallow is not holder, shallow.value)
print("shallow shares", shallow.pair is holder.pair, shallow.pair == holder.pair)
print("deep", type(deep).__name__, deep is not holder, deep.value)
print("deep splits", deep.pair is not holder.pair, deep.pair == holder.pair)
print("getstate", holder.__getstate__())
print("empty state", Holder.__new__(Holder).__getstate__())

recipe = holder.__reduce_ex__(4)
print("recipe", len(recipe), recipe[0].__name__, recipe[1], recipe[3:])
zero = holder.__reduce_ex__(0)
print("recipe zero", len(zero), zero[0].__name__, zero[1], zero[2])

print("shallow hook", copy.copy(Custom()))
print("deep hook", copy.deepcopy(Custom()))

node = Node()
box = {"a": [node], "b": node, "n": 3}
nested = copy.deepcopy(box)
print("nested", nested["n"], nested["a"][0] is nested["b"], nested["a"][0] is not node)


class Thing:
    pass


class Reference(weakref.ref):
    pass


try:
    copy.copy(Reference(Thing()))
except TypeError as error:
    print("reference refusal", error)
