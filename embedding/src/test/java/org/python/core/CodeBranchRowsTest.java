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
 * The rule behind {@code code.co_branches()}, measured against CPython 3.14.10.
 *
 * <p>Corpus case {@code 126_code_branches.py} prints the rows of fourteen bodies, the opcode each of them
 * starts on and whether the same three offsets land inside a line run; every one of those answers is an
 * agreement with the reference. Pinned here is the walk that produces them, in the only terms a script
 * cannot use: the six opcodes with two ways named beside the four that jump with one, the {@code
 * END_ASYNC_FOR} row shown to start two units behind the instruction that decides it, the rows read off a
 * code object whose line table has been emptied from under it, and a jump past 255 units answered as the one
 * folded distance the array splits across two bytes.
 *
 * <p>The cursor is pinned beside them. CPython's branches iterator is a second {@code PyTypeObject} carrying
 * the first one's {@code tp_name}, so the three cursors are three types with two names between them — a fact
 * about the types rather than about the row that hands them out. Each of the three files three rows in its own
 * book, the two slots and the null sentence beside them, which is what slice 78 landed and {@link
 * CursorBooksTest} measures; this case keeps the book pinned from the row's side as well.
 *
 * <p>The tests live in this module for the reason {@link CodeStorageHalfTest} gives: a code object is only
 * worth reading once something compiled it, and decision D2 keeps the compiler out of {@code org.python.
 * core}.
 */
class CodeBranchRowsTest {

    @TempDir
    Path work;

    /**
     * The six opcodes a row starts on: {@code FOR_ITER}, the four tests, and the {@code END_SEND} an async
     * test's row is read from — the walk names the instruction its argument points back to rather than the
     * {@code END_ASYNC_FOR} that decides.
     */
    private static final List<Integer> ROW_SOURCES =
            List.of(
                    Opcodes.FOR_ITER,
                    Opcodes.POP_JUMP_IF_FALSE,
                    Opcodes.POP_JUMP_IF_TRUE,
                    Opcodes.POP_JUMP_IF_NONE,
                    Opcodes.POP_JUMP_IF_NOT_NONE,
                    Opcodes.END_SEND);

    /** The four tests of the six, which are all a row is read for in a body that loops nowhere. */
    private static final List<Integer> TESTS =
            List.of(
                    Opcodes.POP_JUMP_IF_FALSE,
                    Opcodes.POP_JUMP_IF_TRUE,
                    Opcodes.POP_JUMP_IF_NONE,
                    Opcodes.POP_JUMP_IF_NOT_NONE);

    /** The jumps that go one way, and so name no fallthrough for a row to answer. */
    private static final List<Integer> ONE_WAY =
            List.of(
                    Opcodes.JUMP_FORWARD,
                    Opcodes.JUMP_BACKWARD,
                    Opcodes.JUMP_BACKWARD_NO_INTERRUPT,
                    Opcodes.SEND);

    /** The bodies the walk is asked about, each of them a different way through the six. */
    private static final List<String> BODIES =
            List.of("jump", "loops", "noneish", "tried", "matched", "counted", "gen", "aiter", "closure");

    /** The body a branch is stretched past one argument byte with: an {@code if} over 32 units of work. */
    private static final String FAR_BODY =
            "def far(flag):\n"
                    + "    x = 0\n"
                    + "    if flag:\n"
                    + "        x += 1\n".repeat(32)
                    + "    return x\n";

    private static final String PROGRAM =
            """
            def jump(a, b):
                if a:
                    b = 1
                return a or b

            def loops(n):
                total = 0
                for i in range(n):
                    if i:
                        total += 1
                while total:
                    total -= 1
                return total

            def noneish(a):
                if a is None:
                    return 1
                if a is not None:
                    return 2
                return 3

            def tried(a):
                try:
                    return a[0]
                except TypeError:
                    return None

            def matched(command):
                match command:
                    case ["go", n]:
                        return n
                    case {"key": k}:
                        return k
                    case other:
                        return other

            def counted(n):
                return [i for i in range(n) if i]

            def gen(n):
                for i in range(n):
                    yield i

            async def aiter(rows):
                total = 0
                async for row in rows:
                    total += 1
                return total

            def closure(a):
                def inner():
                    if a:
                        return 1
                    return 2

                return inner
            """
                    + FAR_BODY;

    private PyModule module;

    /** {@code code.__dict__[name]}: the row the book files, or null where it files no such name. */
    private static PyObject entry(String name) {
        return TypeDict.declared(PyType.CODE).get(PyStr.interned(name));
    }

    /** {@code code.replace(name = value)}, the only route to a body with one table swapped out. */
    private static PyObject changed(PyObject code, String name, PyObject value) {
        return Slots.call(
                Slots.getAttr(code, "replace"), new PyObject[] {value}, PyTuple.of(PyStr.of(name)));
    }

    private static List<PyObject> items(PyTuple tuple) {
        List<PyObject> all = new ArrayList<>();
        for (int at = 0; at < tuple.size(); at++) {
            all.add(tuple.at(at));
        }
        return all;
    }

    /** The rows as Java numbers, in the order the walk writes them. */
    private static List<List<Integer>> rowsOf(PyObject code) {
        List<List<Integer>> all = new ArrayList<>();
        for (PyObject item : items(((PyCode) code).branches())) {
            PyTuple row = (PyTuple) item;
            List<Integer> offsets = new ArrayList<>();
            for (int column = 0; column < row.size(); column++) {
                offsets.add(((PyInt) row.at(column)).getValue().intValue());
            }
            all.add(offsets);
        }
        return all;
    }

    /** The code array, which beside the cache widths is all the walk is given. */
    private static byte[] octetsOf(PyObject code) {
        return ((PyBytes) Slots.getAttr(code, "co_code")).getValue();
    }

    private static int opcodeAt(byte[] stream, int offset) {
        return stream[offset] & 0xff;
    }

    /** The opcodes an array holds at every one of its units, caches and extensions included. */
    private static List<Integer> unitsIn(byte[] stream) {
        List<Integer> opcodes = new ArrayList<>();
        for (int at = 0; at < stream.length; at += 2) {
            opcodes.add(opcodeAt(stream, at));
        }
        return opcodes;
    }

    /** The instruction each of a body's rows starts on, named by its opcode. */
    private static List<Integer> sourcesOf(PyObject code) {
        byte[] stream = octetsOf(code);
        List<Integer> seen = new ArrayList<>();
        for (List<Integer> row : rowsOf(code)) {
            seen.add(opcodeAt(stream, row.get(0)));
        }
        return seen;
    }

    /** The cursor the row hands back, walked to the end. */
    private static List<PyObject> walked(PyObject cursor) {
        List<PyObject> all = new ArrayList<>();
        PyObject item;
        while ((item = ((PyIterator) cursor).nextOrNull()) != null) {
            all.add(item);
        }
        return all;
    }

    /** {@code fn.__code__} for the named function of the program, the way a value reaches it. */
    private PyObject codeOf(String name) throws IOException {
        if (module == null) {
            Cpython314Compiler.Compiled compiled =
                    Cpython314Compiler.compileFile(Files.writeString(work.resolve("branches.py"), PROGRAM));
            module = new PyModule("workshop");
            PyRuntime.exec(compiled.code(), module, compiled.fileName());
        }
        PyObject function =
                PyRuntime.eval(Cpython314Compiler.compileExpression(name, module.name()), module);
        return Slots.getAttr(function, "__code__");
    }

    /**
     * Every row is three even byte offsets, and it starts on one of the six instructions a row is read for:
     * the four one-way jumps are named in the same breath as the rows that refuse them, and the async test
     * is not a source at all but the instruction one row points back at.
     */
    @Test
    void everyRowIsThreeByteOffsetsStartingOnAnInstructionThatHasTwoWays() throws IOException {
        for (String name : BODIES) {
            PyObject code = codeOf(name);
            List<Integer> sources = sourcesOf(code);

            assertThat(ROW_SOURCES).as(name).containsAll(sources);
            assertThat(sources).as(name).doesNotContainAnyElementsOf(ONE_WAY);
            assertThat(sources).as(name + " reads the async row backwards")
                    .doesNotContain(Opcodes.END_ASYNC_FOR);

            for (List<Integer> row : rowsOf(code)) {
                assertThat(row).as(name + " row, three offsets").hasSize(3);
                for (int offset : row) {
                    assertThat(offset % 2).as(name + " offset counted in bytes").isZero();
                    assertThat(offset).as(name + " offset").isNotNegative();
                }
            }
        }
    }

    /**
     * The {@code JUMP_BACKWARD} that closes a loop is in the array of the two bodies that loop and in
     * neither of their rows, and a {@code match} that jumps into six case blocks answers six rows that all
     * start on its own tests rather than on anything inside a block.
     */
    @Test
    void aJumpWithOneWayIsInTheArrayAndNotInTheRows() throws IOException {
        for (String name : List.of("loops", "gen")) {
            PyObject code = codeOf(name);
            assertThat(unitsIn(octetsOf(code))).as(name + " jumps back").contains(Opcodes.JUMP_BACKWARD);
            assertThat(sourcesOf(code)).as(name + " rows").doesNotContain(Opcodes.JUMP_BACKWARD);
        }

        PyObject code = codeOf("matched");
        assertThat(rowsOf(code)).hasSize(6);
        assertThat(TESTS).as("every row of a match starts on a test").containsAll(sourcesOf(code));
        assertThat(unitsIn(octetsOf(code))).as("no loop to read").doesNotContain(Opcodes.FOR_ITER);
    }

    /** Two tests written on one value give the two rows the reference gives, in the order the body runs. */
    @Test
    void twoTestsOnOneValueAnswerTwoRowsInWalkingOrder() throws IOException {
        PyObject code = codeOf("noneish");

        assertThat(rowsOf(code))
                .containsExactly(List.of(4, 10, 14), List.of(16, 22, 26));
        assertThat(sourcesOf(code))
                .containsExactly(Opcodes.POP_JUMP_IF_NOT_NONE, Opcodes.POP_JUMP_IF_NONE);
        // The way a test falls through to is not the unit behind it: a NOT_TAKEN is left in its place.
        byte[] stream = octetsOf(code);
        assertThat(opcodeAt(stream, rowsOf(code).get(0).get(1) - 2)).isEqualTo(Opcodes.NOT_TAKEN);
    }

    /**
     * The {@code END_ASYNC_FOR} row read backwards: the source is the {@code END_SEND} its argument names,
     * the fallthrough is the two units past that and its {@code NOT_TAKEN}, and the instruction that decides
     * the way sits immediately before where jumping begins rather than at the source.
     */
    @Test
    void anAsyncBranchNamesTheEndSendItReturnsToRatherThanTheTestItself() throws IOException {
        PyObject code = codeOf("aiter");
        byte[] stream = octetsOf(code);
        List<List<Integer>> rows = rowsOf(code);

        assertThat(rows).hasSize(1);
        List<Integer> row = rows.get(0);
        assertThat(opcodeAt(stream, row.get(0))).isEqualTo(Opcodes.END_SEND);
        assertThat(opcodeAt(stream, row.get(0) + 2)).isEqualTo(Opcodes.NOT_TAKEN);
        assertThat(row.get(1) - row.get(0)).isEqualTo(4);
        assertThat(opcodeAt(stream, row.get(2) - 2)).isEqualTo(Opcodes.END_ASYNC_FOR);
        assertThat(row.get(2) - row.get(1)).isGreaterThan(4);
    }

    /**
     * A jump no single argument byte could name, answered as one distance: the extension is written in front
     * of the instruction and the row carries the folded total rather than either byte of it.
     */
    @Test
    void aBranchFurtherThanAnArgumentByteAllowsIsAnsweredFolded() throws IOException {
        PyObject code = codeOf("far");
        byte[] stream = octetsOf(code);
        List<List<Integer>> rows = rowsOf(code);

        assertThat(rows).hasSize(1);
        List<Integer> row = rows.get(0);
        assertThat(row.get(0)).isGreaterThan(2);
        assertThat(opcodeAt(stream, row.get(0) - 2)).isEqualTo(Opcodes.EXTENDED_ARG);
        assertThat(unitsIn(stream).stream().filter(at -> at == Opcodes.EXTENDED_ARG).count())
                .as("the array carries the extension once")
                .isEqualTo(1);
        // 289 units is more than the 255 one byte names and less than what two of them fold to.
        assertThat((row.get(2) - row.get(1)) / 2).isBetween(256, 511);
    }

    /**
     * The rows are a reading of the code array alone: take the line table out from under a body and the two
     * position cursors go empty while the rows stay, and the same for a handler table borrowed from another
     * body.
     */
    @Test
    void theRowsOutliveTheTablesTheyNeverRead() throws IOException {
        PyObject code = codeOf("loops");
        List<List<Integer>> before = rowsOf(code);
        assertThat(before).isNotEmpty();

        PyObject noLines = changed(code, "co_linetable", PyBytes.wrap(new byte[0]));
        assertThat(items(((PyCode) noLines).lineRanges())).isEmpty();
        assertThat(items(((PyCode) noLines).positions())).isEmpty();
        assertThat(rowsOf(noLines)).isEqualTo(before);

        PyObject foreign =
                changed(
                        code,
                        "co_exceptiontable",
                        Slots.getAttr(codeOf("tried"), "co_exceptiontable"));
        assertThat(Slots.getAttr(foreign, "co_exceptiontable"))
                .as("the swap reached the copy")
                .isNotEqualTo(Slots.getAttr(code, "co_exceptiontable"));
        assertThat(rowsOf(foreign)).isEqualTo(before);
    }

    /**
     * The row is registered the way {@code code_methods} registers the other two cursors — no sentence at
     * all — and its cursor is a type of its own that CPython names {@code line_iterator} anyway.
     */
    @Test
    void theThreeCursorsAreThreeTypesCarryingTwoNames() throws IOException {
        PyObject code = codeOf("jump");
        assertThat(entry("co_branches")).isNotNull();
        assertThat(Slots.getAttr(entry("co_branches"), "__doc__")).isEqualTo(PyNone.INSTANCE);

        PyObject lines = Slots.call(Slots.getAttr(code, "co_lines"), new PyObject[0], null);
        PyObject branches = Slots.call(Slots.getAttr(code, "co_branches"), new PyObject[0], null);
        PyObject positions = Slots.call(Slots.getAttr(code, "co_positions"), new PyObject[0], null);

        assertThat(branches.getType()).isSameAs(PyType.BRANCHES_ITERATOR);
        assertThat(PyType.BRANCHES_ITERATOR).isNotSameAs(PyType.LINE_ITERATOR);
        assertThat(PyType.BRANCHES_ITERATOR).isNotSameAs(PyType.POSITIONS_ITERATOR);
        assertThat(List.of(lines.getType().name(), branches.getType().name(), positions.getType().name()))
                .containsExactly("line_iterator", "line_iterator", "positions_iterator");
        // Two siblings under object, so neither is an instance of the other: the name is all they share.
        assertThat(PyType.BRANCHES_ITERATOR.parent()).isSameAs(PyType.OBJECT);
        assertThat(PyType.LINE_ITERATOR.parent()).isSameAs(PyType.OBJECT);

        assertThat(walked(branches)).isEqualTo(items(((PyCode) code).branches()));
        assertThat(Slots.getIter(branches)).isSameAs(branches);
        assertThat(walked(Slots.call(Slots.getAttr(code, "co_branches"), new PyObject[0], null)))
                .as("a second call walks from the top")
                .isEqualTo(items(((PyCode) code).branches()));
    }

    /**
     * The book the row's cursor keeps: the two slots beside the sentence, which is all the reference declares
     * for it, and the three rows a sequence cursor files beside those still absent. {@link CursorBooksTest}
     * pins the book at length; this pins it from the row's side, since the row is what hands the cursor out.
     */
    @Test
    void aCursorFilesItsTwoSlotsBesideItsSentenceAndNothingElse() throws IOException {
        for (PyType cursor :
                List.of(PyType.LINE_ITERATOR, PyType.BRANCHES_ITERATOR, PyType.POSITIONS_ITERATOR)) {
            List<String> filed = new ArrayList<>();
            for (PyObject key : TypeDict.declared(cursor).keys()) {
                filed.add(((PyStr) key).getValue());
            }
            assertThat(filed).as(cursor.name()).containsExactly("__iter__", "__next__", "__doc__");
            for (String row : List.of("__length_hint__", "__reduce__", "__setstate__")) {
                assertThat(TypeDict.declared(cursor).get(PyStr.interned(row)))
                        .as(cursor.name() + " files no " + row)
                        .isNull();
            }
        }

        PyObject branches = Slots.call(Slots.getAttr(codeOf("jump"), "co_branches"), new PyObject[0], null);
        assertThat(Slots.getAttr(branches, "__next__").getType().name()).isEqualTo("method-wrapper");
        assertThat(walked(branches)).isEqualTo(items(((PyCode) codeOf("jump")).branches()));
        assertThatThrownBy(() -> Slots.getAttr(branches, "__length_hint__"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised)
                                        .hasMessage(
                                                "AttributeError: 'line_iterator' object has no"
                                                        + " attribute '__length_hint__'"));
    }
}
