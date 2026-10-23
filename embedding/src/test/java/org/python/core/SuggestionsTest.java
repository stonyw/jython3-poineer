package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * The names a suggestion is chosen among, and the arithmetic it is chosen with.
 *
 * <p>{@link org.python.util.Jy3LauncherTest} compares whole reports for the shapes a person reaches by
 * mistyping; what is taken apart here is the chooser underneath them, because the lists that decide it
 * cannot be typed. A class that writes its own {@code __dir__} is how a test hands {@code _suggestions}
 * a list of its own — the candidate set CPython is asked to work over is then exactly the one written,
 * sorted, and with the underscored names the wrong name did not ask for removed.
 *
 * <p>Only the complaint line is asserted. The frames above it are the same for every shape here and
 * {@link TracebackReportTest} reads them; what is pinned is the sentence the chooser hangs on the end of
 * the text, and the arithmetic that decides whether it says anything at all.
 *
 * <p>Every literal is what CPython 3.14.8 prints for the same script, read off the reference asked for
 * its anchor-free report.
 */
class SuggestionsTest {

    @TempDir
    Path work;

    /** {@code substitution_cost}: one letter in the other case costs less than a letter moved. */
    @Test
    void aCaseFlipIsCheaperThanAMove() throws Exception {
        assertThat(complaint("['print']", "Print"))
                .isEqualTo("AttributeError: 'Chosen' object has no attribute 'Print'. Did you mean: 'print'?\n");
        assertThat(complaint("['prinT']", "print"))
                .isEqualTo("AttributeError: 'Chosen' object has no attribute 'print'. Did you mean: 'prinT'?\n");
    }

    /**
     * The budget a candidate is measured against is a third of the two names together, and a candidate is
     * refused at anything over it: {@code 'ab'} and {@code 'ba'} differ by two moves, and the four that
     * costs is more than the two the formula allows.
     */
    @Test
    void twoMovesAreNotATransposition() throws Exception {
        assertThat(complaint("['ab']", "ba"))
                .isEqualTo("AttributeError: 'Chosen' object has no attribute 'ba'\n");
    }

    /**
     * First past the post keeps a tie, because the chooser only takes a candidate that <em>beats</em> the
     * distance it already has — and the list arrives sorted, so the winner is the name that comes first in
     * the order {@code dir()} answers in.
     */
    @Test
    void theFirstCandidatePastThePostKeepsATie() throws Exception {
        assertThat(complaint("['ax', 'ay']", "ab"))
                .isEqualTo("AttributeError: 'Chosen' object has no attribute 'ab'. Did you mean: 'ax'?\n");
        assertThat(complaint("['a1', 'a2', 'ax']", "ab"))
                .isEqualTo("AttributeError: 'Chosen' object has no attribute 'ab'. Did you mean: 'a1'?\n");
    }

    /** A later candidate that is nearer still takes the place of the first one. */
    @Test
    void aNearerCandidateBeatsTheOneBeforeIt() throws Exception {
        // Four case flips cost what one letter's deletion costs twice, and 'PRINt' is offered first.
        assertThat(complaint("['PRINt', 'prine']", "print"))
                .isEqualTo(
                        "AttributeError: 'Chosen' object has no attribute 'print'. Did you mean: 'prine'?\n");
    }

    /** A candidate equal to the name that was missed is skipped rather than handed back. */
    @Test
    void aCandidateEqualToTheNameIsNotSuggested() throws Exception {
        assertThat(complaint("['valu', 'value']", "valu"))
                .isEqualTo(
                        "AttributeError: 'Chosen' object has no attribute 'valu'. Did you mean: 'value'?\n");
    }

    /**
     * The length that refuses is judged on what is left after the two names' shared affixes are cut off,
     * and it is counted in bytes rather than in characters.
     */
    @Test
    void aSharedAffixIsCutBeforeTheLengthIsJudged() throws Exception {
        String fortyTwo = "a".repeat(41);
        assertThat(complaint("['" + fortyTwo + "x']", fortyTwo + "y"))
                .isEqualTo(
                        "AttributeError: 'Chosen' object has no attribute '"
                                + fortyTwo
                                + "y'. Did you mean: '"
                                + fortyTwo
                                + "x'?\n");

        // Two names of fifty-one bytes each keep only one common byte, so forty is what is left to judge
        // and the distance is never worked out at all.
        String umlauts = "ö".repeat(25);
        String other = "ä".repeat(25);
        assertThat(complaint("['" + other + "x']", umlauts + "y"))
                .isEqualTo("AttributeError: 'Chosen' object has no attribute '" + umlauts + "y'\n");
    }

    /**
     * {@code MAX_CANDIDATE_ITEMS}: a list this long is refused before it is read, at the count and not
     * above it.
     */
    @Test
    void sevenHundredAndFiftyNamesAreRefused() throws Exception {
        assertThat(complaint("['counter'] + ['pad%03d' % i for i in range(748)]", "countr"))
                .isEqualTo(
                        "AttributeError: 'Chosen' object has no attribute 'countr'. Did you mean: 'counter'?\n");
        assertThat(complaint("['counter'] + ['pad%03d' % i for i in range(749)]", "countr"))
                .isEqualTo(
                        "AttributeError: 'Chosen' object has no attribute 'countr'\n");
    }

    // --- running -------------------------------------------------------------------------

    /**
     * The complaint line of a read that missed {@code read} on a value whose {@code dir()} answers with
     * {@code names}.
     */
    private String complaint(String names, String read) throws IOException {
        Cpython314Compiler.Compiled script =
                script(
                        "chosen.py",
                        "class Chosen:\n"
                                + "    def __dir__(self):\n"
                                + "        return "
                                + names
                                + "\n"
                                + "\n"
                                + "thing = Chosen()\n"
                                + "thing."
                                + read
                                + "\n");
        PyBaseException error =
                uncaught(script.code(), PyRuntime.mainModule(), script.fileName());
        String report = TracebackReport.of(error);
        return report.substring(report.lastIndexOf('\n', report.length() - 2) + 1);
    }

    /** A program written where the report can read the line each frame quotes. */
    private Cpython314Compiler.Compiled script(String name, String program) throws IOException {
        return Cpython314Compiler.compileFile(Files.writeString(work.resolve(name), program));
    }

    /** The exception a module's top level could not catch. */
    private static PyBaseException uncaught(PyCode code, PyModule where, String fileName) {
        try {
            PyRuntime.exec(code, where, fileName);
        } catch (PyRaise raised) {
            return raised.value();
        }
        throw new AssertionError("expected an escaped exception from " + code.fileName());
    }
}
