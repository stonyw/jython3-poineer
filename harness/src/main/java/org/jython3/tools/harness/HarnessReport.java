package org.jython3.tools.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns graded results into the console table, a JSON record and JUnit XML. */
public final class HarnessReport {

    private final String backend;
    private final String suite;
    private final List<Grading.Graded> graded;

    public HarnessReport(String backend, String suite, List<Grading.Graded> graded) {
        this.backend = backend;
        this.suite = suite;
        this.graded = List.copyOf(graded);
    }

    public Map<Grading.Verdict, Integer> counts() {
        Map<Grading.Verdict, Integer> c = new LinkedHashMap<>();
        for (Grading.Verdict v : Grading.Verdict.values()) {
            c.put(v, 0);
        }
        for (Grading.Graded g : graded) {
            c.merge(g.verdict(), 1, Integer::sum);
        }
        c.entrySet().removeIf(e -> e.getValue() == 0);
        return c;
    }

    public int surprises() {
        return (int) graded.stream().filter(Grading.Graded::isSurprise).count();
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("jy3 harness  backend=").append(backend).append("  suite=").append(suite).append('\n');
        for (Grading.Graded g : graded) {
            sb.append(String.format(
                    "  %-22s %-15s %-17s %-7s %5dms%n",
                    g.verdict(),
                    g.result().test(),
                    g.result().status(),
                    g.expectation(),
                    g.result().millis()));
        }
        sb.append("\n  ");
        counts().forEach((v, n) -> sb.append(v).append('=').append(n).append(' '));
        sb.append('\n');
        sb.append("  surprises: ").append(surprises()).append('\n');
        if (surprises() > 0) {
            graded.stream()
                    .filter(Grading.Graded::isSurprise)
                    .forEach(
                            g ->
                                    sb.append("  !! ")
                                            .append(g.result().test())
                                            .append(" -> ")
                                            .append(g.result().status())
                                            .append(" (expected ")
                                            .append(g.expectation())
                                            .append(") :: ")
                                            .append(firstLine(g.result().detail()))
                                            .append('\n'));
        }
        return sb.toString();
    }

    public void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(backend + ".txt"), text());
        Files.writeString(dir.resolve(backend + ".json"), json());
        Files.writeString(dir.resolve(backend + "-junit.xml"), junitXml());
    }

    public String json() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"backend\": ").append(str(backend)).append(",\n  \"suite\": ").append(str(suite))
                .append(",\n  \"surprises\": ").append(surprises())
                .append(",\n  \"counts\": {");
        boolean first = true;
        for (Map.Entry<Grading.Verdict, Integer> e : counts().entrySet()) {
            sb.append(first ? "\n" : ",\n").append("    ").append(str(e.getKey().name())).append(": ")
                    .append(e.getValue());
            first = false;
        }
        sb.append(first ? "},\n" : "\n  },\n").append("  \"tests\": [\n");
        for (int i = 0; i < graded.size(); i++) {
            Grading.Graded g = graded.get(i);
            sb.append("    {\"test\": ").append(str(g.result().test()))
                    .append(", \"verdict\": ").append(str(g.verdict().name()))
                    .append(", \"status\": ").append(str(g.result().status().name()))
                    .append(", \"expectation\": ").append(str(g.expectation().name()))
                    .append(", \"exitCode\": ").append(g.result().exitCode())
                    .append(", \"millis\": ").append(g.result().millis())
                    .append(", \"detail\": ").append(str(oneLine(g.result().detail())))
                    .append("}").append(i + 1 < graded.size() ? "," : "").append('\n');
        }
        return sb.append("  ]\n}\n").toString();
    }

    public String junitXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<testsuite name=\"jy3-harness.")
                .append(esc(backend))
                .append(".")
                .append(esc(suite))
                .append("\" tests=\"")
                .append(graded.size())
                .append("\" failures=\"")
                .append(graded.stream().filter(g -> g.verdict().isSurprise()).count())
                .append("\" skipped=\"")
                .append(graded.stream().filter(g -> g.result().status() == Grading.Status.SKIP).count())
                .append("\">\n");
        for (Grading.Graded g : graded) {
            sb.append("  <testcase classname=\"").append(esc(backend)).append(".")
                    .append(esc(suite))
                    .append("\" name=\"").append(esc(g.result().test())).append("\" time=\"")
                    .append(g.result().millis() / 1000.0)
                    .append("\"");
            if (g.verdict().isSurprise()) {
                sb.append(">\n    <failure type=\"")
                        .append(esc(g.verdict().name()))
                        .append("\" message=\"")
                        .append(esc(g.result().status() + " while " + g.expectation()))
                        .append("\n")
                        .append(esc(g.result().detail()))
                        .append("\n    </failure>\n  </testcase>\n");
            } else if (g.result().status() == Grading.Status.SKIP) {
                sb.append(">\n    <skipped/>\n  </testcase>\n");
            } else {
                sb.append("/>\n");
            }
        }
        return sb.append("</testsuite>\n").toString();
    }

    private static String str(String s) {
        return "\"" + esc(s) + "\"";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("&quot;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                default -> {
                    if (c < 0x20) {
                        out.append(' ');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\r", "").replace("\n", " | ");
    }

    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
