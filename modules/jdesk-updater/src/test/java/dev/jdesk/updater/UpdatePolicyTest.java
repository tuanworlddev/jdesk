package dev.jdesk.updater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UpdatePolicyTest {
    private static final String PREFIX = "jdesk.update.";

    @AfterEach void clearProperties() {
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith(PREFIX))
                .toList().forEach(System::clearProperty);
    }

    @Test void readsValidManagedProperties() {
        System.setProperty("jdesk.update.enabled", "false");
        System.setProperty("jdesk.update.channel", "internal");
        System.setProperty("jdesk.update.maxPackageBytes", "2048");

        UpdatePolicy policy = UpdatePolicy.systemProperties();

        assertThat(policy.enabled()).isFalse();
        assertThat(policy.channel()).isEqualTo(UpdateChannel.INTERNAL);
        assertThat(policy.maxPackageBytes()).isEqualTo(2048);
    }

    @Test void rejectsInvalidBooleanAndNumberInsteadOfWeakeningPolicy() {
        System.setProperty("jdesk.update.allowDowngrade", "yes");
        assertThatThrownBy(UpdatePolicy::systemProperties)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be true or false");

        System.setProperty("jdesk.update.allowDowngrade", "false");
        System.setProperty("jdesk.update.maxPackageBytes", "unbounded");
        assertThatThrownBy(UpdatePolicy::systemProperties)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an integer");
    }
}
