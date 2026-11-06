package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;
import org.python.util.Jy3Process;

/**
 * {@code co_lnotab} cut back out of the runs {@code co_lines()} answers, against pairs the reference
 * interpreter wrote — measured against CPython 3.14.10.
 *
 * <p>The name cannot be printed differentially: the reference warns about it on every read ({@code co_lnotab
 * is deprecated, use co_lines instead}) and this runtime has no warning machinery to answer with, so the two
 * stdouts can never be compared. What is compared here is the bytes. They were captured from the reference
 * compiling {@code m3/lnotab_shapes.py} with {@code compile(source, name, "exec", dont_inherit=True)} — the
 * same call the front-end's driver makes — and each pair below is what that read answered for the shape the
 * file names.
 *
 * <p>The four shapes the width of a pair forces are the reason the file exists. A plain line writes one pair;
 * a statement whose column span pushes the address gap past a byte writes {@code (255, 0)} runs until the gap
 * fits and rides the remainder on the pair that names the line; a jump of a hundred and forty lines writes
 * {@code (gap, 127)} then {@code (0, 127)} then what is left; and the jump back from a loop's last statement
 * to its own line writes {@code -128} runs, which is the one direction a signed byte carries further than
 * the other. The module's table opens by walking back to line nought, because the walk starts at {@code
 * co_firstlineno} and the first run is above it.
 *
 * <p>That the runs are the same for both interpreters is not a free pass: this runtime compiles with debug
 * ranges and the reference was asked without them, so the two {@code co_linetable}s disagree in bytes and in
 * length. The legacy table cannot see that, because it is cut from line runs, and a run of one line is one
 * run whichever columns it carries.
 */
class LegacyLineTableTest {

    @TempDir
    Path work;

    /** The pairs the reference wrote for each of the six code objects the fixture compiles to. */
    private static final Map<String, String> CAPTURED = captured();

    private static Map<String, String> captured() {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("<module>", "00ff0201040506040605067f0012067f0012");
        pairs.put("simple", "0201");
        pairs.put("wide", "0201ff00ff00ff002701");
        pairs.put("farjump", "0201047f000e");
        pairs.put("back", "0201" + "1e01" + "0401".repeat(139) + "0a8000f4047f000e");
        // The class body is the one code object named for the class it builds rather than for itself.
        pairs.put("K", "1401");
        pairs.put("K.m", "0201");
        return pairs;
    }

    /** Every code object the fixture compiles to, by the qualname the reference gives it. */
    private Map<String, PyCode> shapes() throws IOException {
        Path script =
                Jy3Process.write(work, "lnotab_shapes.py", Jy3Process.resource("m3/lnotab_shapes.py"));
        PyCode module = Cpython314Compiler.compileFile(script).code();
        Map<String, PyCode> found = new LinkedHashMap<>();
        collect(module, found);
        return found;
    }

    private static void collect(PyCode code, Map<String, PyCode> into) {
        into.put(code.qualName(), code);
        for (PyObject item : code.constants().elements()) {
            if (item instanceof PyCode nested) {
                collect(nested, into);
            }
        }
    }

    /** {@code co.co_lnotab} as the hex the reference was captured in. */
    private static String pairs(PyCode code) {
        PyBytes table = (PyBytes) Slots.getAttr((PyObject) code, "co_lnotab");
        return HexFormat.of().formatHex(table.getValue());
    }

    @Test
    void theSixShapesWriteThePairsTheReferenceWrote() throws IOException {
        Map<String, PyCode> shapes = shapes();

        assertThat(shapes.keySet()).containsExactlyInAnyOrderElementsOf(CAPTURED.keySet());
        for (Map.Entry<String, String> shape : CAPTURED.entrySet()) {
            assertThat(pairs(shapes.get(shape.getKey())))
                    .as(shape.getKey())
                    .isEqualTo(shape.getValue());
        }
    }

    /**
     * A pair is written by the run that reaches a new line and by no other: the first run of a function sits
     * on the line {@code co_firstlineno} already names, so nothing in its table mentions the line it opens on,
     * and a run with no line under it is passed over with the address it brought.
     */
    @Test
    void onlyARunThatReachesANewLineWritesAPair() throws IOException {
        Map<String, PyCode> shapes = shapes();
        PyCode simple = shapes.get("simple");

        assertThat(pairs(simple)).isEqualTo("0201");
        // Two runs and one pair: the run under the line the function opens on writes nothing, and the
        // address the skipped run brought rides on the pair that names the line the return reaches.
        assertThat(simple.lineRanges().size()).isEqualTo(2);
        assertThat(simple.firstLine()).isEqualTo(6);
        assertThat(pairs(shapes.get("K.m"))).isEqualTo("0201");
        assertThat(shapes.get("K.m").firstLine()).isEqualTo(306);
    }

    /**
     * The address gap is told in bytes, not in code units: {@code co_lines()} already answers byte offsets,
     * which is why a jump of two units writes {@code 02} and not {@code 01} beside the line it reaches.
     */
    @Test
    void theAddressHalfOfAPairIsAlreadyAByteCount() throws IOException {
        PyCode wide = shapes().get("wide");
        String table = pairs(wide);

        assertThat(table).isEqualTo("0201ff00ff00ff002701");
        // Three (255, 0) runs, then the gap that is left riding on the pair that names the line.
        assertThat(table).contains("ff00ff00ff00");
        assertThat(wide.firstLine()).isEqualTo(10);
    }
}
