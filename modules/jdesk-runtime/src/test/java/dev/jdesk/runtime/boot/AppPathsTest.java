package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AppPathsTest {

    private static final Path HOME = Path.of("/home/u");
    private static final String ID = "dev.jdesk.examples.notes";

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void windowsUsesAppDataAndLocalAppData() {
        AppPaths p = AppPaths.compute("Windows 11",
                env(Map.of("APPDATA", "C:\\Users\\u\\AppData\\Roaming",
                        "LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local")),
                HOME, ID, null);
        assertThat(p.dataDir()).isEqualTo(Path.of("C:\\Users\\u\\AppData\\Roaming", ID));
        assertThat(p.configDir()).isEqualTo(Path.of("C:\\Users\\u\\AppData\\Roaming", ID, "config"));
        assertThat(p.cacheDir()).isEqualTo(Path.of("C:\\Users\\u\\AppData\\Local", ID, "cache"));
    }

    @Test
    void windowsFallsBackToHomeWhenEnvMissing() {
        AppPaths p = AppPaths.compute("Windows 10", env(Map.of()), HOME, ID, null);
        assertThat(p.dataDir()).isEqualTo(HOME.resolve("AppData").resolve("Roaming").resolve(ID));
        assertThat(p.cacheDir())
                .isEqualTo(HOME.resolve("AppData").resolve("Local").resolve(ID).resolve("cache"));
    }

    @Test
    void macUsesLibrary() {
        AppPaths p = AppPaths.compute("Mac OS X", env(Map.of()), HOME, ID, null);
        Path support = HOME.resolve("Library").resolve("Application Support").resolve(ID);
        assertThat(p.dataDir()).isEqualTo(support);
        assertThat(p.configDir()).isEqualTo(support);
        assertThat(p.cacheDir()).isEqualTo(HOME.resolve("Library").resolve("Caches").resolve(ID));
    }

    @Test
    void linuxUsesXdgWithDefaults() {
        AppPaths p = AppPaths.compute("Linux", env(Map.of()), HOME, ID, null);
        assertThat(p.dataDir()).isEqualTo(HOME.resolve(".local").resolve("share").resolve(ID));
        assertThat(p.configDir()).isEqualTo(HOME.resolve(".config").resolve(ID));
        assertThat(p.cacheDir()).isEqualTo(HOME.resolve(".cache").resolve(ID));
    }

    @Test
    void linuxHonorsXdgEnvironment() {
        AppPaths p = AppPaths.compute("Linux",
                env(Map.of("XDG_DATA_HOME", "/data", "XDG_CONFIG_HOME", "/cfg",
                        "XDG_CACHE_HOME", "/cache")),
                HOME, ID, null);
        assertThat(p.dataDir()).isEqualTo(Path.of("/data", ID));
        assertThat(p.configDir()).isEqualTo(Path.of("/cfg", ID));
        assertThat(p.cacheDir()).isEqualTo(Path.of("/cache", ID));
    }

    @Test
    void overrideWinsOnEveryPlatform() {
        AppPaths p = AppPaths.compute("Windows 11", env(Map.of()), HOME, ID, "/tmp/box");
        assertThat(p.dataDir()).isEqualTo(Path.of("/tmp/box", ID, "data"));
        assertThat(p.configDir()).isEqualTo(Path.of("/tmp/box", ID, "config"));
        assertThat(p.cacheDir()).isEqualTo(Path.of("/tmp/box", ID, "cache"));
    }

    @Test
    void sanitizesUnsafeApplicationId() {
        AppPaths p = AppPaths.compute("Linux", env(Map.of()), HOME, "../../evil/id", "/base");
        assertThat(p.dataDir().toString()).doesNotContain("..");
    }

    @Test
    void blankIdFallsBack() {
        AppPaths p = AppPaths.compute("Linux", env(Map.of()), HOME, "  ", null);
        assertThat(p.dataDir()).isEqualTo(HOME.resolve(".local").resolve("share").resolve("jdesk-app"));
    }
}
