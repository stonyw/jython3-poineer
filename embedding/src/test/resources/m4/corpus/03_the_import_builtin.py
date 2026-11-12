# __import__ itself: the four parameters, the branch a relative name takes, and the module or
# head it answers with. The import statements compile to this same call.
import sys


def report(label, thunk):
    try:
        thunk()
    except Exception as exc:
        print(label, type(exc).__name__, exc)
    else:
        print(label, "no error")


# What the builtin says about itself.
print("type:", type(__import__).__name__)
print("name:", __import__.__name__)
print("module:", __import__.__module__)
print("self:", __import__.__self__.__name__)
print("signature:", __import__.__text_signature__.splitlines()[0])
print("doc:", __import__.__doc__.splitlines()[0])

# A plain name answers the module itself, and it is filed under that name.
helper = __import__("helper")
print("plain:", helper.VALUE, helper is sys.modules["helper"])
print("cached:", __import__("helper") is helper)

# A dotted name answers the head, though both halves are filed and the parent records the child.
head = __import__("pkg.sibling")
print("head:", head.VALUE, head is sys.modules["pkg"])
print("leaf:", sys.modules["pkg.sibling"].VALUE)
print("parent-attr:", sys.modules["pkg"].sibling is sys.modules["pkg.sibling"])
print("leaf-package:", sys.modules["pkg.sibling"].__package__)
print("order:", list(sys.modules).index("pkg") < list(sys.modules).index("pkg.sibling"))

# A subpackage's own code runs after its parent is filed, and sees both entries.
sub = __import__("pkg.sub")
print("sub-head:", sub is sys.modules["pkg"])
print("sub:", sys.modules["pkg.sub"].VALUE, sys.modules["pkg.sub"].__package__)
print("sub-filed:", sys.modules["pkg.sub"].PARENT_IN_MODULES, sys.modules["pkg.sub"].SELF_IN_MODULES)
print("sub-attr:", sys.modules["pkg"].sub is sys.modules["pkg.sub"])

# A from-list answers the module as asked and loads the names the package does not already have.
wanted = __import__("pkg", fromlist=("sibling",))
print("fromlist:", wanted is sys.modules["pkg"], sys.modules["pkg.sibling"].VALUE)

star = __import__("pkall", fromlist=("*",))
print("star:", star is sys.modules["pkall"], sys.modules["pkall.one"].VALUE, sys.modules["pkall.two"].VALUE)

# What counts as an empty from-list is its truth, not its length.
print("empty-tuple:", __import__("pkg.sibling", fromlist=()) is sys.modules["pkg"])
print("empty-list:", __import__("helper", fromlist=[]) is helper)
print("none:", __import__("helper", fromlist=None) is helper)
print("zero:", __import__("helper", fromlist=0) is helper)
print("false:", __import__("helper", fromlist=False) is helper)
print("int-on-module:", __import__("helper", fromlist=3) is helper)

# globals and locals are not read at all when the level is zero.
print("level-zero:", __import__("helper", None, None, (), 0) is helper)
print("globals-ignored:", __import__("helper", 3, 4, 5, 0) is helper)

# A relative name is made absolute against the caller's package; the head rule follows the name
# as it was written, so a name with no dot answers the module the resolution named.
pkg_globals = {"__name__": "pkg.sibling", "__package__": "pkg"}
print("relative:", __import__("", pkg_globals, None, ("VALUE",), 1).__name__)
print("relative-leaf:", __import__("sibling", pkg_globals, None, ("VALUE",), 1) is sys.modules["pkg.sibling"])
print("relative-plain:", __import__("sibling", pkg_globals, None, (), 1).__name__)

# The three ways a package is found when __package__ says nothing.
print("fallback-name:", __import__("sibling", {"__name__": "pkg.sibling"}, None, ("VALUE",), 1).VALUE)
print("fallback-path:", __import__("", {"__name__": "pkg", "__path__": []}, None, ("VALUE",), 1).__name__)
print("fallback-none:", __import__("sibling", {"__package__": None, "__name__": "pkg.sibling"}, None, ("VALUE",), 1).VALUE)
print("fallback-spec:", __import__("sibling", {"__package__": None, "__spec__": sys.modules["pkg"].__spec__}, None, ("VALUE",), 1).VALUE)

# The refusals, in the reference's own words.
report("relative-root:", lambda: __import__("", {"__name__": "helper", "__package__": ""}, None, (), 1))
report("beyond:", lambda: __import__("sibling", {"__name__": "a.b", "__package__": "a"}, None, (), 2))
report("no-name:", lambda: __import__("", {}, None, (), 1))
report("globals-type:", lambda: __import__("", 3, None, (), 1))
report("package-type:", lambda: __import__("", {"__package__": 3, "__name__": "pkg"}, None, (), 1))
report("name-type:", lambda: __import__("", {"__name__": 3}, None, (), 1))
report("empty:", lambda: __import__(""))
report("bad-name:", lambda: __import__(3))
report("level:", lambda: __import__("helper", None, None, (), -1))
report("level-type:", lambda: __import__("helper", None, None, (), "x"))
report("missing-arg:", lambda: __import__())
report("too-many:", lambda: __import__("a", None, None, (), 0, 1))
report("duplicate:", lambda: __import__("helper", name="helper"))
report("unexpected:", lambda: __import__("helper", extra=1))
report("not-package:", lambda: __import__("helper.sub"))
report("deep-not-package:", lambda: __import__("pkg.sibling.more"))
report("trailing-one:", lambda: __import__("pkg."))
report("trailing-double:", lambda: __import__("pkg..sibling"))
report("trailing-leaf:", lambda: __import__("pkg.sibling."))
report("missing-head:", lambda: __import__("no_pkg.sub"))
report("fromlist-item:", lambda: __import__("pkg", fromlist=(3,)))
report("fromlist-missing:", lambda: __import__("pkg", fromlist=("nope",)))

sys.modules["blocked"] = None
report("blocked:", lambda: __import__("blocked"))
