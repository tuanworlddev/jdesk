package dev.jdesk.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every compile-time rejection required by spec section 11 and ADR-005. */
class RejectionTest {

    @TempDir
    Path dir;

    private TestCompiler.Result compileService(String body) throws IOException {
        return TestCompiler.compile(dir, Map.of("com.example.app.TestService",
                Fixtures.service(body)));
    }

    private void assertRejected(TestCompiler.Result result, String expectedMessagePart) {
        assertThat(result.success()).isFalse();
        assertThat(result.errorText()).contains(expectedMessagePart);
    }

    @Test
    void duplicateCommandNamesAcrossClassesAreRejected() throws IOException {
        String other = """
                package com.example.app;

                import dev.jdesk.api.DesktopCommand;
                import dev.jdesk.api.InvocationContext;
                import dev.jdesk.api.PublicDesktopCommand;
                import java.util.concurrent.CompletionStage;

                public class OtherService {

                    @DesktopCommand("app.ping")
                    @PublicDesktopCommand
                    public CompletionStage<String> otherPing(InvocationContext context) {
                        return null;
                    }
                }
                """;
        TestCompiler.Result result = TestCompiler.compile(dir, Map.of(
                "com.example.app.TestService", Fixtures.service("""
                        @DesktopCommand("app.ping")
                        @PublicDesktopCommand
                        public CompletionStage<String> ping(InvocationContext context) {
                            return null;
                        }
                        """),
                "com.example.app.OtherService", other));
        assertRejected(result, "Duplicate command name \"app.ping\"");
    }

    @Test
    void invalidCommandNameGrammarIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("Greeting.Greet")
                @PublicDesktopCommand
                public CompletionStage<String> greet(InvocationContext context) {
                    return null;
                }
                """), "Invalid command name \"Greeting.Greet\"");
    }

    @Test
    void overlongCommandNameIsRejected() throws IOException {
        String name = "a" + "b".repeat(140);
        assertRejected(compileService("""
                @DesktopCommand("%s")
                @PublicDesktopCommand
                public CompletionStage<String> greet(InvocationContext context) {
                    return null;
                }
                """.formatted(name)), "Invalid command name");
    }

    @Test
    void missingCapabilityAnnotationIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.ping")
                public CompletionStage<String> ping(InvocationContext context) {
                    return null;
                }
                """), "must declare @RequiresCapability");
    }

    @Test
    void capabilityAndPublicTogetherAreRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.ping")
                @RequiresCapability("app.ping")
                @PublicDesktopCommand
                public CompletionStage<String> ping(InvocationContext context) {
                    return null;
                }
                """), "mutually exclusive");
    }

    @Test
    void nonPublicMethodIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.ping")
                @PublicDesktopCommand
                CompletionStage<String> ping(InvocationContext context) {
                    return null;
                }
                """), "@DesktopCommand method must be public");
    }

    @Test
    void nonPublicEnclosingClassIsRejected() throws IOException {
        String source = """
                package com.example.app;

                import dev.jdesk.api.DesktopCommand;
                import dev.jdesk.api.InvocationContext;
                import dev.jdesk.api.PublicDesktopCommand;
                import java.util.concurrent.CompletionStage;

                class HiddenService {

                    @DesktopCommand("app.ping")
                    @PublicDesktopCommand
                    public CompletionStage<String> ping(InvocationContext context) {
                        return null;
                    }
                }
                """;
        TestCompiler.Result result =
                TestCompiler.compile(dir, Map.of("com.example.app.HiddenService", source));
        assertRejected(result, "must be public");
    }

    @Test
    void nonRecordDtoIsRejected() throws IOException {
        assertRejected(compileService("""
                public static class Payload {
                    public String name;
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "must be a public record, String, or a boxed primitive");
    }

    @Test
    void nonPublicDtoRecordIsRejected() throws IOException {
        assertRejected(compileService("""
                record Payload(String name) {
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "must be public");
    }

    @Test
    void rawObjectInSignatureIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<Object> save(InvocationContext context) {
                    return null;
                }
                """), "raw Object is not allowed");
    }

    @Test
    void classFieldInDtoIsRejected() throws IOException {
        assertRejected(compileService("""
                public record Payload(Class<?> type) {
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "Class is not allowed");
    }

    @Test
    void reflectMethodFieldInDtoIsRejected() throws IOException {
        assertRejected(compileService("""
                public record Payload(java.lang.reflect.Method method) {
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "java.lang.reflect.Method is not allowed");
    }

    @Test
    void throwableFieldInDtoIsRejected() throws IOException {
        assertRejected(compileService("""
                public record Payload(IllegalStateException failure) {
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "Throwable types");
    }

    @Test
    void memorySegmentFieldInDtoIsRejected() throws IOException {
        assertRejected(compileService("""
                public record Payload(java.lang.foreign.MemorySegment segment) {
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "native handle types");
    }

    @Test
    void wildcardGenericsAreRejected() throws IOException {
        assertRejected(compileService("""
                public record Payload(java.util.List<? extends String> names) {
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "wildcard types are not supported");
    }

    @Test
    void rawListIsRejected() throws IOException {
        assertRejected(compileService("""
                @SuppressWarnings("rawtypes")
                public record Payload(java.util.List names) {
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "raw java.util.List is not supported");
    }

    @Test
    void mapWithNonStringKeyIsRejected() throws IOException {
        assertRejected(compileService("""
                public record Payload(java.util.Map<Integer, String> byId) {
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "Map keys must be String");
    }

    @Test
    void arrayComponentIsRejected() throws IOException {
        assertRejected(compileService("""
                public record Payload(String[] names) {
                }

                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(Payload payload, InvocationContext context) {
                    return null;
                }
                """), "arrays are not supported");
    }

    @Test
    void directlyRecursiveRecordIsRejected() throws IOException {
        assertRejected(compileService("""
                public record Node(String name, java.util.List<Node> children) {
                }

                @DesktopCommand("app.tree")
                @PublicDesktopCommand
                public CompletionStage<Node> tree(InvocationContext context) {
                    return null;
                }
                """), "Recursive record type");
    }

    @Test
    void transitivelyRecursiveRecordIsRejected() throws IOException {
        assertRejected(compileService("""
                public record Left(Right right) {
                }

                public record Right(java.util.Optional<Left> left) {
                }

                @DesktopCommand("app.tree")
                @PublicDesktopCommand
                public CompletionStage<Left> tree(InvocationContext context) {
                    return null;
                }
                """), "Recursive record type");
    }

    @Test
    void overloadedCommandMethodsAreRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.pingA")
                @PublicDesktopCommand
                public CompletionStage<String> ping(InvocationContext context) {
                    return null;
                }

                @DesktopCommand("app.pingB")
                @PublicDesktopCommand
                public CompletionStage<String> ping(String name, InvocationContext context) {
                    return null;
                }
                """), "Overloaded @DesktopCommand methods are not supported");
    }

    @Test
    void nonCompletionStageReturnTypeIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.ping")
                @PublicDesktopCommand
                public String ping(InvocationContext context) {
                    return null;
                }
                """), "must return java.util.concurrent.CompletionStage<Res>");
    }

    @Test
    void completableFutureReturnTypeIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.ping")
                @PublicDesktopCommand
                public java.util.concurrent.CompletableFuture<String> ping(InvocationContext context) {
                    return null;
                }
                """), "must return java.util.concurrent.CompletionStage<Res>");
    }

    @Test
    void wildcardResponseTypeIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.ping")
                @PublicDesktopCommand
                public CompletionStage<?> ping(InvocationContext context) {
                    return null;
                }
                """), "Wildcard response types are not supported");
    }

    @Test
    void staticCommandMethodIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.ping")
                @PublicDesktopCommand
                public static CompletionStage<String> ping(InvocationContext context) {
                    return null;
                }
                """), "must be an instance method");
    }

    @Test
    void payloadWithoutContextParameterIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.save")
                @PublicDesktopCommand
                public CompletionStage<String> save(String payload) {
                    return null;
                }
                """), "single-parameter command method must take");
    }

    @Test
    void commandNameNamespaceConflictIsRejected() throws IOException {
        assertRejected(compileService("""
                @DesktopCommand("app.file")
                @PublicDesktopCommand
                public CompletionStage<String> file(InvocationContext context) {
                    return null;
                }

                @DesktopCommand("app.file.read")
                @PublicDesktopCommand
                public CompletionStage<String> read(InvocationContext context) {
                    return null;
                }
                """), "cannot also be a namespace");
    }

    @Test
    void nestedServiceClassIsRejected() throws IOException {
        String source = """
                package com.example.app;

                import dev.jdesk.api.DesktopCommand;
                import dev.jdesk.api.InvocationContext;
                import dev.jdesk.api.PublicDesktopCommand;
                import java.util.concurrent.CompletionStage;

                public class Outer {

                    public static class Inner {

                        @DesktopCommand("app.ping")
                        @PublicDesktopCommand
                        public CompletionStage<String> ping(InvocationContext context) {
                            return null;
                        }
                    }
                }
                """;
        TestCompiler.Result result =
                TestCompiler.compile(dir, Map.of("com.example.app.Outer", source));
        assertRejected(result, "must be a top-level class");
    }

    @Test
    void typeScriptSimpleNameCollisionIsRejected() throws IOException {
        String other = """
                package com.example.other;

                public record Payload(int count) {
                }
                """;
        String service = """
                package com.example.app;

                import dev.jdesk.api.DesktopCommand;
                import dev.jdesk.api.InvocationContext;
                import dev.jdesk.api.PublicDesktopCommand;
                import java.util.concurrent.CompletionStage;

                public class TestService {

                    public record Payload(String name) {
                    }

                    @DesktopCommand("app.save")
                    @PublicDesktopCommand
                    public CompletionStage<String> save(Payload payload, InvocationContext context) {
                        return null;
                    }

                    @DesktopCommand("app.count")
                    @PublicDesktopCommand
                    public CompletionStage<String> count(com.example.other.Payload payload,
                            InvocationContext context) {
                        return null;
                    }
                }
                """;
        TestCompiler.Result result = TestCompiler.compile(dir, Map.of(
                "com.example.app.TestService", service,
                "com.example.other.Payload", other));
        assertRejected(result, "TypeScript type name collision");
    }
}
