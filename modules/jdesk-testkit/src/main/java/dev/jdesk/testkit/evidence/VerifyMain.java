package dev.jdesk.testkit.evidence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI entry for evidence verification: {@code VerifyMain <evidence-base-dir>
 * [expected-category]}. Verifies every run directory found; exits non-zero on any
 * problem, on zero runs, or when no run has status PASSED. Backs CI's evidence gate
 * until the Gradle {@code jdeskVerifyEvidence} task lands (Phase 3).
 */
public final class VerifyMain {
    private VerifyMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: VerifyMain <evidence-base-dir>");
            System.exit(64);
        }
        Path base = Path.of(args[0]);
        if (!Files.isDirectory(base)) {
            System.err.println("VERIFY FAIL: no evidence directory at " + base.toAbsolutePath());
            System.exit(1);
        }
        EvidenceVerifier verifier = new EvidenceVerifier();
        int runs = 0;
        int passed = 0;
        boolean anyProblem = false;
        try (var stream = Files.list(base)) {
            for (Path runDir : stream.sorted().toList()) {
                if (!Files.isDirectory(runDir)) {
                    continue;
                }
                runs++;
                List<String> problems = verifier.verify(runDir);
                String status = "unknown";
                try {
                    String manifest = Files.readString(runDir.resolve("manifest.json"));
                    if (manifest.contains("\"status\" : \"PASSED\"")
                            || manifest.contains("\"status\":\"PASSED\"")) {
                        status = "PASSED";
                        passed++;
                    } else if (manifest.contains("INCOMPLETE")) {
                        status = "INCOMPLETE";
                    } else {
                        status = "FAILED";
                    }
                } catch (Exception e) {
                    status = "unreadable";
                }
                System.out.println("run " + runDir.getFileName() + ": status=" + status
                        + " problems=" + problems.size());
                for (String problem : problems) {
                    anyProblem = true;
                    System.out.println("  PROBLEM: " + problem);
                }
            }
        }
        if (runs == 0) {
            System.err.println("VERIFY FAIL: no evidence runs found");
            System.exit(1);
        }
        if (passed == 0) {
            System.err.println("VERIFY FAIL: no PASSED evidence run");
            System.exit(1);
        }
        if (anyProblem) {
            System.err.println("VERIFY FAIL: evidence problems found");
            System.exit(1);
        }
        System.out.println("VERIFY OK: " + passed + "/" + runs + " runs passed");
    }
}
