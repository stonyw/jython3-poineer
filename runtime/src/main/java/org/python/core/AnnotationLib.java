package org.python.core;

import java.util.Map;

/**
 * {@code annotationlib}: the module PEP 649 grew for reading annotations that are no longer evaluated
 * at definition time.
 *
 * <p>The surface here is the part that can be answered without re-running anyone's code. {@link PyFormat}
 * carries the four formats, {@link #getAnnotations} reads a value's annotations through the ones it can
 * serve, and {@link #callAnnotateFunction} wraps an annotate function so that a caller sees the format it
 * asked for. The stringiner machinery is carried as far as the value conversion: when an annotate function
 * refuses {@code STRING}, the {@code VALUE} dictionary is stringified, which is the answer CPython's own
 * fallback gives.
 *
 * <p>What is not carried is named rather than faked. {@code VALUE_WITH_FAKE_GLOBALS} is refused at the
 * door, so the string fallback answers the {@code VALUE} dictionary stringified rather than re-running the
 * annotate function against rewritten globals, and {@code FORWARDREF} falls back on {@code VALUE} the same
 * way. {@code eval_str=True} evaluates each string annotation through {@link AnnotationEval}, which reads
 * names, attribute paths, subscripts, constants and parentheses — the shapes an annotation is normally
 * written with — and refuses every operator, including the {@code X | Y} union spelling. A caller that
 * needs one of those wider behaviours is told in this paragraph, not by a plausible wrong answer.
 */
final class AnnotationLib {

    /** The sentence CPython's module carries. */
    private static final String DOC = "Helpers for introspecting and wrapping annotations.";

    private static final String[] GET_NAMES = {"obj", "globals", "locals", "eval_str", "format"};

    private static final String[] CALL_NAMES = {"annotate", "format", "owner", "_is_evaluate"};

    private static final String[] EVALUATE_NAMES = {"evaluate", "format", "owner"};

    private static final String[] NAMESPACE_NAMES = {"namespace"};

    private static final String[] VALUE_NAMES = {"value"};

    private static final String[] ANNOTATIONS_NAMES = {"annotations"};

    private AnnotationLib() {}

    /** Build the module the runtime files under {@code annotationlib}. */
    static PyModule newModule() {
        PyModule module = new PyModule("annotationlib");
        module.set("__doc__", PyStr.of(DOC));
        module.set("__package__", PyStr.of(""));
        module.set("Format", PyType.FORMAT);
        module.set("ForwardRef", PyType.FORWARD_REF);
        module.set("get_annotations", new PyNativeFunction("get_annotations", AnnotationLib::getAnnotations));
        module.set("call_annotate_function",
                new PyNativeFunction("call_annotate_function", AnnotationLib::callAnnotateFunctionCalled));
        module.set("call_evaluate_function",
                new PyNativeFunction("call_evaluate_function", AnnotationLib::callEvaluateFunctionCalled));
        module.set("annotations_to_string",
                new PyNativeFunction("annotations_to_string", AnnotationLib::annotationsToStringCalled));
        module.set("type_repr", new PyNativeFunction("type_repr", AnnotationLib::typeReprCalled));
        module.set("get_annotate_from_class_namespace",
                new PyNativeFunction(
                        "get_annotate_from_class_namespace",
                        AnnotationLib::getAnnotateFromClassNamespace));
        module.set("__all__", PyList.of(new PyObject[] {
            PyStr.of("Format"),
            PyStr.of("ForwardRef"),
            PyStr.of("call_annotate_function"),
            PyStr.of("call_evaluate_function"),
            PyStr.of("get_annotate_from_class_namespace"),
            PyStr.of("get_annotations"),
            PyStr.of("annotations_to_string"),
            PyStr.of("type_repr")
        }));
        return module;
    }

    // --- get_annotations -------------------------------------------------------------------

    private static PyObject getAnnotations(PyObject[] args, String[] kwNames) {
        requireArguments("get_annotations", args, kwNames, "obj");
        PyObject[] values = Arguments.unpackKeywords("get_annotations", args, kwNames, GET_NAMES, 1, 1);
        PyObject obj = values[0];
        PyObject evalStr = values[3];
        PyObject format = values[4] == null ? PyFormat.VALUE : values[4];
        boolean evaluate = evalStr != null && Slots.truth(evalStr);
        if (evaluate && !isFormat(format, PyFormat.VALUE)) {
            throw Slots.valueError("eval_str=True is only supported with format=Format.VALUE");
        }
        PyObject annotations;
        if (isFormat(format, PyFormat.VALUE)) {
            annotations = dunderAnnotations(obj);
            if (annotations == null) {
                annotations = callAnnotateIfAny(obj, format);
            }
        } else if (isFormat(format, PyFormat.VALUE_WITH_FAKE_GLOBALS)) {
            throw Slots.valueError("The VALUE_WITH_FAKE_GLOBALS format is for internal use only");
        } else if (isFormat(format, PyFormat.FORWARDREF)) {
            annotations = forwardReferenceAnnotations(obj);
        } else if (isFormat(format, PyFormat.STRING)) {
            annotations = stringAnnotations(obj, format);
        } else {
            throw Slots.valueError("Unsupported format %s", format.repr());
        }
        if (annotations == null) {
            if (obj instanceof PyType || Slots.isCallable(obj)) {
                return PyDict.empty();
            }
            throw Slots.typeError("%s does not have annotations", obj.repr());
        }
        if (((PyDict) annotations).size() == 0) {
            return PyDict.empty();
        }
        return evaluate
                ? evaluateStrings(obj, (PyDict) annotations, values[1], values[2])
                : copy((PyDict) annotations);
    }

    /**
     * {@code eval_str=True}: every string annotation evaluated, every other value kept as it is.
     *
     * <p>The caller's {@code globals} and {@code locals} win; a missing one is filled from the object, as
     * CPython's own {@code get_annotations} does before it walks the dictionary.
     */
    private static PyObject evaluateStrings(
            PyObject obj, PyDict annotations, PyObject globals, PyObject locals) {
        PyObject[] namespaces = namespacesOf(obj);
        if (globals == null || globals == PyNone.INSTANCE) {
            globals = namespaces[0];
        }
        if (locals == null || locals == PyNone.INSTANCE) {
            locals = namespaces[1];
        }
        PyDict evaluated = PyDict.empty();
        for (Map.Entry<PyObject, PyObject> entry : annotations.getItems().entrySet()) {
            PyObject value = entry.getValue();
            evaluated.put(entry.getKey(),
                    value instanceof PyStr text
                            ? AnnotationEval.evaluate(text.getValue(), globals, locals)
                            : value);
        }
        return evaluated;
    }

    /**
     * The namespaces an evaluation of strings against {@code owner} uses when the caller gave none.
     *
     * <p>A module's dictionary is its own; a class reads its module's globals and a copy of its namespace,
     * which is what lets an annotation written in a class body see the class's own names; a function reads
     * the globals it was compiled in. Following {@code __wrapped__} and unwrapping a
     * {@code functools.partial} is the reference's extra step and is not carried here.
     */
    static PyObject[] namespacesOf(PyObject owner) {
        PyObject globals = null;
        PyObject locals = null;
        if (owner instanceof PyModule module) {
            globals = module.dictionary();
        } else if (owner instanceof PyType) {
            PyObject moduleName = Slots.findAttr(owner, "__module__");
            if (moduleName instanceof PyStr name) {
                PyObject home = PyRuntime.existingModule(name.getValue());
                if (home instanceof PyModule found) {
                    globals = found.dictionary();
                }
            }
            PyObject namespace = Slots.findAttr(owner, "__dict__");
            if (namespace instanceof PyMappingProxy window) {
                locals = window.copied();
            }
        } else if (Slots.isCallable(owner)) {
            globals = Slots.findAttr(owner, "__globals__");
        }
        return new PyObject[] {globals, locals};
    }

    /**
     * The refusal a Python function gives when required arguments are missing.
     *
     * <p>{@link Arguments#unpackKeywords} is modelled on CPython's C argument parser, whose sentence names
     * the position; these functions are written in Python in the reference, so a caller reads the sentence
     * a Python function gives instead.
     */
    private static void requireArguments(
            String function, PyObject[] args, String[] kwNames, String... required) {
        int positional = Arguments.positional(args, kwNames);
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (int i = 0; i < required.length; i++) {
            boolean byPosition = i < positional;
            boolean byName = named(kwNames, required[i]);
            if (byPosition && byName) {
                return; // `unpackKeywords` reports the duplicate in the parser's own words.
            }
            if (!byPosition && !byName) {
                missing.add(required[i]);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                names.append(i == missing.size() - 1
                        ? (missing.size() > 2 ? ", and " : " and ")
                        : ", ");
            }
            names.append('\'').append(missing.get(i)).append('\'');
        }
        throw Slots.typeError(
                "%s() missing %d required positional argument%s: %s",
                function, missing.size(), missing.size() == 1 ? "" : "s", names);
    }

    /** Whether {@code kwNames} carries {@code name}. */
    private static boolean named(String[] kwNames, String name) {
        if (kwNames != null) {
            for (String keyword : kwNames) {
                if (name.equals(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code FORWARDREF}: a dictionary of forward references.
     *
     * <p>CPython tries the cached {@code __annotations__} first and then re-runs the annotate function with
     * globals that make every name a reference. This runtime has no such globals, so it asks for
     * {@code VALUE} and the fallback is named in the class docstring.
     */
    private static PyObject forwardReferenceAnnotations(PyObject obj) {
        PyObject annotations = null;
        try {
            annotations = dunderAnnotations(obj);
        } catch (PyRaise ignored) {
            // CPython ignores a failing read here too, before it tries the annotate function.
        }
        if (annotations != null) {
            return annotations;
        }
        annotations = callAnnotateIfAny(obj, PyFormat.FORWARDREF);
        return annotations == null ? dunderAnnotations(obj) : annotations;
    }

    /** {@code STRING}: the annotate function's own {@code STRING} answer, or the {@code VALUE} one stringified. */
    private static PyObject stringAnnotations(PyObject obj, PyObject format) {
        PyObject annotations = callAnnotateIfAny(obj, format);
        if (annotations != null) {
            return annotations;
        }
        annotations = dunderAnnotations(obj);
        return annotations == null ? null : annotationsToString(annotations);
    }

    /**
     * {@code obj.__annotations__}, or null where CPython's read raises {@code AttributeError} or answers
     * {@code None}.
     *
     * <p>A static type refuses the read with {@code AttributeError} in CPython and with {@code TypeError}
     * in this runtime, so the refusal is not reached here: a static type simply has no annotations.
     */
    private static PyObject dunderAnnotations(PyObject obj) {
        if (obj instanceof PyType type && !(type instanceof PyClass)) {
            return null;
        }
        PyObject value;
        try {
            value = Slots.getAttr(obj, "__annotations__");
        } catch (PyRaise refusal) {
            if (refusal.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                return null;
            }
            throw refusal;
        }
        if (value == PyNone.INSTANCE) {
            return null;
        }
        if (!(value instanceof PyDict)) {
            throw Slots.valueError("%s.__annotations__ is neither a dict nor None", obj.repr());
        }
        return value;
    }

    /** {@code obj.__annotate__}, or null where the read is missing or {@code None}. */
    private static PyObject annotateOf(PyObject obj) {
        if (obj instanceof PyType type && !(type instanceof PyClass)) {
            return null;
        }
        PyObject annotate;
        try {
            annotate = Slots.getAttr(obj, "__annotate__");
        } catch (PyRaise refusal) {
            if (refusal.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                return null;
            }
            throw refusal;
        }
        return annotate == PyNone.INSTANCE ? null : annotate;
    }

    /**
     * CPython's {@code _get_and_call_annotate}: the annotate function's answer for {@code format}.
     *
     * <p>A missing function and a {@code None} answer are both null — the caller then falls back on the
     * cached dictionary, which is what the reference does. An answer that is not a dictionary is refused
     * in the module's own sentence.
     */
    private static PyObject callAnnotateIfAny(PyObject obj, PyObject format) {
        PyObject annotate = annotateOf(obj);
        if (annotate == null) {
            return null;
        }
        PyObject built = callAnnotateFunction(annotate, format);
        if (built == null || built == PyNone.INSTANCE) {
            return null;
        }
        if (!(built instanceof PyDict)) {
            throw Slots.valueError("%s.__annotate__ returned a non-dict", obj.repr());
        }
        return ((PyDict) built).size() == 0 ? PyDict.empty() : built;
    }

    /** A shallow copy, which is what {@code dict(ann)} is in CPython. */
    private static PyDict copy(PyDict annotations) {
        PyDict copied = PyDict.empty();
        for (Map.Entry<PyObject, PyObject> entry : annotations.getItems().entrySet()) {
            copied.put(entry.getKey(), entry.getValue());
        }
        return copied;
    }

    // --- call_annotate_function -------------------------------------------------------------

    private static PyObject callAnnotateFunctionCalled(PyObject[] args, String[] kwNames) {
        requireArguments("call_annotate_function", args, kwNames, "annotate", "format");
        PyObject[] values = Arguments.unpackKeywords(
                "call_annotate_function", args, kwNames, CALL_NAMES, 2, 2);
        // `owner` and `_is_evaluate` name state that only the stringifier paths read, and those paths are
        // answered from the `VALUE` dictionary here; the two names are parsed and dropped.
        return callAnnotateFunction(values[0], values[1]);
    }

    /** {@code call_evaluate_function(evaluate, format, *, owner=None)}: the same call, for an evaluation. */
    private static PyObject callEvaluateFunctionCalled(PyObject[] args, String[] kwNames) {
        requireArguments("call_evaluate_function", args, kwNames, "evaluate", "format");
        PyObject[] values = Arguments.unpackKeywords(
                "call_evaluate_function", args, kwNames, EVALUATE_NAMES, 2, 2);
        return callAnnotateFunction(values[0], values[1]);
    }

    /**
     * {@code call_annotate_function(annotate, format)}: call the function, and answer for the format it
     * refused.
     *
     * <p>The first call is the plain one, and whatever it returns is the answer — even when that is not a
     * dictionary, because CPython validates nothing at this level. Only a {@code NotImplementedError} sends
     * the call down the format's own fallback.
     */
    static PyObject callAnnotateFunction(PyObject annotate, PyObject format) {
        if (isFormat(format, PyFormat.VALUE_WITH_FAKE_GLOBALS)) {
            throw Slots.valueError("The VALUE_WITH_FAKE_GLOBALS format is for internal use only");
        }
        try {
            return Slots.call(annotate, new PyObject[] {format}, null);
        } catch (PyRaise refusal) {
            if (!refusal.value().exceptionType().matches(ExceptionTypes.NOT_IMPLEMENTED_ERROR)) {
                throw refusal;
            }
        }
        if (isFormat(format, PyFormat.STRING)) {
            return stringFallback(annotate);
        }
        if (isFormat(format, PyFormat.FORWARDREF)) {
            return callAnnotateFunction(annotate, PyFormat.VALUE);
        }
        if (isFormat(format, PyFormat.VALUE)) {
            throw Slots.runtimeError("annotate function does not support VALUE format");
        }
        throw Slots.valueError("Invalid format: %s", format.repr());
    }

    /**
     * {@code STRING} when the annotate function refused it.
     *
     * <p>CPython asks for {@code VALUE_WITH_FAKE_GLOBALS} next, then rewrites the function so that every
     * name it looks up reads a stringifier. This runtime has neither, so the answer to that second call —
     * which is a dictionary of real values — is stringified directly. That agrees with the reference for
     * every annotation the compiler generates: a name, a subscript or a constant. An annotate function that
     * refuses the fake-globals format too falls back on its {@code VALUE} answer, which is CPython's own
     * last resort when neither format is implemented.
     */
    private static PyObject stringFallback(PyObject annotate) {
        PyObject fakeGlobals;
        try {
            fakeGlobals = Slots.call(
                    annotate, new PyObject[] {PyFormat.VALUE_WITH_FAKE_GLOBALS}, null);
        } catch (PyRaise refusal) {
            return annotationsToString(callAnnotateFunction(annotate, PyFormat.VALUE));
        }
        return annotationsToString(fakeGlobals);
    }

    // --- annotations_to_string and type_repr ------------------------------------------------

    private static PyObject annotationsToStringCalled(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "annotations_to_string", args, kwNames, ANNOTATIONS_NAMES, 1, 1);
        return annotationsToString(values[0]);
    }

    /**
     * {@code annotations_to_string(d)}: the same keys, and each value as the text a person would write.
     *
     * <p>A string is already that text and passes through; everything else is {@link #typeRepr}. The
     * mapping is asked for {@code items}, so a value that is not a mapping is refused by the type it is.
     */
    private static PyObject annotationsToString(PyObject annotations) {
        if (!(annotations instanceof PyDict dict)) {
            throw Slots.attributeError(
                    "'%s' object has no attribute 'items'", annotations.getType().name());
        }
        PyDict converted = PyDict.empty();
        for (Map.Entry<PyObject, PyObject> entry : dict.getItems().entrySet()) {
            PyObject value = entry.getValue();
            converted.put(entry.getKey(),
                    value instanceof PyStr ? value : PyStr.of(typeRepr(value)));
        }
        return converted;
    }

    private static PyObject typeReprCalled(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords("type_repr", args, kwNames, VALUE_NAMES, 1, 1);
        return PyStr.of(typeRepr(values[0]));
    }

    /**
     * {@code type_repr(value)}: a type or function as the text a name would be written with.
     *
     * <p>The module qualifies the name except for {@code builtins}, where the bare name is what a person
     * writes. A built-in function has no {@code __module__} of its own, so the module is the one its
     * {@code __self__} belongs to, which is how {@code len} answers {@code len} and {@code list.append}
     * answers {@code list.append}. Everything else is its {@code repr}, with {@code Ellipsis} the one value
     * whose written form is not that repr.
     */
    private static String typeRepr(PyObject value) {
        if (value instanceof PyType || value instanceof PyFunction || value instanceof PyNativeFunction) {
            String module = text(Slots.findAttr(value, "__module__"));
            if (module == null) {
                PyObject self = Slots.findAttr(value, "__self__");
                if (self != null && self != PyNone.INSTANCE) {
                    PyObject owner = self instanceof PyType ? self : self.getType();
                    module = text(Slots.findAttr(owner, "__module__"));
                }
            }
            PyObject qualname = Slots.findAttr(value, "__qualname__");
            String name = qualname instanceof PyStr str ? str.getValue() : value.repr();
            return module == null || module.equals("builtins") ? name : module + "." + name;
        }
        if (value == PyEllipsis.INSTANCE) {
            return "...";
        }
        return value.repr();
    }

    private static String text(PyObject value) {
        return value instanceof PyStr str ? str.getValue() : null;
    }

    // --- get_annotate_from_class_namespace --------------------------------------------------

    private static PyObject getAnnotateFromClassNamespace(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "get_annotate_from_class_namespace", args, kwNames, NAMESPACE_NAMES, 1, 1);
        PyObject namespace = values[0];
        PyObject annotate;
        try {
            annotate = Slots.getItem(namespace, PyStr.interned("__annotate__"));
        } catch (PyRaise refusal) {
            if (!refusal.value().exceptionType().matches(ExceptionTypes.KEY_ERROR)) {
                throw refusal;
            }
            annotate = null;
        }
        if (annotate != null) {
            return annotate;
        }
        try {
            return Slots.getItem(namespace, PyStr.interned("__annotate_func__"));
        } catch (PyRaise refusal) {
            if (refusal.value().exceptionType().matches(ExceptionTypes.KEY_ERROR)) {
                return PyNone.INSTANCE;
            }
            throw refusal;
        }
    }

    // --- formats ---------------------------------------------------------------------------

    /** {@code format == member}, the comparison an {@code IntEnum} is matched with. */
    private static boolean isFormat(PyObject format, PyFormat member) {
        return Slots.compareTrue(CompareOp.EQ, format, member);
    }
}
