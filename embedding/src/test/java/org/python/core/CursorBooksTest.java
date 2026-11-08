package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * The three books the location cursors file for themselves, measured against CPython 3.14.10.
 *
 * <p>Corpus case {@code 128_cursor_books.py} reads each book from Python and prints what the reference prints:
 * three rows, the two sentences the wrappers carry, the refusals a wrong receiver answers with, and the routes
 * that try to make a cursor. Pinned here is the half a script cannot point at. The first of it is that three
 * books are one: {@code TypeDict} writes all three from one table, because {@code _PyLineIterator} ({@code
 * Objects/codeobject.c:1421}), {@code _PyPositionsIterator} ({@code :1518}) and {@code _PyBranchesIterator}
 * ({@code Objects/instrumentation.c:3155}) are declared with the same two {@code PyMethodDef} rows and with
 * {@code tp_members}, {@code tp_getset}, {@code tp_doc}, {@code tp_new} and {@code tp_dictoffset} all zero.
 * The two rows are copied out of the two slots, so the book is the two slots and the null sentence beside
 * them, and every one of the three answers the same because the same code writes all three.
 *
 * <p>Then the size of that book against the six rows a sequence cursor files. The two that are gone are
 * {@code __length_hint__} and {@code __reduce__}, so a cursor that walks a code object can neither be counted
 * nor pickled, and both refusals name a type that files no row rather than a method that failed.
 *
 * <p>Then the two types that share one name, which is a fact about Java objects rather than about a printed
 * sentence: {@code _PyBranchesIterator} copies {@code line_iterator} as its {@code tp_name}, so its rows and
 * the lines cursor's rows refuse each other with a message naming the type it wanted and the type it got, and
 * both of those read {@code line_iterator}.
 *
 * <p>The tests live in this module for the reason {@link CodeStorageHalfTest} gives: a cursor is only worth
 * reading once something compiled the code it walks, and decision D2 keeps the compiler out of {@code
 * org.python.core}.
 */
class CursorBooksTest {

    @TempDir
    Path work;

    /** The three rows each of the three books files, in the order the declaration writes them. */
    private static final List<String> ORDER = List.of("__iter__", "__next__", "__doc__");

    /** The two rows {@code tp_iter} and {@code tp_iternext} are copied out as. */
    private static final List<String> SLOTS = List.of("__iter__", "__next__");

    /** The three rows a sequence cursor files and these three leave out, in the order it files them. */
    private static final List<String> MISSING = List.of("__length_hint__", "__reduce__", "__setstate__");

    /** The three types whose books are one table, in the order the two C files write them. */
    private static final List<PyType> TYPES =
            List.of(PyType.LINE_ITERATOR, PyType.POSITIONS_ITERATOR, PyType.BRANCHES_ITERATOR);

    private static final String PROGRAM =
            """
            def jump(a, b):
                if a:
                    b = 1
                return a or b
            """;

    private static final PyObject ZERO = PyInt.ZERO;

    private static final PyTuple X = PyTuple.of(PyStr.interned("x"));

    private PyModule module;

    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** The entry one of the three types files under {@code name}. */
    private static PyObject entry(PyType owner, String name) {
        return TypeDict.declared(owner).get(PyStr.interned(name));
    }

    /** The names one type's own dictionary keeps, in the order it kept them. */
    private static List<String> filed(PyType owner) {
        List<String> names = new ArrayList<>();
        for (PyObject key : TypeDict.declared(owner).keys()) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** The names {@code dir()} of one of the three gains over {@code dir()} of {@code object}. */
    private static List<String> gained(PyType owner) {
        List<String> names = new ArrayList<>(Dirs.classNames(owner));
        names.removeAll(Dirs.classNames(PyType.OBJECT));
        return names;
    }

    private static PyObject called(PyObject callable, PyObject... args) {
        return Slots.call(callable, args, null);
    }

    /** A fresh cursor of {@code owner}'s own kind, made the only way the language makes it. */
    private PyObject cursor(PyType owner) throws IOException {
        PyObject code = code();
        if (owner == PyType.LINE_ITERATOR) {
            return Slots.call(Slots.getAttr(code, "co_lines"), new PyObject[0], null);
        }
        if (owner == PyType.POSITIONS_ITERATOR) {
            return Slots.call(Slots.getAttr(code, "co_positions"), new PyObject[0], null);
        }
        return Slots.call(Slots.getAttr(code, "co_branches"), new PyObject[0], null);
    }

    /** A list cursor, the neighbour whose book is six rows and whose refusals name the same two slots. */
    private static PyObject sequenceCursor() {
        return called(Slots.getAttr(PyList.of(ZERO), "__iter__"));
    }

    /** {@code jump.__code__}, the one body the three cursors are made from. */
    private PyObject code() throws IOException {
        if (module == null) {
            Cpython314Compiler.Compiled compiled =
                    Cpython314Compiler.compileFile(Files.writeString(work.resolve("cursors.py"), PROGRAM));
            module = new PyModule("workshop");
            PyRuntime.exec(compiled.code(), module, compiled.fileName());
        }
        PyObject jump =
                PyRuntime.eval(Cpython314Compiler.compileExpression("jump", module.name()), module);
        return Slots.getAttr(jump, "__code__");
    }

    /**
     * Three books of three rows apiece, where the range, sequence and hash-table cursors each file six: the
     * two rows that are gone are a length and a pickle, and the sentence is gone with them.
     */
    @Test
    void eachOfTheThreeBooksFilesThreeRowsAndNothingElse() throws IOException {
        for (PyType owner : TYPES) {
            String kind = owner.name();
            assertThat(filed(owner)).as(kind).isEqualTo(ORDER);
            assertThat(TypeDict.declared(owner).size()).as(kind).isEqualTo(3);
            for (String name : MISSING) {
                assertThat(entry(owner, name)).as(kind + " files no " + name).isNull();
            }
            assertThat(entry(owner, "__new__")).as(kind + " files no __new__").isNull();
            assertThat(Dirs.valueNames(cursor(owner))).as(kind).hasSize(26);
            assertThat(gained(owner)).as(kind).containsExactly("__iter__", "__next__");
            assertThat(owner.docText()).as(kind).isNull();
            assertThat(Slots.getAttr(owner, "__doc__")).as(kind).isSameAs(PyNone.INSTANCE);
        }

        assertThat(filed(PyType.LIST_ITERATOR))
                .containsExactlyElementsOf(List.of("__iter__", "__next__", "__length_hint__", "__reduce__",
                        "__setstate__", "__doc__"));
        assertThat(Dirs.valueNames(sequenceCursor())).hasSize(28);
    }

    /**
     * The two live rows are the two slots copied out: {@code wrapper_descriptor}s carrying the two sentences
     * every cursor in the interpreter shares, naming their own type in {@code __qualname__} and {@code
     * __objclass__}, and filing no {@code __module__} at all.
     */
    @Test
    void theTwoRowsAreTheTwoSlotsCopiedOutAndTheThirdRowIsNone() throws IOException {
        for (PyType owner : TYPES) {
            String kind = owner.name();
            for (String name : SLOTS) {
                PyObject row = entry(owner, name);
                assertThat(row.getType().name()).as(kind + "." + name).isEqualTo("wrapper_descriptor");
                assertThat(Slots.getAttr(row, "__doc__"))
                        .as(kind + "." + name)
                        .isEqualTo(PyStr.of(name.equals("__iter__")
                                ? "Implement iter(self)." : "Implement next(self)."));
                assertThat(Slots.getAttr(row, "__text_signature__"))
                        .as(kind + "." + name)
                        .isEqualTo(PyStr.of("($self, /)"));
                assertThat(Slots.getAttr(row, "__qualname__"))
                        .as(kind + "." + name)
                        .isEqualTo(PyStr.of(kind + "." + name));
                assertThat(Slots.getAttr(row, "__objclass__")).as(kind + "." + name).isSameAs(owner);
                refuses(
                        "AttributeError: 'wrapper_descriptor' object has no attribute '__module__'",
                        () -> Slots.getAttr(row, "__module__"));
            }
            assertThat(Slots.getAttr(entry(owner, "__doc__"), "__class__"))
                    .as(kind + " files None, not a string")
                    .isSameAs(PyType.NONE);
        }

        // One table, three books: the same two sentences, three pairs of objects that are not one another.
        for (String name : SLOTS) {
            for (int a = 0; a < TYPES.size(); a++) {
                for (int b = a + 1; b < TYPES.size(); b++) {
                    assertThat(entry(TYPES.get(b), name))
                            .as(name + " of " + TYPES.get(a).name() + " and " + TYPES.get(b).name())
                            .isNotSameAs(entry(TYPES.get(a), name));
                    assertThat(Slots.getAttr(entry(TYPES.get(b), name), "__doc__"))
                            .isEqualTo(Slots.getAttr(entry(TYPES.get(a), name), "__doc__"));
                }
            }
        }
    }

    /** {@code tp_iter} is {@code PyObject_SelfIter} in all three, which is what makes {@code iter(it)} be it. */
    @Test
    void theIterRowHandsBackTheCursorItWasGiven() throws IOException {
        for (PyType owner : TYPES) {
            String kind = owner.name();
            PyObject fresh = cursor(owner);
            assertThat(Slots.getIter(fresh)).as(kind).isSameAs(fresh);
            assertThat(called(entry(owner, "__iter__"), fresh)).as(kind).isSameAs(fresh);
            assertThat(Slots.getAttr(fresh, "__iter__").getType().name())
                    .as(kind)
                    .isEqualTo("method-wrapper");
            assertThat(Slots.getAttr(Slots.getAttr(fresh, "__iter__"), "__self__"))
                    .as(kind)
                    .isSameAs(fresh);
            assertThat(Slots.getAttr(fresh, "__next__").getType().name()).as(kind)
                    .isEqualTo("method-wrapper");
        }
    }

    /**
     * A row asked to work on a foreign receiver refuses by two routes with two different sentences: {@code
     * descr_check} in {@code tp_descr_get} says "for ... objects doesn't apply to", and the {@code check_self}
     * written into the wrapper says "requires ... but received".
     */
    @Test
    void aRowRefusesAReceiverOfAnotherTypeTwoWays() throws IOException {
        for (PyType owner : TYPES) {
            String kind = owner.name();
            for (String name : SLOTS) {
                PyObject row = entry(owner, name);
                refuses(
                        "TypeError: descriptor '" + name + "' requires a '" + kind + "' object"
                                + " but received a 'int'",
                        () -> called(row, ZERO));
                refuses(
                        "TypeError: descriptor '" + name + "' for '" + kind + "' objects"
                                + " doesn't apply to a 'int' object",
                        () -> called(Slots.getAttr(row, "__get__"), ZERO, owner));
            }
            refuses(
                    "TypeError: descriptor '__next__' requires a '" + kind + "' object"
                            + " but received a 'code'",
                    () -> called(entry(owner, "__next__"), code()));
            refuses(
                    "TypeError: descriptor '__next__' requires a '" + kind + "' object"
                            + " but received a 'list_iterator'",
                    () -> called(entry(owner, "__next__"), sequenceCursor()));
        }
    }

    /**
     * The branches iterator is a second type wearing the first one's name, so the two rows that share the
     * sentence share nothing else: each belongs to its own book, each names its own {@code __objclass__}, and
     * each refuses a cursor of the other with a sentence that says {@code line_iterator} twice.
     */
    @Test
    void theTwoTypesCarryingOneNameRefuseEachOther() throws IOException {
        assertThat(PyType.LINE_ITERATOR).isNotSameAs(PyType.BRANCHES_ITERATOR);
        assertThat(PyType.LINE_ITERATOR.name()).isEqualTo(PyType.BRANCHES_ITERATOR.name());
        assertThat(PyType.LINE_ITERATOR.mro()).containsExactly(PyType.LINE_ITERATOR, PyType.OBJECT);
        assertThat(PyType.BRANCHES_ITERATOR.mro())
                .containsExactly(PyType.BRANCHES_ITERATOR, PyType.OBJECT);

        PyObject line = cursor(PyType.LINE_ITERATOR);
        PyObject branch = cursor(PyType.BRANCHES_ITERATOR);
        assertThat(PyType.LINE_ITERATOR.instanceOf(line)).isTrue();
        assertThat(PyType.LINE_ITERATOR.instanceOf(branch)).isFalse();
        assertThat(PyType.BRANCHES_ITERATOR.instanceOf(branch)).isTrue();
        assertThat(PyType.BRANCHES_ITERATOR.instanceOf(line)).isFalse();

        refuses(
                "TypeError: descriptor '__next__' requires a 'line_iterator' object"
                        + " but received a 'line_iterator'",
                () -> called(entry(PyType.LINE_ITERATOR, "__next__"), branch));
        refuses(
                "TypeError: descriptor '__next__' requires a 'line_iterator' object"
                        + " but received a 'line_iterator'",
                () -> called(entry(PyType.BRANCHES_ITERATOR, "__next__"), line));
        refuses(
                "TypeError: descriptor '__iter__' requires a 'line_iterator' object"
                        + " but received a 'line_iterator'",
                () -> called(entry(PyType.LINE_ITERATOR, "__iter__"), branch));
    }

    /**
     * Writing a row is refused at three levels, and the delete of a row says "cannot set" exactly as the set
     * does, since {@code type_setattro} has no other word for an immutable type.
     */
    @Test
    void aRowIsWritableAtNeitherTheClassNorTheValueNorTheProxy() throws IOException {
        PyObject value = cursor(PyType.LINE_ITERATOR);

        for (String name : SLOTS) {
            refuses(
                    "TypeError: cannot set '" + name + "' attribute of immutable type 'line_iterator'",
                    () -> Slots.setAttr(PyType.LINE_ITERATOR, name, ZERO));
            refuses(
                    "TypeError: cannot set '" + name + "' attribute of immutable type 'line_iterator'",
                    () -> Slots.delAttr(PyType.LINE_ITERATOR, name));
            refuses(
                    "AttributeError: 'line_iterator' object attribute '" + name + "' is read-only",
                    () -> Slots.setAttr(value, name, ZERO));
            refuses(
                    "AttributeError: 'line_iterator' object attribute '" + name + "' is read-only",
                    () -> Slots.delAttr(value, name));
        }
        for (String name : SLOTS) {
            refuses(
                    "TypeError: 'mappingproxy' object does not support item assignment",
                    () ->
                            Slots.setItem(
                                    Slots.getAttr(PyType.LINE_ITERATOR, "__dict__"),
                                    PyStr.interned(name),
                                    ZERO));
        }
        assertThat(filed(PyType.LINE_ITERATOR)).isEqualTo(ORDER);
        assertThat(entry(PyType.LINE_ITERATOR, "__next__").getType().name())
                .isEqualTo("wrapper_descriptor");
    }

    /**
     * {@code tp_new} is null, so the refusal comes before one argument is read: the call with none, with one,
     * and with a keyword all answer alike, and the inherited {@code __new__} — which is {@code object}'s, since
     * no book files one — is refused with them.
     */
    @Test
    void noneOfTheThreeTypesCanBeCalledIntoBeing() throws IOException {
        for (PyType owner : TYPES) {
            String kind = owner.name();
            String refusal = "TypeError: cannot create '" + kind + "' instances";

            assertThat(Slots.getAttr(owner, "__new__"))
                    .as(kind + " has no __new__ of its own")
                    .isSameAs(Slots.getAttr(PyType.OBJECT, "__new__"));
            refuses(refusal, () -> owner.call(new PyObject[0], null));
            refuses(refusal, () -> owner.call(new PyObject[] {ZERO}, null));
            refuses(refusal, () -> owner.call(new PyObject[] {ZERO}, X));
            refuses(refusal, () -> called(Slots.getAttr(owner, "__new__"), owner));
            refuses(refusal, () -> called(Slots.getAttr(PyType.OBJECT, "__new__"), owner));
        }

        // The sequence cursor is refused the same way, which is what makes this a family rather than a bug.
        refuses(
                "TypeError: cannot create 'list_iterator' instances",
                () -> PyType.LIST_ITERATOR.call(new PyObject[0], null));
    }

    /**
     * No length, no pickle, no dictionary: the two rows a sequence cursor files and these three do not are the
     * two a person asks a cursor for, so {@code len()} names the missing slot and {@code __reduce__} is not in
     * the book at all and the refusal comes out of {@code object} naming the type it was handed.
     */
    @Test
    void aLocationCursorIsNeitherCountableNorPicklableNorOwnable() throws IOException {
        for (PyType owner : TYPES) {
            String kind = owner.name();
            PyObject fresh = cursor(owner);

            refuses(
                    "TypeError: object of type '" + kind + "' has no len()",
                    () -> Slots.length(fresh));
            refuses(
                    "AttributeError: '" + kind + "' object has no attribute '__length_hint__'",
                    () -> Slots.getAttr(fresh, "__length_hint__"));
            refuses(
                    "TypeError: cannot pickle '" + kind + "' object",
                    () -> Slots.call(Slots.getAttr(fresh, "__reduce__"), new PyObject[0], null));
            refuses(
                    "AttributeError: '" + kind + "' object has no attribute '__dict__'",
                    () -> Slots.getAttr(fresh, "__dict__"));
            refuses(
                    "AttributeError: '" + kind + "' object has no attribute '__weakref__'",
                    () -> Slots.getAttr(fresh, "__weakref__"));
            assertThat(Slots.getAttr(fresh, "__doc__")).as(kind).isSameAs(PyNone.INSTANCE);
        }

        // The sequence cursor has both rows, and answers both without a word of refusal.
        PyObject list = sequenceCursor();
        assertThat(called(Slots.getAttr(list, "__length_hint__")).getType().name()).isEqualTo("int");
        assertThat(called(Slots.getAttr(list, "__reduce__")).getType().name()).isEqualTo("tuple");
    }

    /**
     * The walk is one-shot, and a spent cursor answers the same empty {@code StopIteration} however it is
     * asked — through the row, through the bound wrapper, or by the builtin — while a fresh one of the same
     * kind walks from the top again.
     */
    @Test
    void aSpentCursorRefusesTheSameWayThreeTimes() throws IOException {
        for (PyType owner : TYPES) {
            String kind = owner.name();
            PyObject spent = cursor(owner);
            int rows = walked(spent).size();
            assertThat(rows).as(kind).isPositive();

            refuses("StopIteration: ", () -> called(entry(owner, "__next__"), spent));
            refuses(
                    "StopIteration: ",
                    () -> Slots.call(Slots.getAttr(spent, "__next__"), new PyObject[0], null));
            assertThat(Slots.getIter(spent)).as(kind + " still answers iter").isSameAs(spent);

            PyObject again = cursor(owner);
            assertThat(walked(again)).as(kind + " walks from the top").hasSize(rows);
        }
    }

    /** Every row a walk answers, which for these three is a tuple of offsets or of positions. */
    private static List<PyObject> walked(PyObject cursor) {
        List<PyObject> rows = new ArrayList<>();
        PyObject item;
        while ((item = ((PyIterator) cursor).nextOrNull()) != null) {
            rows.add(item);
        }
        return rows;
    }
}
