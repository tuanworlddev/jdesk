package dev.jdesk.runtime.boot;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;

/**
 * Platform-standard per-application directories (data / config / cache), resolved from the
 * OS conventions:
 *
 * <ul>
 *   <li><b>Windows</b>: {@code %APPDATA%\<id>} (data), {@code %APPDATA%\<id>\config},
 *       {@code %LOCALAPPDATA%\<id>\cache}.</li>
 *   <li><b>macOS</b>: {@code ~/Library/Application Support/<id>} (data + config),
 *       {@code ~/Library/Caches/<id>}.</li>
 *   <li><b>Linux/other</b>: {@code $XDG_DATA_HOME/<id>}, {@code $XDG_CONFIG_HOME/<id>},
 *       {@code $XDG_CACHE_HOME/<id>} (defaulting to {@code ~/.local/share}, {@code ~/.config},
 *       {@code ~/.cache}).</li>
 * </ul>
 *
 * <p>Setting {@code -Djdesk.paths.dir=<base>} overrides all three with
 * {@code <base>/<id>/{data,config,cache}} — used for tests and sandboxed runs. The path
 * computation is pure (no I/O): callers create the directories on demand.</p>
 */
final class AppPaths {

    private final Path dataDir;
    private final Path configDir;
    private final Path cacheDir;

    private AppPaths(Path dataDir, Path configDir, Path cacheDir) {
        this.dataDir = dataDir;
        this.configDir = configDir;
        this.cacheDir = cacheDir;
    }

    Path dataDir() {
        return dataDir;
    }

    Path configDir() {
        return configDir;
    }

    Path cacheDir() {
        return cacheDir;
    }

    /** Resolves the directories for {@code applicationId} from the real environment. */
    static AppPaths forApplication(String applicationId) {
        return compute(System.getProperty("os.name", ""), System::getenv,
                Path.of(System.getProperty("user.home", ".")), applicationId,
                System.getProperty("jdesk.paths.dir"));
    }

    /** Pure resolver — injectable OS name / environment / home / override for testing. */
    static AppPaths compute(String osName, Function<String, String> env, Path home,
            String applicationId, String override) {
        String id = sanitize(applicationId);
        if (override != null && !override.isBlank()) {
            Path base = Path.of(override).resolve(id);
            return new AppPaths(base.resolve("data"), base.resolve("config"),
                    base.resolve("cache"));
        }
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            Path roaming = dir(env, "APPDATA", home.resolve("AppData").resolve("Roaming"));
            Path local = dir(env, "LOCALAPPDATA", home.resolve("AppData").resolve("Local"));
            return new AppPaths(roaming.resolve(id), roaming.resolve(id).resolve("config"),
                    local.resolve(id).resolve("cache"));
        }
        if (os.contains("mac") || os.contains("darwin")) {
            Path support = home.resolve("Library").resolve("Application Support").resolve(id);
            return new AppPaths(support, support, home.resolve("Library")
                    .resolve("Caches").resolve(id));
        }
        Path data = dir(env, "XDG_DATA_HOME", home.resolve(".local").resolve("share")).resolve(id);
        Path config = dir(env, "XDG_CONFIG_HOME", home.resolve(".config")).resolve(id);
        Path cache = dir(env, "XDG_CACHE_HOME", home.resolve(".cache")).resolve(id);
        return new AppPaths(data, config, cache);
    }

    private static Path dir(Function<String, String> env, String key, Path fallback) {
        String value = env.apply(key);
        return value != null && !value.isBlank() ? Path.of(value) : fallback;
    }

    /** Keeps the id usable as a single path segment (no separators or traversal). */
    private static String sanitize(String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            return "jdesk-app";
        }
        String cleaned = applicationId.strip()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replace("..", "_");
        return cleaned.isBlank() ? "jdesk-app" : cleaned;
    }
}
