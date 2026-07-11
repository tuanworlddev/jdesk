package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Command registry construction rules and command name grammar. */
class CommandRegistryTest {

    private static final CommandHandler NOOP_HANDLER =
            (request, context) -> CompletableFuture.completedFuture(null);

    private static CommandDefinition definition(String name) {
        return new CommandDefinition(
                name, Optional.empty(), Object.class, Optional.empty(), NOOP_HANDLER);
    }

    @Test
    void duplicateNameThrowsIllegalState() {
        CommandDefinition first = definition("greeting.greet");
        CommandDefinition duplicate = definition("greeting.greet");

        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> CommandRegistry.of(first, duplicate));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE);
        assertThat(e.publicMessage()).contains("greeting.greet");
    }

    @Test
    void findReturnsRegisteredDefinition() {
        CommandDefinition greet = definition("greeting.greet");
        CommandDefinition ping = definition("ping");
        CommandRegistry registry = CommandRegistry.of(greet, ping);

        assertThat(registry.find("greeting.greet")).contains(greet);
        assertThat(registry.find("ping")).contains(ping);
        assertThat(registry.find("missing.command")).isEmpty();
    }

    @Test
    void commandNamesAndSizeReflectContents() {
        CommandRegistry registry = CommandRegistry.of(
                definition("greeting.greet"), definition("a.b.c"), definition("ping"));

        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.commandNames())
                .containsExactlyInAnyOrder("greeting.greet", "a.b.c", "ping");
    }

    @Test
    void emptyRegistryIsAllowed() {
        CommandRegistry registry = CommandRegistry.of();
        assertThat(registry.size()).isZero();
        assertThat(registry.commandNames()).isEmpty();
        assertThat(registry.find("anything")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "greeting.greet",
            "a.b.c",
            "ping",
            "camelCase.segmentTwo",
            "app.doThing2",
            "x",
    })
    void acceptsValidCommandNames(String name) {
        assertThat(definition(name).name()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Uppercase.first",   // segment must start lower-case
            "greeting.Greet",    // second segment starts upper-case
            "",
            ".leadingDot",
            "trailingDot.",
            "double..dot",
            "has-hyphen.cmd",
            "has_underscore",
            "1numeric.start",
            "space name",
    })
    void rejectsInvalidCommandNames(String name) {
        JDeskException e = catchThrowableOfType(JDeskException.class, () -> definition(name));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void acceptsNameOfExactly128Chars() {
        String name = "a" + "b".repeat(127);
        assertThat(name).hasSize(128);
        assertThat(definition(name).name()).isEqualTo(name);
    }

    @Test
    void rejectsNameLongerThan128Chars() {
        String name = "a" + "b".repeat(128);
        assertThat(name).hasSize(129);
        JDeskException e = catchThrowableOfType(JDeskException.class, () -> definition(name));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void definitionRejectsNullComponents() {
        assertThat(catchThrowableOfType(NullPointerException.class, () -> new CommandDefinition(
                null, Optional.empty(), Object.class, Optional.empty(), NOOP_HANDLER)))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class, () -> new CommandDefinition(
                "ping", null, Object.class, Optional.empty(), NOOP_HANDLER)))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class, () -> new CommandDefinition(
                "ping", Optional.empty(), null, Optional.empty(), NOOP_HANDLER)))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class, () -> new CommandDefinition(
                "ping", Optional.empty(), Object.class, null, NOOP_HANDLER)))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class, () -> new CommandDefinition(
                "ping", Optional.empty(), Object.class, Optional.empty(), null)))
                .isNotNull();
    }

    @Test
    void definitionCarriesCapabilityAndTimeout() {
        CommandDefinition def = new CommandDefinition(
                "files.read",
                Optional.of("fs.read"),
                String.class,
                Optional.of(Duration.ofSeconds(5)),
                NOOP_HANDLER);
        assertThat(def.requiredCapability()).contains("fs.read");
        assertThat(def.timeout()).contains(Duration.ofSeconds(5));
        assertThat(def.requestType()).isEqualTo(String.class);
    }

    @Test
    void definitionRejectsNonPositiveOrExcessiveTimeouts() {
        for (Duration timeout : new Duration[] {
                Duration.ZERO, Duration.ofNanos(-1), Duration.ofHours(25)}) {
            assertThatThrownBy(() -> new CommandDefinition(
                    "test.timeout", Optional.empty(), Void.class,
                    Optional.of(timeout), NOOP_HANDLER))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        assertThat(new CommandDefinition(
                "test.timeout", Optional.empty(), Void.class,
                Optional.of(Duration.ofHours(24)), NOOP_HANDLER).timeout())
                .contains(Duration.ofHours(24));
    }
}
