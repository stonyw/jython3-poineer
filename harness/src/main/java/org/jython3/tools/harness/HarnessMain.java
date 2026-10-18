package org.jython3.tools.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Golden test harness entry point.
 *
 * <pre>
 * gradle :harness:run --args="--backend=cpython-ref --suite=smoke"
 * </pre>
 *
 * Exit status is the number of surprises (capped at 100), so CI fails exactly when reality diverged
 * from the recorded expectations in either direction.
 */
public final class HarnessMain {

    private static final String USAGE =
            """
            usage: harness [options]
              --backend=<cpython-ref|jy3>   which runtime measures the suite (required)
              --suite=<name>                harness/suites/<name>.txt (default: smoke)
              --python=<bin>                reference interpreter (default: $JY3_REF_PYTHON or python3.14)
              --lib-python=<dir>            vendored CPython test tree (default: lib-python/3.14)
              --jy3=<path>                  jy3 launcher for the jy3 backend
              --expected=<file>             expected list (default: harness/expected/<backend>.txt)
              --report-dir=<dir>            output dir (default: build/reports/harness)
              --timeout=<seconds>           per-test wall clock limit (default: 120)
              --update-expected             rewrite the expected list from this run
              --list                        resolve configuration and exit without running
            """;

    public static void main(String[] args) throws IOException {
        Map<String, String> opt = parse(args);
        if (opt.containsKey("help")) {
            System.out.print(USAGE);
            return;
        }
        String backendId = require(opt, "backend");
        String suiteName = opt.getOrDefault("suite", "smoke");
        Duration timeout = Duration.ofSeconds(Long.parseLong(opt.getOrDefault("timeout", "120")));
        Path root = Paths.get(System.getProperty("user.dir"));
        Path libPython = root.resolve(opt.getOrDefault("lib-python", "lib-python/3.14"));
        Path suiteFile = root.resolve("harness/suites/" + suiteName + ".txt");
        String platform = ExpectedList.currentPlatform();

        Suite suite = Suite.read(suiteFile);
        String python =
                opt.getOrDefault(
                        "python", System.getenv().getOrDefault("JY3_REF_PYTHON", "python3.14"));

        Backend backend =
                switch (backendId) {
                    case "cpython-ref" -> new CpythonBackend(python, libPython, timeout);
                    case "jy3" ->
                            new Jython3Backend(
                                    root.resolve(
                                            opt.getOrDefault(
                                                    "jy3", "embedding/build/install/jy3/bin/jy3")),
                                    libPython,
                                    timeout);
                    default -> throw new IllegalArgumentException("--backend must be cpython-ref or jy3");
                };

        Path expectedFile = root.resolve("harness/expected/" + backendId + ".txt");
        if (opt.containsKey("list")) {
            System.out.printf(
                    "backend=%s platform=%s suite=%s tests=%d expected=%s%s libPython=%s%n",
                    backendId,
                    platform,
                    suiteName,
                    suite.tests().size(),
                    expectedFile,
                    Files.exists(expectedFile) ? "" : " (absent: nothing expected)",
                    libPython);
            return;
        }

        ExpectedList expected = ExpectedList.read(expectedFile, platform);
        List<Grading.Graded> graded = new ArrayList<>();
        for (String test : suite.tests()) {
            Grading.Result r = backend.run(test);
            Grading.Graded g = Grading.grade(r, expected.expectationFor(test));
            graded.add(g);
            System.out.printf(
                    "%-8s %-22s %-17s %5dms%n", g.verdict(), test, r.status(), r.millis());
        }

        HarnessReport report = new HarnessReport(backendId, suiteName, graded);
        System.out.println();
        System.out.print(report.text());
        report.write(root.resolve(opt.getOrDefault("report-dir", "build/reports/harness")));

        if (opt.containsKey("update-expected")) {
            List<Grading.Graded> withExpectations =
                    graded.stream()
                            .map(
                                    g ->
                                            new Grading.Graded(
                                                    g.result(),
                                                    expectationFrom(g.result()),
                                                    g.verdict()))
                            .toList();
            Files.writeString(expectedFile, ExpectedList.render(withExpectations));
            System.out.println("rewrote " + expectedFile);
        }

        int surprises = report.surprises();
        if (surprises > 0) {
            System.out.println("FAIL: " + surprises + " surprise(s) vs recorded expectations");
        }
        System.exit(Math.min(surprises, 100));
    }

    private static Grading.Expectation expectationFrom(Grading.Result r) {
        return switch (r.status()) {
            case FAIL, CRASH, TIMEOUT, NOT_IMPLEMENTED -> Grading.Expectation.FAIL;
            case SKIP -> Grading.Expectation.SKIP;
            case PASS -> Grading.Expectation.NONE;
        };
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String a : args) {
            if (a.equals("--help") || a.equals("-h")) {
                m.put("help", "");
            } else if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                if (eq < 0) {
                    m.put(a.substring(2), "");
                } else {
                    m.put(a.substring(2, eq), a.substring(eq + 1));
                }
            } else {
                throw new IllegalArgumentException("unrecognised argument: " + a + "\n" + USAGE);
            }
        }
        return m;
    }

    private static String require(Map<String, String> opt, String key) {
        String v = opt.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("--" + key + " is required\n" + USAGE);
        }
        return v;
    }

    private HarnessMain() {}
}
