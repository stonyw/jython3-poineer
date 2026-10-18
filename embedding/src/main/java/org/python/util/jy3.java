package org.python.util;

/**
 * Jython3 launcher.
 *
 * <p>M0 status: argument surface and exit protocol only. The runtime arrives in M1 (hello world) and
 * M2 (interop); until then every code path reports 3 = not implemented, which the harness records as
 * {@code NOT_IMPLEMENTED} rather than pretending to pass.
 *
 * <p>Harness protocol, shared with {@code org.jython3.tools.harness.Jython3Backend}:
 *
 * <pre>
 *   0 passed   1 failed   2 skipped   3 not implemented   other crash
 * </pre>
 */
public final class jy3 {

    /** Reserved by the golden test harness: run one regrtest target and report by exit code. */
    private static final String HARNESS_FLAG = "--harness";

    public static void main(String[] args) {
        String test = harnessTarget(args);
        System.err.println(
                "jy3 0.1.0-M0: the Jython3 runtime is not implemented yet."
                        + (test == null ? "" : " Harness target: " + test));
        System.err.println("See PLAN.md milestones M1 (hello world) and M2 (Java interop).");
        System.exit(3);
    }

    static String harnessTarget(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (HARNESS_FLAG.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private jy3() {}
}
