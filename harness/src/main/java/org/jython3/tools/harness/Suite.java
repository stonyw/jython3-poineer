package org.jython3.tools.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The tests to measure: one CPython regrtest target per line in {@code harness/suites/<name>.txt}. */
public record Suite(String name, List<String> tests) {

    public static Suite read(Path file) throws IOException {
        List<String> tests = new ArrayList<>();
        int lineNo = 0;
        for (String raw : Files.readAllLines(file)) {
            lineNo++;
            String line = raw.indexOf('#') < 0 ? raw : raw.substring(0, raw.indexOf('#'));
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.split("\\s+").length != 1) {
                throw new IOException(file + ":" + lineNo + ": expected a single test name, got: " + line);
            }
            if (!tests.contains(line)) {
                tests.add(line);
            }
        }
        if (tests.isEmpty()) {
            throw new IOException(file + ": suite is empty");
        }
        return new Suite(file.getFileName().toString().replace(".txt", ""), List.copyOf(tests));
    }
}
