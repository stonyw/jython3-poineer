"""What a class takes when one of its names is written, and the sentence each refusal comes in.

Writing ``Point.kind = 'point'`` reaches the class's own dictionary, and deleting it reaches the same
dictionary again — those are the two ordinary answers of ``type_setattro``. Everything else on a class is
answered before the dictionary is looked at, in a fixed order: first the metatype is asked whether it
files a member that writes, and if the member it files has a writing half that half runs instead of the
dictionary; only when it does not is the name filed. That order is why the special names of a class each
have their own refusal rather than a plain overwrite: ``__name__`` and ``__qualname__`` are getsets that
re-read the text handed to them, so a number arrives as ``type.__name__ must be set to a string object``
and a name with a null in it as ``type name must not contain null characters``; ``__mro__``, ``__dict__``
and ``__text_signature__`` are getsets with no writing half, so both a write and a delete of them answer
the same ``attribute '__dict__' of 'type' objects is not writable``; and ``__bases__`` is a getset that
checks the value is a tuple before anything else, which is why ``__bases__ = None`` names ``NoneType``
while ``__bases__ = [object]`` names ``list``.

Then the half that is not about the name at all. A delete of a special name goes through the same writing
half, so ``del C.__module__`` is refused as ``cannot delete '__module__' attribute of immutable type 'T'``
before the value is examined — and ``__basicsize__`` is a member rather than a getset, so its refusal is
the member's own ``readonly attribute``. A built-in type refuses every one of them with its own name in
the sentence, because the immutability check is the first thing ``type_setattro`` does. A class statement's
name, on the other hand, is a plain entry: ``del C.tag`` removes it, ``del C.tag`` a second time answers
``type object 'T' has no attribute 'tag'``, and a name that was never there answers the same sentence. What
never reaches any of this is a name that is not text: ``setattr(C, 5, 1)`` is refused as ``type object 'T'
has no attribute '5'``, because the refusal is looked up by name and there is no name to look up.
"""


def probe(label, thunk):
    try:
        return "%s -> %r" % (label, thunk())
    except BaseException as error:
        return "%s !! %s: %s" % (label, type(error).__name__, error)


def show(label, thunk):
    print(probe(label, thunk))


def fresh(name="T"):
    return type(name, (), {})


print("== the names a class re-reads before filing ==")
show("name=int", lambda: setattr(fresh(), "__name__", 5))
show("name=null", lambda: setattr(fresh(), "__name__", "a\x00b"))
show("qualname=int", lambda: setattr(fresh(), "__qualname__", 5))
renamed = fresh("Renamed")
renamed.__name__ = "Other"
show("renamed", lambda: (renamed.__name__, renamed.__qualname__, type(renamed).__name__))
show("del name", lambda: delattr(renamed, "__name__"))
show("del qualname", lambda: delattr(fresh(), "__qualname__"))
show("module=int", lambda: setattr(fresh(), "__module__", 5))
show("del module", lambda: delattr(fresh(), "__module__"))
show("doc=int", lambda: setattr(fresh(), "__doc__", 5))
show("del doc", lambda: delattr(fresh("Doced"), "__doc__"))

print("== the names a class files without reading ==")
show("type_params=int", lambda: setattr(fresh(), "__type_params__", 5))
show("del type_params", lambda: delattr(fresh(), "__type_params__"))
show("abstractmethods=int", lambda: setattr(fresh(), "__abstractmethods__", 5))
show("del abstractmethods", lambda: delattr(fresh(), "__abstractmethods__"))
show("annotations=int", lambda: setattr(fresh(), "__annotations__", 5))
show("del annotations", lambda: delattr(fresh(), "__annotations__"))
show("annotate=int", lambda: setattr(fresh(), "__annotate__", 5))
show("del annotate", lambda: delattr(fresh(), "__annotate__"))

print("== the names that refuse before the value is read ==")
show("bases=None", lambda: setattr(fresh(), "__bases__", None))
show("bases=list", lambda: setattr(fresh(), "__bases__", [object]))
show("del bases", lambda: delattr(fresh(), "__bases__"))
show("dict=set", lambda: setattr(fresh(), "__dict__", {}))
show("del dict", lambda: delattr(fresh(), "__dict__"))
show("mro=set", lambda: setattr(fresh(), "__mro__", ()))
show("del mro", lambda: delattr(fresh(), "__mro__"))
show("text_signature=set", lambda: setattr(fresh(), "__text_signature__", "(x)"))
show("del text_signature", lambda: delattr(fresh(), "__text_signature__"))
show("basicsize=set", lambda: setattr(fresh(), "__basicsize__", 1))
show("del basicsize", lambda: delattr(fresh(), "__basicsize__"))

print("== a name the class statement wrote is an entry in a dictionary ==")
tagged = fresh("Box")
tagged.tag = 1
show("tag", lambda: tagged.tag)
show("filed", lambda: "tag" in tagged.__dict__)
show("del tag", lambda: (delattr(tagged, "tag"), "tag" in tagged.__dict__))
show("del tag again", lambda: delattr(tagged, "tag"))
show("del missing", lambda: delattr(tagged, "nothere"))
show("set nonstring", lambda: setattr(tagged, 5, 1))
show("del nonstring", lambda: delattr(tagged, 5))

print("== a type the interpreter wrote takes nothing ==")
show("del int doc", lambda: delattr(int, "__doc__"))
show("int doc=set", lambda: setattr(int, "__doc__", "x"))
show("int name=set", lambda: setattr(int, "__name__", "x"))
show("del int name", lambda: delattr(int, "__name__"))
show("del int tag", lambda: delattr(int, "tag"))
show("int tag=set", lambda: setattr(int, "tag", 1))
show("object mro=set", lambda: setattr(object, "__mro__", ()))
show("object dict=set", lambda: setattr(object, "__dict__", {}))
