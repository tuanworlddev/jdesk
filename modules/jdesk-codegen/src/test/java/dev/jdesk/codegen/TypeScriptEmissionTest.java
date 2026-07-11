package dev.jdesk.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeScriptEmissionTest {

    @Test
    void listMapOptionalAndNestedRecordsMapToGoldenTypes(@TempDir Path dir) throws IOException {
        TestCompiler.Result result = TestCompiler.compile(dir,
                Map.of(Fixtures.CATALOG_SERVICE_NAME, Fixtures.CATALOG_SERVICE));
        assertThat(result.success()).as(result.errorText()).isTrue();
        assertThat(result.tsFile("types.ts"))
                .isEqualTo(DeterminismGoldenTest.golden("catalog-types.ts.golden"));
        assertThat(result.tsFile("commands.ts"))
                .isEqualTo(DeterminismGoldenTest.golden("catalog-commands.ts.golden"));
    }

    @Test
    void scalarMappingsAreEmitted(@TempDir Path dir) throws IOException {
        TestCompiler.Result result = TestCompiler.compile(dir, Map.of("com.example.app.TestService",
                Fixtures.service("""
                        public record Mixed(boolean flag, Boolean boxedFlag, int count, long big,
                                double ratio, Byte tiny, char letter, Character boxedLetter,
                                String text, java.util.Optional<java.util.List<String>> lines,
                                java.util.List<java.util.Optional<String>> cells) {
                        }

                        @DesktopCommand("app.mix")
                        @PublicDesktopCommand
                        public CompletionStage<Mixed> mix(InvocationContext context) {
                            return null;
                        }
                        """)));
        assertThat(result.success()).as(result.errorText()).isTrue();
        assertThat(result.tsFile("types.ts"))
                .contains("flag: boolean;")
                .contains("boxedFlag: boolean;")
                .contains("count: number;")
                .contains("big: number;")
                .contains("ratio: number;")
                .contains("tiny: number;")
                .contains("letter: string;")
                .contains("boxedLetter: string;")
                .contains("text: string;")
                .contains("lines: string[] | null;")
                .contains("cells: (string | null)[];");
    }

    @Test
    void outputDirOptionRedirectsTypeScript(@TempDir Path dir) throws IOException {
        Path tsDir = dir.resolve("ts-out");
        TestCompiler.Result result = TestCompiler.compile(dir,
                Map.of(Fixtures.GREETING_SERVICE_NAME, Fixtures.GREETING_SERVICE),
                List.of("-Ajdesk.ts.outputDir=" + tsDir));
        assertThat(result.success()).as(result.errorText()).isTrue();
        assertThat(tsDir.resolve("types.ts")).exists();
        assertThat(tsDir.resolve("commands.ts")).exists();
        assertThat(result.classesDir().resolve("jdesk-ts")).doesNotExist();
        assertThat(Files.readString(tsDir.resolve("types.ts"), StandardCharsets.UTF_8))
                .isEqualTo(DeterminismGoldenTest.golden("greeting-types.ts.golden"));
    }

    @Test
    void noTypeScriptIsEmittedWhenCompilationFails(@TempDir Path dir) throws IOException {
        TestCompiler.Result result = TestCompiler.compile(dir, Map.of("com.example.app.TestService",
                Fixtures.service("""
                        @DesktopCommand("app.bad name")
                        @PublicDesktopCommand
                        public CompletionStage<String> bad(InvocationContext context) {
                            return null;
                        }
                        """)));
        assertThat(result.success()).isFalse();
        assertThat(result.classesDir().resolve("jdesk-ts")).doesNotExist();
    }
}
