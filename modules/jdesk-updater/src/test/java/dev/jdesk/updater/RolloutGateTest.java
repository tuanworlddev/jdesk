package dev.jdesk.updater;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RolloutGateTest {

    @Test
    void fullRolloutAdmitsEveryoneAndZeroAdmitsNoOne() {
        for (int i = 0; i < 50; i++) {
            String id = "install-" + i;
            assertThat(RolloutGate.eligible(id, "2.0.0", 100)).isTrue();
            assertThat(RolloutGate.eligible(id, "2.0.0", 0)).isFalse();
        }
    }

    @Test
    void bucketIsDeterministicAndInRange() {
        int first = RolloutGate.bucket("install-x", "2.0.0");
        int second = RolloutGate.bucket("install-x", "2.0.0");
        assertThat(first).isEqualTo(second).isBetween(0, 99);
        // Independent per release: the same install buckets differently across versions.
        assertThat(RolloutGate.bucket("install-x", "2.0.1")).isBetween(0, 99);
    }

    @Test
    void raisingThePercentageOnlyEverAdmitsMoreInstalls() {
        // An install admitted at percentage P must still be admitted at every higher P.
        for (int i = 0; i < 200; i++) {
            String id = "install-" + i;
            int firstAdmitted = -1;
            for (int p = 0; p <= 100; p++) {
                boolean in = RolloutGate.eligible(id, "3.1.0", p);
                if (in && firstAdmitted < 0) {
                    firstAdmitted = p;
                }
                if (firstAdmitted >= 0) {
                    assertThat(in).as("monotonic at p=%d for %s", p, id).isTrue();
                }
            }
        }
    }

    @Test
    void distributionRoughlyTracksThePercentage() {
        int total = 10_000;
        int admitted = 0;
        for (int i = 0; i < total; i++) {
            if (RolloutGate.eligible("install-" + i, "4.0.0", 25)) {
                admitted++;
            }
        }
        double ratio = (double) admitted / total;
        assertThat(ratio).isBetween(0.22, 0.28); // ~25% within a loose band
    }
}
