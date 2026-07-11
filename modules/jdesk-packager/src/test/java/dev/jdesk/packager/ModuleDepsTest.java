package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ModuleDepsTest {

    @Test
    void parsesSimpleModuleList() {
        assertThat(ModuleDeps.parse("java.base,java.net.http,jdk.unsupported\n"))
                .containsExactly("java.base", "java.net.http", "jdk.unsupported");
    }

    @Test
    void takesLastNonBlankLine() {
        String output = "some informational line is ignored? no: last line wins\n"
                + "\n"
                + "java.desktop,java.base\n\n";
        assertThat(ModuleDeps.parse(output)).containsExactly("java.base", "java.desktop");
    }

    @Test
    void emptyOutputYieldsEmptySet() {
        assertThat(ModuleDeps.parse("")).isEmpty();
        assertThat(ModuleDeps.parse("\n \n")).isEmpty();
    }

    @Test
    void rejectsNonModuleListOutput() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ModuleDeps.parse("Error: some jdeps failure"))
                .withMessageContaining("jdeps");
    }
}
