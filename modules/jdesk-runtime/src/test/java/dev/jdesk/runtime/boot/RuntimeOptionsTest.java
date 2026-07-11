package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.runtime.assets.AssetSource;
import dev.jdesk.runtime.assets.ClasspathAssetSource;
import dev.jdesk.runtime.assets.CspValidator;
import dev.jdesk.runtime.assets.DirectoryAssetSource;
import dev.jdesk.runtime.assets.MapAssetSource;
import dev.jdesk.runtime.ipc.EventOverflowPolicy;
import dev.jdesk.runtime.ipc.IpcLimits;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RuntimeOptions defaults and system-property driven asset-source selection
 * (dir -> module -> classpath -> empty in-memory).
 */
class RuntimeOptionsTest {

    private static final String[] KEYS = {
            "jdesk.dev", "jdesk.assets.dir", "jdesk.assets.module", "jdesk.assets.classpath"
    };

    private static void clearAll() {
        for (String k : KEYS) {
            System.clearProperty(k);
        }
    }

    @Test
    void productionUsesStrictSecureDefaults() {
        AssetSource source = new MapAssetSource();
        RuntimeOptions options = RuntimeOptions.production(source);

        assertThat(options.devMode()).isFalse();
        assertThat(options.assetSource()).isSameAs(source);
        assertThat(options.spaFallback()).isFalse();
        assertThat(options.securityHeaders())
                .containsEntry("Content-Security-Policy", CspValidator.DEFAULT_CSP);
        assertThat(options.limits()).isEqualTo(IpcLimits.DEFAULTS);
        assertThat(options.overflowPolicy()).isEqualTo(EventOverflowPolicy.REJECT);
        assertThat(options.navigationGrace()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void constructorCopiesSecurityHeadersDefensively() {
        AssetSource source = new MapAssetSource();
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("X-A", "1");
        RuntimeOptions options = new RuntimeOptions(false, source, false, mutable,
                IpcLimits.DEFAULTS, EventOverflowPolicy.REJECT, Duration.ofMillis(100));
        mutable.put("X-B", "2");
        assertThat(options.securityHeaders()).containsOnlyKeys("X-A");
    }

    @Test
    void fromSystemPropertiesDefaultsToEmptyInMemorySource() {
        clearAll();
        try {
            RuntimeOptions options = RuntimeOptions.fromSystemProperties();
            assertThat(options.assetSource()).isInstanceOf(MapAssetSource.class);
            assertThat(options.devMode()).isFalse();
        } finally {
            clearAll();
        }
    }

    @Test
    void devPropertyEnablesDevMode() {
        clearAll();
        System.setProperty("jdesk.dev", "true");
        try {
            assertThat(RuntimeOptions.fromSystemProperties().devMode()).isTrue();
        } finally {
            clearAll();
        }
    }

    @Test
    void assetsDirSelectsDirectorySource(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("web"));
        clearAll();
        System.setProperty("jdesk.assets.dir", dir.toString());
        try {
            assertThat(RuntimeOptions.fromSystemProperties().assetSource())
                    .isInstanceOf(DirectoryAssetSource.class);
        } finally {
            clearAll();
        }
    }

    @Test
    void invalidAssetsDirWrapsIoException() {
        clearAll();
        System.setProperty("jdesk.assets.dir", "/definitely/not/a/real/dir/jdesk-xyz");
        try {
            assertThatThrownBy(RuntimeOptions::fromSystemProperties)
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("jdesk.assets.dir");
        } finally {
            clearAll();
        }
    }

    @Test
    void assetsModuleSelectsClasspathModuleSource() {
        clearAll();
        // java.base is always present in the boot layer.
        System.setProperty("jdesk.assets.module", "java.base");
        try {
            assertThat(RuntimeOptions.fromSystemProperties().assetSource())
                    .isInstanceOf(ClasspathAssetSource.class);
        } finally {
            clearAll();
        }
    }

    @Test
    void unknownAssetsModuleFailsFast() {
        clearAll();
        System.setProperty("jdesk.assets.module", "no.such.module.anywhere");
        try {
            assertThatThrownBy(RuntimeOptions::fromSystemProperties)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no.such.module.anywhere");
        } finally {
            clearAll();
        }
    }

    @Test
    void assetsClasspathSelectsClasspathSource() {
        clearAll();
        System.setProperty("jdesk.assets.classpath", "web");
        try {
            assertThat(RuntimeOptions.fromSystemProperties().assetSource())
                    .isInstanceOf(ClasspathAssetSource.class);
        } finally {
            clearAll();
        }
    }

    @Test
    void assetsDirTakesPrecedenceOverModuleAndClasspath(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        clearAll();
        System.setProperty("jdesk.assets.dir", dir.toString());
        System.setProperty("jdesk.assets.module", "java.base");
        System.setProperty("jdesk.assets.classpath", "web");
        try {
            assertThat(RuntimeOptions.fromSystemProperties().assetSource())
                    .isInstanceOf(DirectoryAssetSource.class);
        } finally {
            clearAll();
        }
    }
}
