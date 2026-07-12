package dev.jdesk.runtime.arch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.runtime.json.JsonCodec;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {
    @Test
    void runtimeDoesNotRequireHttpServerOrExposePlatformSpiTransitively() throws Exception {
        Path classes = Path.of(JsonCodec.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        ModuleDescriptor descriptor = ModuleFinder.of(classes).find("dev.jdesk.runtime")
                .orElseThrow().descriptor();
        assertThat(descriptor.requires()).noneMatch(requirement ->
                requirement.name().equals("jdk.httpserver"));
        ModuleDescriptor.Requires spi = descriptor.requires().stream()
                .filter(requirement -> requirement.name().equals("dev.jdesk.webview.spi"))
                .findFirst().orElseThrow();
        assertThat(spi.modifiers()).doesNotContain(ModuleDescriptor.Requires.Modifier.TRANSITIVE);
    }
}
