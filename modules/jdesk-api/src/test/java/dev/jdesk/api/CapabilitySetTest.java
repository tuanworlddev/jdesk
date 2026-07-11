package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Deny-by-default capability evaluation (spec section 12.1). */
class CapabilitySetTest {

    private static final WindowId MAIN = new WindowId("main");
    private static final WindowId SETTINGS = new WindowId("settings");

    @Test
    void emptySetDeniesEverything() {
        CapabilitySet set = CapabilitySet.empty();
        assertThat(set.grants()).isEmpty();
        assertThat(set.isGranted("fs.read", MAIN)).isFalse();
        assertThat(set.isGranted("", MAIN)).isFalse();
    }

    @Test
    void allWindowsGrantAppliesToEveryWindow() {
        CapabilitySet set = CapabilitySet.of(Set.of(CapabilityGrant.forAllWindows("fs.read")));

        assertThat(set.isGranted("fs.read", MAIN)).isTrue();
        assertThat(set.isGranted("fs.read", SETTINGS)).isTrue();
        assertThat(set.isGranted("fs.read", new WindowId("any-window-at-all"))).isTrue();
        // other capabilities stay denied
        assertThat(set.isGranted("fs.write", MAIN)).isFalse();
    }

    @Test
    void windowRestrictedGrantMatchesOnlyListedWindows() {
        CapabilitySet set = CapabilitySet.of(
                Set.of(new CapabilityGrant("clipboard.write", Set.of("main", "editor"))));

        assertThat(set.isGranted("clipboard.write", MAIN)).isTrue();
        assertThat(set.isGranted("clipboard.write", new WindowId("editor"))).isTrue();
        assertThat(set.isGranted("clipboard.write", SETTINGS)).isFalse();
        assertThat(set.isGranted("clipboard.read", MAIN)).isFalse();
    }

    @Test
    void multipleGrantsForSameCapabilityAreUnioned() {
        CapabilitySet set = CapabilitySet.of(List.of(
                new CapabilityGrant("net.fetch", Set.of("main")),
                new CapabilityGrant("net.fetch", Set.of("settings"))));

        assertThat(set.isGranted("net.fetch", MAIN)).isTrue();
        assertThat(set.isGranted("net.fetch", SETTINGS)).isTrue();
        assertThat(set.isGranted("net.fetch", new WindowId("other"))).isFalse();
    }

    @Test
    void grantsReturnsImmutableSet() {
        CapabilitySet set = CapabilitySet.of(Set.of(CapabilityGrant.forAllWindows("fs.read")));

        assertThatThrownBy(() -> set.grants().add(CapabilityGrant.forAllWindows("fs.write")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> set.grants().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ofCopiesTheInputCollection() {
        List<CapabilityGrant> input = new ArrayList<>();
        input.add(CapabilityGrant.forAllWindows("fs.read"));
        CapabilitySet set = CapabilitySet.of(input);

        input.add(CapabilityGrant.forAllWindows("fs.write"));
        input.clear();

        assertThat(set.grants()).containsExactly(CapabilityGrant.forAllWindows("fs.read"));
        assertThat(set.isGranted("fs.read", MAIN)).isTrue();
        assertThat(set.isGranted("fs.write", MAIN)).isFalse();
    }

    @Test
    void grantWindowsSetIsDefensivelyCopied() {
        java.util.Set<String> windows = new java.util.HashSet<>(Set.of("main"));
        CapabilityGrant grant = new CapabilityGrant("fs.read", windows);
        windows.add("settings");

        assertThat(grant.windows()).containsExactly("main");
        assertThatThrownBy(() -> grant.windows().add("settings"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
