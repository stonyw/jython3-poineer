package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The eleven argument tables welded onto the front of a built-in type's sentence, and the copy a class
 * statement makes of its own docstring, measured against CPython 3.14.10.
 *
 * <p>Corpus case 118 reads both halves of each text from Python, down to the characters the cut leaves under
 * its rule. What is pinned here is the store the two reads share. A built-in type keeps one string: the table,
 * the rule, then the sentence — and {@code __doc__} is what is left once {@code
 * _PyType_DocWithoutSignature} throws the first two away, not a second text anyone transcribed beside them.
 * Eleven of the types in {@code builtins} are written that way, and seven more open with a bracket and end
 * with an arrow, which is the shape {@code skip_signature} refuses: no rule, so no table, so the sentence
 * arrives whole.
 *
 * <p>The other half is the copy {@code type_new_impl} makes when the namespace it is handed files {@code
 * __doc__} as a string. It happens once, at the build, and nothing writes the field again — so a class reads
 * its own docstring through both knives, keeps the string entire at {@code __doc__} and in its own dictionary,
 * and still reports the table of the text it was built with after a program rewrites the name. The name itself
 * is read live, and only the half of it after the last dot: {@code __qualname__} never counts.
 */
class BuiltinTextSignatureTableTest {

    /** A built-in type and the bracketed half {@code __text_signature__} answers out of its sentence. */
    private record Welded(PyType kind, String table) {}

    /** The eleven types whose {@code tp_doc} opens with an argument table and the rule under it. */
    private static final List<Welded> ELEVEN =
            List.of(
                    new Welded(PyType.BOOL, "(object=False, /)"),
                    new Welded(PyType.FLOAT, "(x=0, /)"),
                    new Welded(PyType.COMPLEX, "(real=0, imag=0)"),
                    new Welded(PyType.LIST, "(iterable=(), /)"),
                    new Welded(PyType.TUPLE, "(iterable=(), /)"),
                    new Welded(PyType.SET, "(iterable=(), /)"),
                    new Welded(PyType.FROZENSET, "(iterable=(), /)"),
                    new Welded(PyType.PROPERTY, "(fget=None, fset=None, fdel=None, doc=None)"),
                    new Welded(PyType.CLASS_METHOD, "(function, /)"),
                    new Welded(PyType.STATIC_METHOD, "(function, /)"),
                    new Welded(PyType.OBJECT, "()"));

    /**
     * The seven whose sentences open like a table and carry no rule, so both reads answer the same text and
     * {@code __text_signature__} answers {@code None}.
     */
    private static final List<PyType> RECIPES =
            List.of(
                    PyType.INT,
                    PyType.STR,
                    PyType.BYTES,
                    PyType.DICT,
                    PyType.SLICE,
                    PyType.RANGE,
                    PyType.TYPE);

    /** The three lines {@code skip_signature} looks for between a table and the sentence under it. */
    private static final String RULE = "\n--\n\n";

    /** {@code type(name, (), {members})}, the three-argument creator asked to write a class. */
    private static PyType declared(String name, Object... members) {
        PyDict dict = PyDict.empty();
        for (int at = 0; at < members.length; at += 2) {
            dict.put(PyStr.interned((String) members[at]), (PyObject) members[at + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {PyStr.of(name), PyTuple.of(), dict},
                        null);
    }

    /** What a class's own dictionary files under a name, which no read of the type cuts. */
    private static PyObject filed(PyType kind, String name) {
        return kind.namespace().get(PyStr.interned(name));
    }

    /** The sentence under a welded type's rule, which is what {@code __doc__} answers. */
    private static String sentence(Welded welded) {
        String stored = welded.kind().docText();
        return stored.substring(
                welded.kind().name().length() + welded.table().length() + RULE.length());
    }

    @Test
    void oneStringCarriesTheTableTheRuleAndTheSentence() {
        for (Welded welded : ELEVEN) {
            assertThat(welded.kind().docText())
                    .as("%s keeps its table above its own sentence", welded.kind().name())
                    .startsWith(welded.kind().name() + welded.table() + RULE);
        }
    }

    @Test
    void theTwoReadsCutOneText() {
        for (Welded welded : ELEVEN) {
            String stored = welded.kind().docText();
            assertThat(PyNativeFunction.signatureOf(welded.kind().name(), stored))
                    .as("%s answers nothing but the table", welded.kind().name())
                    .isEqualTo(PyStr.of(welded.table()));
            assertThat(PyNativeFunction.docOf(welded.kind().name(), stored))
                    .as("%s answers everything under the rule", welded.kind().name())
                    .isEqualTo(PyStr.of(sentence(welded)));
        }
    }

    @Test
    void bothReadsAnswerThroughTheTypeItself() {
        for (Welded welded : ELEVEN) {
            assertThat(Slots.getAttr(welded.kind(), "__text_signature__"))
                    .as("%s reports its table to a program", welded.kind().name())
                    .isEqualTo(PyStr.of(welded.table()));
            assertThat(Slots.getAttr(welded.kind(), "__doc__"))
                    .as("%s reports its sentence to a program", welded.kind().name())
                    .isEqualTo(PyStr.of(sentence(welded)));
        }
    }

    @Test
    void aRecipeOpensLikeATableAndIsNotOne() {
        for (PyType kind : RECIPES) {
            String stored = kind.docText();
            assertThat(stored).as("%s says something", kind.name()).isNotNull();
            assertThat(PyNativeFunction.signatureOf(kind.filedName(), stored))
                    .as("%s has no rule to cut at", kind.name())
                    .isSameAs(PyNone.INSTANCE);
            assertThat(PyNativeFunction.docOf(kind.filedName(), stored))
                    .as("%s keeps its whole sentence", kind.name())
                    .isEqualTo(PyStr.of(stored));
            assertThat(Slots.getAttr(kind, "__text_signature__"))
                    .as("%s reports no table", kind.name())
                    .isSameAs(PyNone.INSTANCE);
        }
    }

    @Test
    void aBlankLineReachesTheReaderBeforeTheRuleDoes() {
        String guarded = "Blank(a)\n\n--\n\nA sentence the guard leaves whole.\n";
        assertThat(PyNativeFunction.signatureOf("Blank", guarded)).isSameAs(PyNone.INSTANCE);
        assertThat(PyNativeFunction.docOf("Blank", guarded)).isEqualTo(PyStr.of(guarded));
    }

    @Test
    void onlyTheLastComponentOfTheNameIsSought() {
        assertThat(PyNativeFunction.signatureOf("Outer.In", "In(x)\n--\n\nBody.\n"))
                .isEqualTo(PyStr.of("(x)"));
        assertThat(PyNativeFunction.signatureOf("Dotted.In", "Dotted.In(x)\n--\n\nBody.\n"))
                .isSameAs(PyNone.INSTANCE);
    }

    @Test
    void aClassCopiesItsOwnSentenceAtTheBuild() {
        String stated = "Plain(a, b)\n--\n\nA body the class statement wrote.\n";
        PyType kind = declared("Plain", "__doc__", PyStr.of(stated));
        assertThat(kind.docText()).isEqualTo(stated);
        assertThat(Slots.getAttr(kind, "__text_signature__")).isEqualTo(PyStr.of("(a, b)"));
        assertThat(Slots.getAttr(kind, "__doc__")).isEqualTo(PyStr.of(stated));
        assertThat(filed(kind, "__doc__")).isEqualTo(PyStr.of(stated));
    }

    @Test
    void aLaterWriteLeavesTheCopyAlone() {
        String stated = "Late(a)\n--\n\nThe sentence the build saw.\n";
        String rewritten = "Late(z)\n--\n\nThe sentence written afterwards.\n";
        PyType kind = declared("Late", "__doc__", PyStr.of(stated));
        Slots.setAttr(kind, "__doc__", PyStr.of(rewritten));
        assertThat(kind.docText()).isEqualTo(stated);
        assertThat(Slots.getAttr(kind, "__doc__")).isEqualTo(PyStr.of(rewritten));
        assertThat(filed(kind, "__doc__")).isEqualTo(PyStr.of(rewritten));
        assertThat(Slots.getAttr(kind, "__text_signature__")).isEqualTo(PyStr.of("(a)"));
    }

    @Test
    void nothingThatIsNotTextIsCopied() {
        PyType number = declared("NotText", "__doc__", PyInt.of(42));
        assertThat(number.docText()).isNull();
        assertThat(Slots.getAttr(number, "__text_signature__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(number, "__doc__")).isEqualTo(PyInt.of(42));

        PyObject held =
                PyType.PROPERTY.call(
                        new PyObject[] {
                            PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE
                        },
                        null);
        PyType descriptor = declared("Described", "__doc__", held);
        assertThat(descriptor.docText()).isNull();
        assertThat(Slots.getAttr(descriptor, "__text_signature__")).isSameAs(PyNone.INSTANCE);
        // The row is reached as a descriptor and the class is read as its owner, which hands it back undressed.
        assertThat(Slots.getAttr(descriptor, "__doc__")).isSameAs(held);

        PyType silent = declared("NoDoc");
        assertThat(silent.docText()).isNull();
        assertThat(Slots.getAttr(silent, "__text_signature__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(silent, "__doc__")).isSameAs(PyNone.INSTANCE);
    }

    @Test
    void aRenamedClassIsReadUnderTheNameItAnswers() {
        PyType kind = declared("Renamed", "__doc__", PyStr.of("Gaze(a)\n--\n\nBody.\n"));
        assertThat(Slots.getAttr(kind, "__text_signature__")).isSameAs(PyNone.INSTANCE);
        Slots.setAttr(kind, "__name__", PyStr.of("Gaze"));
        assertThat(Slots.getAttr(kind, "__text_signature__")).isEqualTo(PyStr.of("(a)"));
        assertThat(Slots.getAttr(kind, "__qualname__")).isEqualTo(PyStr.of("Renamed"));
    }

    @Test
    void aNestedClassIsReadUnderItsShortName() {
        PyType qualified =
                declared(
                        "In",
                        "__qualname__",
                        PyStr.of("Dotted.In"),
                        "__doc__",
                        PyStr.of("Dotted.In(x)\n--\n\nWritten under a name never read.\n"));
        assertThat(qualified.filedName()).isEqualTo("In");
        assertThat(Slots.getAttr(qualified, "__text_signature__")).isSameAs(PyNone.INSTANCE);

        PyType tail =
                declared(
                        "Nested",
                        "__qualname__",
                        PyStr.of("Holder.Nested"),
                        "__doc__",
                        PyStr.of("Nested(q)\n--\n\nWritten under the name that is read.\n"));
        assertThat(Slots.getAttr(tail, "__text_signature__")).isEqualTo(PyStr.of("(q)"));
    }
}
