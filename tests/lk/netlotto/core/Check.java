package lk.netlotto.core;

/** Minimal assertion helper for the test programs: prints PASS/FAIL and exits non-zero on failure. */
public final class Check {

    private static int passed;
    private static int failed;

    private Check() {
    }

    public static void that(boolean condition, String description) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + description);
        } else {
            failed++;
            System.out.println("  FAIL  " + description);
        }
    }

    public static void equal(Object expected, Object actual, String description) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        that(same, description + (same ? "" : "  (expected " + expected + ", got " + actual + ")"));
    }

    public static void throwsIllegal(Runnable r, String description) {
        try {
            r.run();
            that(false, description);
        } catch (IllegalArgumentException e) {
            that(true, description);
        }
    }

    /** Prints the totals and exits with status 1 if anything failed. */
    public static void done() {
        System.out.println("  " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
