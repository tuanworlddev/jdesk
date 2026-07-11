package dev.jdesk.gradle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvRedactorTest {

    @Test
    void redactsSecretLookingVariablesCaseInsensitively() {
        String rendered = EnvRedactor.redact(Map.of(
                "PATH", "/usr/bin",
                "NPM_TOKEN", "hunter2",
                "ApiKey", "hunter2",
                "DB_PASSWORD", "hunter2",
                "client_secret", "hunter2",
                "LANG", "en_US.UTF-8"));

        assertThat(rendered)
                .contains("PATH=/usr/bin")
                .contains("LANG=en_US.UTF-8")
                .contains("NPM_TOKEN=[redacted]")
                .contains("ApiKey=[redacted]")
                .contains("DB_PASSWORD=[redacted]")
                .contains("client_secret=[redacted]")
                .doesNotContain("hunter2");
    }

    @Test
    void flagsSecretNames() {
        assertThat(EnvRedactor.isSecretName("GITHUB_TOKEN")).isTrue();
        assertThat(EnvRedactor.isSecretName("ssh_key_path")).isTrue();
        assertThat(EnvRedactor.isSecretName("HOME")).isFalse();
    }
}
