package dev.jdesk.platform.macos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit tests for application-name derivation; no AppKit classes are loaded. */
class MacApplicationNameTest {

    private static final String PROP = "jdesk.applicationName";

    @AfterEach
    void clearOverride() {
        System.clearProperty(PROP);
    }

    @Test
    void derivesCapitalizedNameFromApplicationIdWhenNoOverride() {
        System.clearProperty(PROP);
        assertThat(MacApplicationName.displayName("dev.example.dragon7")).isEqualTo("Dragon7");
    }

    @Test
    void overridePropertyWinsVerbatimIncludingSpaces() {
        System.setProperty(PROP, "Dragon 7");
        assertThat(MacApplicationName.displayName("dev.example.dragon7")).isEqualTo("Dragon 7");
    }

    @Test
    void blankOverrideFallsBackToDerivedName() {
        System.setProperty(PROP, "   ");
        assertThat(MacApplicationName.displayName("dev.example.dragon7")).isEqualTo("Dragon7");
    }

    @Test
    void returnsNullWhenNoUsableSegmentAndNoOverride() {
        System.clearProperty(PROP);
        assertThat(MacApplicationName.displayName("dev.example.")).isNull();
    }
}
