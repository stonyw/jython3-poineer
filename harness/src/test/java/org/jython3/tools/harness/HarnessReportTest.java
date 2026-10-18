package org.jython3.tools.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessReportTest {

    private static Grading.Graded g(String test, Grading.Status s, Grading.Expectation e) {
        Grading.Result r = new Grading.Result(test, s, 0, 3L, "boom <&\"x\"/> \nsecond line");
        return new Grading.Graded(r, e, Grading.grade(r, e).verdict());
    }

    private static HarnessReport report() {
        return new HarnessReport(
                "cpython-ref",
                "smoke",
                List.of(
                        g("test_a", Grading.Status.PASS, Grading.Expectation.NONE),
                        g("test_b", Grading.Status.FAIL, Grading.Expectation.FAIL),
                        g("test_c", Grading.Status.PASS, Grading.Expectation.FAIL),
                        g("test_d", Grading.Status.SKIP, Grading.Expectation.NONE)));
    }

    @Test
    void countsSurprisesInBothDirections() {
        HarnessReport r = report();
        // test_c passed against a known-bad entry, test_d skipped unannounced: two surprises.
        assertThat(r.surprises()).isEqualTo(2);
        assertThat(r.counts())
                .containsEntry(Grading.Verdict.PASS, 1)
                .containsEntry(Grading.Verdict.EXPECTED_FAIL, 1)
                .containsEntry(Grading.Verdict.UNEXPECTED_PASS, 1)
                .containsEntry(Grading.Verdict.UNEXPECTED_SKIP, 1);
        assertThat(r.text()).contains("surprises: 2").contains("test_c");
    }

    @Test
    void jsonStaysParsableWithQuotedDetail() {
        String json = report().json();
        assertThat(json).contains("\"surprises\": 2").doesNotContain("</");
        // Every detail string is flattened, so a newline can never break the record structure.
        assertThat(json.lines().filter(l -> l.contains("\"detail\"")).count()).isEqualTo(4);
    }

    @Test
    void junitXmlEscapesMarkup() {
        String xml = report().junitXml();
        assertThat(xml)
                .startsWith("<?xml")
                .contains("failures=\"2\"")
                .contains("skipped=\"1\"")
                .doesNotContain("<&\"x\"/>");
        assertThat(xml).contains("&lt;&amp;&quot;x&quot;/&gt;");
    }
}
