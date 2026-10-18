package org.jython3.tools.harness;

import java.util.Locale;

/**
 * The grading domain: what a backend observed, what we expected, and whether the gap is a surprise.
 *
 * <p>Surprise detection is bidirectional on purpose (the Jython 2.7 regrtest lesson): an
 * unexpectedly passing test is as harmful as a failing one, because it silently launders a stale
 * skip list into a green build.
 */
public final class Grading {

    public enum Status {
        PASS,
        FAIL,
        SKIP,
        CRASH,
        TIMEOUT,
        NOT_IMPLEMENTED
    }

    public enum Expectation {
        NONE,
        FAIL,
        SKIP;

        public static Expectation parse(String token) {
            return switch (token.toLowerCase(Locale.ROOT)) {
                case "fail" -> FAIL;
                case "skip" -> SKIP;
                case "", "none" -> NONE;
                default -> throw new IllegalArgumentException("unknown expectation: " + token);
            };
        }
    }

    public enum Verdict {
        /** Ran, passed, nothing was expected of it. */
        PASS(false),
        /** Ran, failed, and we had it on the known-bad list. */
        EXPECTED_FAIL(false),
        /** Skipped, and we knew it would be. */
        EXPECTED_SKIP(false),
        /** Ran, failed, and it was not supposed to. */
        FAILURE(true),
        /** Ran, passed, but it was listed as known-bad: the list is stale. */
        UNEXPECTED_PASS(true),
        /** Skipped when it was expected to at least run and fail. */
        UNEXPECTED_SKIP(true),
        /** Interpreter-level breakage (core dump, aborted, unusable exit). */
        ERROR(true),
        /** Nothing after a hang. */
        TIMEOUT(true),
        /** The backend refused to run the test at all. */
        NOT_IMPLEMENTED(true);

        private final boolean surprise;

        Verdict(boolean surprise) {
            this.surprise = surprise;
        }

        public boolean isSurprise() {
            return surprise;
        }
    }

    public record Result(String test, Status status, int exitCode, long millis, String detail) {}

    public record Graded(Result result, Expectation expectation, Verdict verdict) {
        public boolean isSurprise() {
            return verdict.isSurprise();
        }
    }

    private Grading() {}

    public static Graded grade(Result r, Expectation expected) {
        Verdict v =
                switch (expected) {
                    case NONE ->
                            switch (r.status()) {
                                case PASS -> Verdict.PASS;
                                case SKIP -> Verdict.UNEXPECTED_SKIP;
                                case FAIL -> Verdict.FAILURE;
                                case CRASH, TIMEOUT -> Verdict.ERROR;
                                case NOT_IMPLEMENTED -> Verdict.NOT_IMPLEMENTED;
                            };
                    case FAIL ->
                            switch (r.status()) {
                                case FAIL -> Verdict.EXPECTED_FAIL;
                                case PASS -> Verdict.UNEXPECTED_PASS;
                                case SKIP -> Verdict.UNEXPECTED_SKIP;
                                case CRASH, TIMEOUT -> Verdict.ERROR;
                                case NOT_IMPLEMENTED -> Verdict.NOT_IMPLEMENTED;
                            };
                    case SKIP ->
                            switch (r.status()) {
                                case SKIP -> Verdict.EXPECTED_SKIP;
                                case PASS -> Verdict.UNEXPECTED_PASS;
                                case FAIL -> Verdict.FAILURE;
                                case CRASH, TIMEOUT -> Verdict.ERROR;
                                case NOT_IMPLEMENTED -> Verdict.NOT_IMPLEMENTED;
                            };
                };
        return new Graded(r, expected, v);
    }
}
