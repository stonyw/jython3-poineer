package org.python.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The methods a type's values share, as CPython's method definition tables are.
 *
 * One table per type, built once in a static field, and a lookup that hands back a
 * {@link PyBoundMethod} carrying the receiver — which is what makes {@code "a".upper} print as
 * {@code <built-in method upper of str object>} and compare correctly against itself.
 *
 * <p>A table also remembers which type it belongs to, because the same methods are reachable through
 * the type: {@code str.upper("a")} takes the value as its argument, and CPython answers that with a
 * {@link PyMethodDescriptor} rather than refusing. {@link #tableOf} is how a type finds its table, and
 * installs the one its value class declares when no value of that type has been built yet.
 *
 * <p>Both lookups here walk the type's {@link PyType#mro mro()}, so a type has the methods it inherits
 * and not only the ones it declares: {@code True.bit_length()} reaches the table {@code int} declares,
 * and {@code bool.bit_length} is the same descriptor {@code int.bit_length} gives.
 *
 * Names here are the Python-visible ones only; a dunder is a slot on {@link PyObject} and is answered
 * there, because the interpreter calls slots directly rather than looking them up as attributes.
 */
final class Members {

    /** A method taking one argument, checked for the caller. */
    interface One {
        PyObject apply(PyObject self, PyObject argument);
    }

    /** A method of the type rather than of one of its values, with the type kept apart from the call. */
    interface OnType {
        PyObject apply(PyObject type, PyObject[] args, String[] kwNames);
    }

    /** Each table by the type it was built for, which is what a lookup on the class consults. */
    private static final Map<PyType, Members> REGISTRY = new ConcurrentHashMap<>();

    /**
     * The class that declares each built-in type's table.
     *
     * <p>A table is a static field, so Java initializes it only when the class is first used — and a
     * script that only ever asks {@code set.add} has never made a set. {@link #tableOf} initializes the
     * class named here, which is what makes a type's members answerable before any value of it exists.
     */
    private static final Map<PyType, Class<?>> VALUE_CLASSES = Map.ofEntries(
            Map.entry(PyType.INT, PyInt.class),
            Map.entry(PyType.FLOAT, PyFloat.class),
            Map.entry(PyType.COMPLEX, PyComplex.class),
            Map.entry(PyType.STR, PyStr.class),
            Map.entry(PyType.BYTES, PyBytes.class),
            Map.entry(PyType.TUPLE, PyTuple.class),
            Map.entry(PyType.LIST, PyList.class),
            Map.entry(PyType.DICT, PyDict.class),
            Map.entry(PyType.SET, PySet.class),
            Map.entry(PyType.FROZENSET, PySet.class),
            // A code object's values are made by whichever front-end reads one, so its table belongs to no
            // value class: CodeMembers declares it, and is loaded when a code object is.
            Map.entry(PyType.CODE, CodeMembers.class),
            Map.entry(PyType.GENERATOR, PyGenerator.class),
            // The one named sequence is declared by its value class too, so that asking the type for a
            // field works before the interpreter has built the value it files.
            Map.entry(PyType.VERSION_INFO, PyVersionInfo.class));

    private final PyType owner;

    /** Built during the value class's own initialization, then only read. */
    private final Map<String, PyBoundMethod.Body> entries = new LinkedHashMap<>();

    /**
     * The methods this type declares that take no receiver: {@code bytes.maketrans}.
     *
     * <p>One object per name, held here rather than in {@link #values}, because the two differ in what a
     * read hands out: a value is the object the dictionary holds, and these are answered identically from
     * the type and from any value of it, which is what a {@code staticmethod} in a built-in type is.
     */
    private final Map<String, PyNativeFunction> statics = new LinkedHashMap<>();

    /**
     * The same static methods as the dictionary holds them: wrapped in a {@code staticmethod}.
     *
     * <p>CPython's {@code bytes.__dict__['maketrans']} is the wrapper, and its {@code __get__} hands back
     * the function underneath — which is why the entry reads as a {@code staticmethod} while the attribute
     * read of the same name reads as a function. Two answers, so two objects, and the one the read gives is
     * the one {@link #statics} keeps so that a value and its type still come back with the same method.
     */
    private final Map<String, PyObject> published = new LinkedHashMap<>();

    /** The methods whose first argument is the type itself: {@code bytes.fromhex}. */
    private final Map<String, PyNativeFunction> classMethods = new LinkedHashMap<>();

    /**
     * The names this type files as storage rather than as behaviour: {@code sys.version_info}'s five
     * fields, each a {@link PyMemberDescriptor} the dictionary holds once.
     */
    private final Map<String, PyMemberDescriptor> fields = new LinkedHashMap<>();

    /**
     * The plain values a type's dictionary holds, which are neither methods nor fields: {@code
     * sys.version_info} files its three counts and its {@code __match_args__} that way, and a value read
     * of one is the value itself rather than something a descriptor answers.
     */
    private final Map<String, PyObject> values = new LinkedHashMap<>();

    /**
     * The sentences the table's bodies were written beside.
     *
     * <p>CPython keeps each one in the {@code PyMethodDef} next to the function pointer, so it belongs to
     * the type rather than to a read of it, and a bound method and its descriptor answer with the same
     * text. Most tables here transcribe no sentence, and the names they file answer {@code __doc__} with
     * {@code None} — a deviation the plan records for the whole family.
     */
    private final Map<String, String> docs = new LinkedHashMap<>();

    /** Made on the first lookup through the type, which is why this one is concurrent. */
    private final Map<String, PyObject> descriptors = new ConcurrentHashMap<>();

    private Members(PyType owner) {
        this.owner = owner;
    }

    static Members of(PyType type) {
        Members methods = new Members(type);
        REGISTRY.put(type, methods);
        return methods;
    }

    /**
     * The table {@code type} declares, installing it if this is the first time the type has been asked.
     *
     * @return the table, or null when the type declares no methods of its own — a class built by
     *     {@link Classes} holds its members in a namespace, and {@code object} has none
     */
    static Members tableOf(PyType type) {
        Members methods = REGISTRY.get(type);
        if (methods != null) {
            return methods;
        }
        Class<?> values = VALUE_CLASSES.get(type);
        if (values == null) {
            return null;
        }
        try {
            Class.forName(values.getName(), true, Members.class.getClassLoader());
        } catch (ClassNotFoundException declaredInThisRuntime) {
            throw Slots.runtimeError(
                    "the value class " + values.getName() + " is not on the class path");
        }
        return REGISTRY.get(type);
    }

    /**
     * The method named {@code name} that {@code self}'s type inherits, bound to {@code self}.
     *
     * <p>The walk is the type's lookup order, so a bool is answered by the methods int declares. The
     * bound method keeps the receiver Python asked for — {@code True.bit_length.__self__} is the bool,
     * and {@code LOAD_ATTR} recognises it as already bound — while the Java body is given the value its
     * own type can read, since it casts to {@link PyInt} rather than to {@link PyBool}. See
     * {@link PyObject#adaptedFor}.
     *
     * @return the bound method, or {@link PyNotImplemented} when no type in the order declares it
     */
    static PyObject find(PyObject self, String name) {
        for (PyType owner : self.getType().mro()) {
            Members methods = tableOf(owner);
            if (methods == null) {
                continue;
            }
            PyObject bound = methods.bind(self, name);
            if (bound != PyNotImplemented.INSTANCE) {
                return bound;
            }
        }
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code self.name} for a name the type files that is not a method.
     *
     * <p>A field is answered through its descriptor, so the receiver is checked against the type that
     * declares it exactly as a read of the descriptor from outside checks it; a plain value is the
     * dictionary's own entry and needs no checking, because every value of the type is asked with the
     * same object.
     *
     * @return the answer, or {@link PyNotImplemented} when no type in the order files the name
     */
    static PyObject filed(PyObject self, String name) {
        for (PyType owner : self.getType().mro()) {
            Members methods = tableOf(owner);
            if (methods == null) {
                continue;
            }
            PyMemberDescriptor field = methods.fields.get(name);
            if (field != null) {
                return Descriptors.get(field, self, self.getType());
            }
            PyObject value = methods.values.get(name);
            if (value != null) {
                return value;
            }
        }
        return PyNotImplemented.INSTANCE;
    }

    /**
     * Whether {@code self}'s type files {@code name}, and whether a filed name is a field.
     *
     * <p>The two answers choose between CPython's three refusals of a write: a field is refused by its
     * descriptor with {@code readonly attribute}, a filed name that is not a descriptor is refused by the
     * generic setter, and a name nobody files is refused because the value has no dictionary.
     */
    static boolean files(PyObject self, String name) {
        for (PyType owner : self.getType().mro()) {
            Members methods = tableOf(owner);
            if (methods != null && methods.files(name)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code name} is one of {@code self}'s type's fields rather than a method or a value. */
    static boolean filesField(PyObject self, String name) {
        for (PyType owner : self.getType().mro()) {
            Members methods = tableOf(owner);
            if (methods != null && methods.fields.containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    /** The full form, for a method that checks its own arguments or accepts keywords. */
    Members add(String name, PyBoundMethod.Body body) {
        entries.put(name, body);
        return this;
    }

    /**
     * A method with the sentence CPython's {@code PyMethodDef} carries beside it.
     *
     * <p>{@code __doc__} is read from the table rather than from a call, so the descriptor and the method
     * bound to a value answer with the same text — which is what {@code version_info.__replace__.__doc__}
     * and {@code version_info().replace.__doc__} are in CPython.
     */
    Members add(String name, String doc, PyBoundMethod.Body body) {
        add(name, body);
        docs.put(name, doc);
        return this;
    }

    /**
     * A field: one of the items underneath, reached by name and never written.
     *
     * <p>The declaration order is the item order, which is what makes {@code major} the first item of a
     * {@code sys.version_info} and {@code serial} the fifth. CPython builds the same table from a
     * {@code PyMemberDef} array whose offsets are the field indices, and marks every one of them
     * {@code Py_READONLY}; the doc is that array's, not a docstring.
     */
    Members addField(String name, String doc) {
        fields.put(name, new PyMemberDescriptor(owner, name, fields.size(), doc));
        return this;
    }

    /** A value the type's dictionary holds, of the kind {@code n_fields} and {@code __match_args__} are. */
    Members addValue(String name, PyObject value) {
        values.put(name, value);
        return this;
    }

    /** {@code upper()}, {@code bit_length()}: no arguments, and the message says so in CPython's words. */
    Members addNoArgument(String name, Function<PyObject, PyObject> body) {
        String qualified = owner.name() + "." + name + "()";
        return add(name, (self, args, kwNames) -> {
            Arguments.none(qualified, args, kwNames);
            return body.apply(self);
        });
    }

    /** The same, with the sentence that {@code PyMethodDef} carries beside it. */
    Members addNoArgument(String name, String doc, Function<PyObject, PyObject> body) {
        addNoArgument(name, body);
        docs.put(name, doc);
        return this;
    }

    /** {@code append(x)}, {@code join(x)}: exactly one argument. */
    Members addOne(String name, One body) {
        String qualified = owner.name() + "." + name + "()";
        return add(name, (self, args, kwNames) -> {
            Arguments.exactlyOne(qualified, args, kwNames);
            return body.apply(self, args[0]);
        });
    }

    /**
     * A method that takes no receiver: {@code bytes.maketrans}.
     *
     * <p>The one function in {@link #statics} answers for the type and for every value of it, because
     * CPython's {@code staticmethod} descriptor hands back the function it wraps — {@code bytes.maketrans
     * is b''.maketrans} is true — and its {@code __self__} is {@code None}, not the type it belongs to.
     * The dictionary holds the wrapper around that function, so {@code bytes.__dict__['maketrans']} reads
     * as a {@code staticmethod} and {@code bytes.maketrans} as the function, which is what
     * {@link #published} keeps the two apart for. That wrapper is the one a method table files — it was
     * never handed a function to {@code functools_wraps}, so it answers none of {@code __name__},
     * {@code __qualname__} or {@code __module__}, exactly as {@code str.__dict__['maketrans']} is silent.
     */
    Members addStatic(String name, PyNativeFunction.Body body) {
        PyNativeFunction function = PyNativeFunction.declaredBy(owner.name(), name, body);
        statics.put(name, function);
        published.put(name, PyStaticMethod.filed(function));
        return this;
    }

    /**
     * A method whose first argument is the type it was read from: {@code bytes.fromhex}.
     *
     * <p>A fresh object per read, which is CPython's too: {@code bytes.fromhex is bytes.fromhex} is
     * false, and {@code __self__} names the type the read was made through — not the one that declares
     * the name, which is why the table keeps these with no receiver attached and lets {@link #describe}
     * hand the read to {@link Descriptors}. The body is handed the arguments of the call rather than
     * that type, because {@code fromhex} builds a {@code bytes} and nothing else — a method that builds
     * a value <em>of the type it was reached through</em> is {@link #addClassNamed}.
     */
    Members addClass(String name, PyNativeFunction.Body body) {
        classMethods.put(
                name,
                PyNativeFunction.onType(
                        owner,
                        name,
                        (args, kwNames) -> body.apply(
                                java.util.Arrays.copyOfRange(args, 1, args.length), kwNames)));
        return this;
    }

    /**
     * A class method the body sees the type of: {@code int.from_bytes}.
     *
     * <p>CPython hands {@code METH_CLASS} methods their type and, when it is not the one that declared
     * them, calls it with the value it worked out — which is what makes {@code bool.from_bytes(b'\xff')}
     * {@code True} rather than {@code 255}. Stripping the receiver, as {@link #addClass} does, would
     * throw that rule away, so the body is given the type it was reached through and the call's own
     * arguments apart from it. That type is the one the <em>read</em> ran on, which is why this table
     * holds the function unbound and {@link PyNativeFunction} answers the read.
     */
    Members addClassNamed(String name, OnType body) {
        return addClassNamed(name, null, body);
    }

    /** The same, with the sentence that {@code PyMethodDef} carries beside it. */
    Members addClassNamed(String name, String doc, OnType body) {
        classMethods.put(
                name,
                PyNativeFunction.onType(
                                owner,
                                name,
                                (args, kwNames) -> body.apply(
                                        args[0],
                                        java.util.Arrays.copyOfRange(args, 1, args.length),
                                        kwNames))
                        .documented(doc));
        return this;
    }

    /**
     * The method named {@code name}, bound to {@code self}, or a refusal if this type has no such method.
     *
     * <p>{@code self} is kept as the receiver and only the body sees the adapted one, so that a method a
     * bool inherits from int still reports itself as {@code bool.bit_length}.
     */
    PyObject bind(PyObject self, String name) {
        PyNativeFunction noReceiver = statics.get(name);
        if (noReceiver != null) {
            return noReceiver;
        }
        PyNativeFunction onType = classMethods.get(name);
        if (onType != null) {
            return onType.withReceiver(self.getType());
        }
        PyBoundMethod.Body body = entries.get(name);
        if (body == null) {
            return PyNotImplemented.INSTANCE;
        }
        String doc = docs.get(name);
        PyObject receiver = self.adaptedFor(owner);
        if (receiver == self) {
            return new PyBoundMethod(self, name, body, doc);
        }
        return new PyBoundMethod(self, name, (ignored, args, kwNames) ->
                body.apply(receiver, args, kwNames), doc);
    }

    /**
     * The method named {@code name} as the type's own, unbound.
     *
     * <p>CPython hands out the same descriptor from the type's dictionary every time it is asked, so
     * {@code list.append is list.append} is true; the table keeps one per name for the same reason. A
     * field is handed out the same way — its descriptor is built with the table, not per read — and a
     * value filed in the dictionary is handed out as itself, because that is what the dictionary holds.
     */
    PyObject describe(String name) {
        PyMemberDescriptor field = fields.get(name);
        if (field != null) {
            return field;
        }
        PyObject filed = values.get(name);
        if (filed != null) {
            return filed;
        }
        PyNativeFunction noReceiver = statics.get(name);
        if (noReceiver != null) {
            // The wrapper rather than the function: CPython's dictionary holds a `staticmethod` here and
            // its descriptor hands back the function underneath, which is what makes the entry read as a
            // `staticmethod` while `bytes.maketrans` reads as a function.
            return published.get(name);
        }
        PyNativeFunction onType = classMethods.get(name);
        if (onType != null) {
            // Unbound, so that the type asking is the one the read attaches: CPython's dictionary holds
            // a classmethod_descriptor here, not the method, and its getter names the class it was read
            // through rather than the one that declared it.
            return onType;
        }
        if (!defines(name)) {
            return PyNotImplemented.INSTANCE;
        }
        return descriptors.computeIfAbsent(
                name, key -> new PyMethodDescriptor(owner, key, docs.get(key)));
    }

    /** Whether this type defines that method at all, which {@code hasattr} and {@code in} both ask. */
    boolean defines(String name) {
        return entries.containsKey(name) || statics.containsKey(name) || classMethods.containsKey(name);
    }

    /** Whether this type files the name at all, as a method, a field or a value. */
    boolean files(String name) {
        return defines(name) || fields.containsKey(name) || values.containsKey(name);
    }

    /** The names Python sees, in declaration order, for {@code dir()}. */
    String[] names() {
        java.util.List<String> all = new java.util.ArrayList<>(entries.keySet());
        all.addAll(statics.keySet());
        all.addAll(classMethods.keySet());
        all.addAll(fields.keySet());
        all.addAll(values.keySet());
        return all.toArray(new String[0]);
    }
}
