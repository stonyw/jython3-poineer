package org.python.core;

/**
 * A module: a name and the dictionary it owns.
 *
 * {@code __main__} is one of these from the first slice, because a module-level code object runs with
 * a module's dict as its globals and {@code LOAD_NAME} writes straight into it. The import system that
 * populates the rest of them is M4.
 */
public class PyModule extends PyObject {

    private final String name;
    private final PyDict dictionary;

    public PyModule(String name) {
        this(name, PyNone.INSTANCE, PyDict.empty());
    }

    PyModule(String name, PyDict dictionary) {
        this(name, PyNone.INSTANCE, dictionary);
    }

    private PyModule(String name, PyObject doc, PyDict dictionary) {
        super(PyType.MODULE);
        this.name = name;
        this.dictionary = dictionary;
        dictionary.put(PyStr.interned("__name__"), PyStr.of(name));
        dictionary.put(PyStr.interned("__doc__"), doc);
    }

    /**
     * A module whose class is one Python wrote, allocated by {@code module_new} with nothing filed.
     *
     * <p>The reference's {@code module_new} leaves the dictionary empty and its {@code module_init}
     * fills the five names; that split is what makes {@code M.__new__(M)} a module with no
     * {@code __name__} at all, and {@code M('a')} one whose {@code MT.__init__} wrote them.
     */
    static PyModule uninitialised(PyType kind) {
        return new PyModule(kind, null);
    }

    private PyModule(PyType kind, PyDict unused) {
        super(kind);
        this.name = null;
        this.dictionary = PyDict.empty();
    }

    /**
     * {@code module.__new__(cls)}: an empty module of the class asked for.
     *
     * <p>The reference's {@code module_new} reads nothing but its type, so arguments are ignored here
     * too; a class that wants a name runs {@code module_init} — inherited, or called by name — and
     * that is the function that writes it.
     */
    static PyObject allocate(PyObject[] args, String[] kwNames, String qualified) {
        if (!(args[0] instanceof PyType kind) || !kind.matches(PyType.MODULE)) {
            throw Slots.typeError(
                    "module.__new__(X): X is not a type object of a module subtype");
        }
        return uninitialised(kind);
    }

    /**
     * {@code module.__init__(self, name, doc=None)}: the five names a module made by hand carries.
     *
     * <p>The reference's {@code module_init} files them in this order, and the last three are
     * {@code None} for the same reason: a module made without the import system has no spec, no
     * loader and no package.
     */
    static PyObject initialise(PyObject[] args, String[] kwNames) {
        if (!(args.length > 0) || !(args[0] instanceof PyModule module)) {
            throw Slots.typeError(
                    "descriptor '__init__' requires a 'module' object but received a '%s'",
                    args.length > 0 ? args[0].getType().name() : "NoneType");
        }
        PyObject[] values =
                Arguments.unpackKeywords(
                        "module",
                        java.util.Arrays.copyOfRange(args, 1, args.length),
                        kwNames,
                        new String[] {"name", "doc"},
                        1,
                        2);
        if (!(values[0] instanceof PyStr text)) {
            throw Slots.typeError(
                    "module() argument 'name' must be str, not %s",
                    Slots.argumentName(values[0]));
        }
        module.set("__name__", text);
        module.set("__doc__", values[1] == null ? PyNone.INSTANCE : values[1]);
        module.set("__package__", PyNone.INSTANCE);
        module.set("__loader__", PyNone.INSTANCE);
        module.set("__spec__", PyNone.INSTANCE);
        return PyNone.INSTANCE;
    }

    /** {@code module.__getattribute__(self, name)}: the walk {@link #attribute} makes, by name. */
    static PyObject read(PyObject[] args, String[] kwNames) {
        if (!(args.length == 2) || !(args[0] instanceof PyModule module)) {
            throw Slots.typeError(
                    "descriptor '__getattribute__' requires a 'module' object but received a '%s'",
                    args.length > 0 ? args[0].getType().name() : "NoneType");
        }
        String attribute = Numbers.asText(args[1]);
        if (attribute == null) {
            throw Slots.typeError("attribute name must be string, not '%s'", args[1].getType().name());
        }
        return module.attribute(attribute);
    }

    /** {@code module.__setattr__(self, name, value)}: a dictionary entry, refused for {@code __dict__}. */
    static PyObject write(PyObject[] args, String[] kwNames) {
        if (!(args.length == 3) || !(args[0] instanceof PyModule module)) {
            throw Slots.typeError(
                    "descriptor '__setattr__' requires a 'module' object but received a '%s'",
                    args.length > 0 ? args[0].getType().name() : "NoneType");
        }
        String attribute = Numbers.asText(args[1]);
        if (attribute == null) {
            throw Slots.typeError("attribute name must be string, not '%s'", args[1].getType().name());
        }
        return module.writeDirect(attribute, args[2]);
    }

    /** {@code module.__delattr__(self, name)}: the entry goes, as {@link #delAttr} says. */
    static PyObject delete(PyObject[] args, String[] kwNames) {
        if (!(args.length == 2) || !(args[0] instanceof PyModule module)) {
            throw Slots.typeError(
                    "descriptor '__delattr__' requires a 'module' object but received a '%s'",
                    args.length > 0 ? args[0].getType().name() : "NoneType");
        }
        String attribute = Numbers.asText(args[1]);
        if (attribute == null) {
            throw Slots.typeError("attribute name must be string, not '%s'", args[1].getType().name());
        }
        return module.deleteDirect(attribute);
    }

    /**
     * {@code dir(module)}: the names in its dictionary, which is all the reference's {@code module_dir}
     * answers.
     *
     * <p>A class that replaced {@code __dict__} with something else makes that name unable to be read
     * as a mapping, and the reference refuses the whole call rather than listing what it can.
     */
    static PyObject names(PyObject[] args, String[] kwNames) {
        if (!(args.length == 1) || !(args[0] instanceof PyModule module)) {
            throw Slots.typeError(
                    "descriptor '__dir__' requires a 'module' object but received a '%s'",
                    args.length > 0 ? args[0].getType().name() : "NoneType");
        }
        PyObject shown = Slots.getAttr(module, "__dict__");
        if (!(shown instanceof PyDict entries)) {
            throw Slots.typeError("%s.__dict__ is not a dictionary", module.getType().name());
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (PyObject key : entries.keys()) {
            if (key instanceof PyStr text) {
                names.add(text.getValue());
            }
        }
        java.util.Collections.sort(names);
        PyObject[] listed = new PyObject[names.size()];
        for (int i = 0; i < listed.length; i++) {
            listed[i] = PyStr.of(names.get(i));
        }
        return PyList.of(listed);
    }

    /**
     * {@code module(name, doc=None)}: what calling {@code type(sys)} builds.
     *
     * <p>The five keys are the ones the reference's {@code module_new} files, in this order, and the
     * last three really are {@code None} — a module made by hand has no spec, no loader and no
     * package. The constructor's refusals are a clinic parser's, so {@code module(5)} names the
     * argument rather than the call.
     */
    static PyModule created(PyObject[] args, String[] kwNames) {
        PyObject[] values =
                Arguments.unpackKeywords(
                        "module", args, kwNames, new String[] {"name", "doc"}, 1, 2);
        if (!(values[0] instanceof PyStr name)) {
            throw Slots.typeError(
                    "module() argument 'name' must be str, not %s", Slots.argumentName(values[0]));
        }
        PyObject doc = values[1] == null ? PyNone.INSTANCE : values[1];
        PyModule module = new PyModule(name.getValue(), doc, PyDict.empty());
        module.set("__package__", PyNone.INSTANCE);
        module.set("__loader__", PyNone.INSTANCE);
        module.set("__spec__", PyNone.INSTANCE);
        return module;
    }

    public String name() {
        if (name != null) {
            return name;
        }
        // A module a class allocated has no Java-level name until `module_init` writes one; the
        // dictionary is where that write lands, so it is where the name is read back from.
        PyObject filed = dictionary.get(PyStr.interned("__name__"));
        return filed instanceof PyStr text ? text.getValue() : "?";
    }

    /** The namespace {@code LOAD_NAME} and {@code STORE_NAME} use. */
    public PyDict dictionary() {
        return dictionary;
    }

    public void set(String key, PyObject value) {
        dictionary.put(PyStr.interned(key), value);
    }

    public PyObject get(String key) {
        return dictionary.get(PyStr.interned(key));
    }

    /**
     * Module attributes are dictionary entries, with the exceptions CPython also has: {@code __dict__} is
     * the dictionary itself and only that name is read-only, and the two annotation names are read and
     * written through PEP 649's rules rather than being plain entries.
     */
    @Override
    public PyObject getAttr(String attribute) {
        // A class Python wrote owns this module's `__getattribute__`, exactly as it would own a plain
        // value's: `slot_tp_getattro` is the type's slot once a class overrides it, so the hook runs
        // before the dictionary is consulted at all.
        if (getType() instanceof PyClass
                && Specials.lookUp(getType(), "__getattribute__") instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(attribute)}, null);
        }
        return attribute(attribute);
    }

    /**
     * The walk CPython's {@code module_getattro} makes after the hook: a data descriptor the class
     * declares, then the dictionary, then the class's other members, then the module type's own table.
     *
     * <p>For a plain module the class step answers nothing and this is the dictionary read it always
     * was. For a class Python wrote it is what lets {@code class M(ModuleType): a = 1} answer
     * {@code M('m').a}, and what lets a class replace {@code __dict__} with a property — a data
     * descriptor, so it wins over the dictionary the module would otherwise hand back.
     */
    private PyObject attribute(String attribute) {
        PyType kind = getType();
        if (kind instanceof PyClass) {
            PyObject member = Specials.lookUp(kind, attribute);
            if (member != null && Descriptors.writes(member) && Descriptors.binds(member)) {
                return Descriptors.get(member, this, kind);
            }
        }
        if ("__dict__".equals(attribute)) {
            return dictionary;
        }
        if ("__annotate__".equals(attribute)) {
            return annotateAttribute();
        }
        if ("__annotations__".equals(attribute)) {
            return annotationsAttribute();
        }
        PyObject value = dictionary.get(PyStr.interned(attribute));
        if (value != null) {
            return value;
        }
        if (kind instanceof PyClass) {
            PyObject member = Specials.lookUp(kind, attribute);
            if (member != null) {
                return Descriptors.get(member, this, kind);
            }
            PyObject answer = Specials.invoke(this, "__getattr__", PyStr.of(attribute));
            if (!Slots.declined(answer)) {
                return answer;
            }
        }
        return Slots.DECLINED;
    }

    /**
     * {@code module.__annotate__}: the function this module's definitions left behind, or {@code None}.
     *
     * <p>CPython's {@code module_getannotate} files the {@code None} it answers into the dictionary, so a
     * module that never had annotations keeps one entry saying so rather than an answer that would have to
     * be reconstructed and could disagree with a later {@code del}.
     */
    private PyObject annotateAttribute() {
        PyStr name = PyStr.interned("__annotate__");
        PyObject value = dictionary.get(name);
        if (value == null) {
            dictionary.put(name, PyNone.INSTANCE);
            return PyNone.INSTANCE;
        }
        return value;
    }

    /**
     * {@code module.__annotations__}: the cached dictionary, computed once from {@code __annotate__}.
     *
     * <p>This is 3.14's module half of PEP 649: running a module's code leaves an annotate function, and
     * reading {@code __annotations__} calls it with {@code Format.VALUE} and keeps the answer. A module
     * with neither name answers an empty dictionary, which is also what a module whose code named no
     * annotations has. The annotate function itself is left alone, so a caller that wants a different
     * format can still ask for it through {@link AnnotationLib}.
     */
    private PyObject annotationsAttribute() {
        PyStr plural = PyStr.interned("__annotations__");
        PyObject cached = dictionary.get(plural);
        if (cached != null) {
            return cached;
        }
        PyObject annotate = dictionary.get(PyStr.interned("__annotate__"));
        PyObject computed;
        if (annotate == null || annotate == PyNone.INSTANCE) {
            computed = PyDict.empty();
        } else {
            // CPython's module getter calls the annotate function directly, so a refusal in it is the
            // answer rather than something to translate the way `call_annotate_function` does.
            computed = Slots.call(annotate, new PyObject[] {PyFormat.VALUE}, null);
            if (!(computed instanceof PyDict)) {
                throw Slots.typeError(
                        "__annotate__ returned non-dict of type '%s'", computed.getType().name());
            }
        }
        dictionary.put(plural, computed);
        return computed;
    }

    /**
     * {@code module.name = value}, with the two annotation names keeping PEP 649's invariants.
     *
     * <p>{@code __annotate__} must be callable or {@code None} — anything else would leave a module whose
     * annotations can never be read. Writing {@code __annotations__} renames the value authoritative and
     * moves the annotate function to {@code None}, which is what CPython does so that a later read answers
     * the written dictionary rather than recomputing over it.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        if (getType() instanceof PyClass
                && Specials.lookUp(getType(), "__setattr__") instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(attribute), value}, null);
        }
        return writeDirect(attribute, value);
    }

    private PyObject writeDirect(String attribute, PyObject value) {
        if ("__dict__".equals(attribute)) {
            throw Slots.attributeError("readonly attribute");
        }
        if ("__annotate__".equals(attribute)) {
            if (value != PyNone.INSTANCE && !Slots.isCallable(value)) {
                throw Slots.typeError("__annotate__ must be callable or None");
            }
            dictionary.put(PyStr.interned(attribute), value);
            return PyNone.INSTANCE;
        }
        if ("__annotations__".equals(attribute)) {
            dictionary.put(PyStr.interned(attribute), value);
            dictionary.put(PyStr.interned("__annotate__"), PyNone.INSTANCE);
            return PyNone.INSTANCE;
        }
        dictionary.put(PyStr.interned(attribute), value);
        return PyNone.INSTANCE;
    }

    /** {@code md_dict}: the same dictionary {@link #setAttr} writes, offered to the generic one. */
    @Override
    PyDict attributeStore() {
        return dictionary;
    }

    /**
     * {@code del module.name}: the entry goes, and a name the dictionary never held is refused as a read
     * of it is.
     *
     * <p>{@code del sys.excepthook} is how a program asks CPython to print an escaping exception itself,
     * so the launcher's read of the name has to be able to come back missing rather than as {@code None}
     * — which CPython would call, and answer {@code 'NoneType' object is not callable}.
     */
    @Override
    public PyObject delAttr(String attribute) {
        if (getType() instanceof PyClass
                && Specials.lookUp(getType(), "__delattr__") instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(attribute)}, null);
        }
        return deleteDirect(attribute);
    }

    private PyObject deleteDirect(String attribute) {
        if ("__dict__".equals(attribute)) {
            throw Slots.attributeError("readonly attribute");
        }
        if ("__annotate__".equals(attribute)) {
            throw Slots.typeError("cannot delete __annotate__ attribute");
        }
        if ("__annotations__".equals(attribute)) {
            PyObject annotations = dictionary.remove(PyStr.interned("__annotations__"));
            PyObject annotate = dictionary.remove(PyStr.interned("__annotate__"));
            if (annotations == null && annotate == null) {
                throw Slots.attributeError("__annotations__");
            }
            return PyNone.INSTANCE;
        }
        return dictionary.remove(PyStr.interned(attribute)) == null
                ? Slots.DECLINED
                : PyNone.INSTANCE;
    }

    @Override
    public PyObject getItem(PyObject key) {
        String name = Numbers.asText(key);
        if (name == null) {
            throw Slots.typeError(
                    "module indices must be str, not %s", key.getType().name());
        }
        PyObject value = dictionary.get(PyStr.of(name));
        if (value == null) {
            throw Slots.keyError(key);
        }
        return value;
    }

    /**
     * {@code repr(module)}, which reads {@code __name__} out of the dictionary rather than the name
     * the module was made with: a program may rename it or delete the entry, and the reference's
     * catch-all repr follows those writes. A name that is not a string is shown as it reprs, and a
     * module with no name at all is the reference's {@code '?'}.
     */
    @Override
    public String repr() {
        PyObject shown = dictionary.get(PyStr.interned("__name__"));
        if (shown == null) {
            return "<module '?'>";
        }
        if (shown instanceof PyStr text) {
            PyObject spec = dictionary.get(PyStr.interned("__spec__"));
            if (spec instanceof PyModuleSpec moduleSpec
                    && Slots.getAttr(moduleSpec, "loader") instanceof PyNamespaceLoader) {
                // A namespace package names the portions it merged rather than a file it came from.
                PyObject locations = Slots.getAttr(moduleSpec, "submodule_search_locations");
                PyObject portions =
                        locations instanceof PyNamespacePath path ? path.portions() : locations;
                return "<module '" + text.getValue() + "' (namespace) from "
                        + Slots.repr(portions) + ">";
            }
            return "<module '" + text.getValue() + "'>";
        }
        return "<module " + Slots.repr(shown) + ">";
    }
}
