"""What a slot answering {@code NotImplemented} means, and what the value itself means."""


def said(action):
    try:
        return "ok " + repr(action())
    except Exception as exc:
        return type(exc).__name__ + ": " + str(exc)


NI = NotImplemented


class GetItem:
    def __getitem__(self, key):
        return NI


class SetItem:
    def __setitem__(self, key, value):
        return NI

    def __delitem__(self, key):
        return NI


class Len:
    def __len__(self):
        return NI


class Iter:
    def __iter__(self):
        return NI


class Next:
    def __iter__(self):
        return self

    def __next__(self):
        return NI


class Bool:
    def __bool__(self):
        return NI


class Contains:
    def __contains__(self, item):
        return NI


class Call:
    def __call__(self):
        return NI


class Str:
    def __str__(self):
        return NI


class Repr:
    def __repr__(self):
        return NI


class Hash:
    def __hash__(self):
        return NI


class Eq:
    def __eq__(self, other):
        return NI


class Lt:
    def __lt__(self, other):
        return NI


class Add:
    def __add__(self, other):
        return NI


class RAdd:
    def __radd__(self, other):
        return "reflected"


class Attr:
    def __getattr__(self, name):
        return NI


def wrote(store):
    store[0] = 1
    return "nothing kept"


def deleted(store):
    del store[0]
    return "nothing kept"


print("== the value itself")
print("   truth", said(lambda: bool(NI)))
print("        ", said(lambda: not NI), said(lambda: NI and 1), said(lambda: [1] if NI else []))
print("   compare", said(lambda: NI == NI), said(lambda: NI is NI), said(lambda: NI != 1))
print("           ", said(lambda: NI < 1), said(lambda: NI in [NI]))
print("   display", said(lambda: str(NI)), said(lambda: repr(NI)), said(lambda: format(NI)))
print("           ", said(lambda: type(NI).__name__), said(lambda: repr([NI])), said(lambda: "%s" % NI))
print("   hashed", said(lambda: {NI: "v"}[NI]), said(lambda: repr({NI})), said(lambda: len({NI, 1})))

print("== the value stored in a container")
print("   list", said(lambda: [NI][0]), said(lambda: [NI][0:1]), said(lambda: list(reversed([NI]))))
print("   tuple", said(lambda: (NI,)[0]), said(lambda: (NI,)[:1]))
print("   dict", said(lambda: {"k": NI}["k"]), said(lambda: {"k": NI}.get("k")))
print("         ", said(lambda: {"k": NI}.pop("k")), said(lambda: repr({"k": NI})))
print("         ", said(lambda: list({"k": NI}.values())), said(lambda: list({"k": NI}.items())))
print("   fresh", said(lambda: {}.get("k", NI)), said(lambda: {}.setdefault("k", NI)))
print("   search", said(lambda: NI in {"k": NI}.values()), said(lambda: ("k", NI) in {"k": NI}.items()))

print("== a slot that answers with the value")
got = GetItem()
store = SetItem()
print("   getitem", said(lambda: got[0]), said(lambda: got[0:1]), said(lambda: got["k"]))
print("   assign", said(lambda: wrote(store)), said(lambda: deleted(store)))
print("   called", said(lambda: store.__setitem__(0, 1)), said(lambda: store.__delitem__(0)))
print("   len", said(lambda: Len().__len__()), said(lambda: len(Len())))
print("   iter", said(lambda: Iter().__iter__()), said(lambda: iter(Iter())), said(lambda: list(Iter())))
print("   next", said(lambda: next(Next())), said(lambda: Next().__next__()))
print("   bool", said(lambda: Bool().__bool__()), said(lambda: bool(Bool())))
print("   contains", said(lambda: Contains().__contains__(1)), said(lambda: 1 in Contains()))
print("   call", said(lambda: Call()()), said(lambda: Call.__call__(Call())))
print("   display", said(lambda: Str.__str__(Str())), said(lambda: str(Str())))
print("            ", said(lambda: repr(Repr())), said(lambda: [Repr()]), said(lambda: "%s" % Str()))
print("   hash", said(lambda: {Hash()}), said(lambda: {Hash: 1}))
print("   getattr", said(lambda: Attr().anything), said(lambda: hasattr(Attr(), "x")))
print("            ", said(lambda: getattr(Attr(), "x", 1)), said(lambda: "x" in dir(Attr())))

print("== the protocols that read the value as a declination")
print("   eq", said(lambda: Eq() == Eq()), said(lambda: Eq() == 1), said(lambda: Eq() != 1))
print("      ", said(lambda: Eq() == Add()))
print("   lt", said(lambda: Lt() < 1), said(lambda: Lt() > 1), said(lambda: sorted([Lt(), Lt()])))
print("   add", said(lambda: Add() + 1), said(lambda: 1 + Add()), said(lambda: Add() + Add()))
print("      ", said(lambda: 1 + RAdd()), said(lambda: RAdd() + 1))
print("   or", said(lambda: Eq() | Add()))
