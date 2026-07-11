package dev.jdesk.runtime.assets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.runtime.assets.AssetSource.Asset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ClasspathAssetSource resolution over the {@code web/} test resources, exercising both
 * the ClassLoader-backed and Module-backed constructors, plus hit and miss paths.
 */
class ClasspathAssetSourceTest {

    @Test
    void classLoaderConstructorFindsResourceUnderPrefix() throws IOException {
        ClasspathAssetSource source =
                new ClasspathAssetSource(getClass().getClassLoader(), "web");

        Optional<Asset> asset = source.find("index.html");
        assertThat(asset).isPresent();
        String body = new String(asset.get().open().open().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(body).contains("hi");
        assertThat(asset.get().size()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void classLoaderConstructorAppendsTrailingSlashToPrefix() throws IOException {
        // Prefix without trailing slash must still resolve nested resources.
        ClasspathAssetSource source =
                new ClasspathAssetSource(getClass().getClassLoader(), "web");
        assertThat(source.find("sub/app.css")).isPresent();
    }

    @Test
    void classLoaderConstructorReturnsEmptyForMiss() throws IOException {
        ClasspathAssetSource source =
                new ClasspathAssetSource(getClass().getClassLoader(), "web/");
        assertThat(source.find("does-not-exist.html")).isEmpty();
    }

    @Test
    void moduleConstructorFindsResourceInNamedModule() throws IOException {
        // java.base is a resolved named module in the boot layer with readable resources.
        Module javaBase = Object.class.getModule();
        ClasspathAssetSource source = new ClasspathAssetSource(javaBase, "java/lang");

        Optional<Asset> asset = source.find("Object.class");
        assertThat(asset).isPresent();
        assertThat(asset.get().open().open().readAllBytes()).isNotEmpty();
    }

    @Test
    void moduleConstructorReturnsEmptyForMiss() throws IOException {
        Module javaBase = Object.class.getModule();
        ClasspathAssetSource source = new ClasspathAssetSource(javaBase, "java/lang");
        assertThat(source.find("NoSuchClass.class")).isEmpty();
    }

    @Test
    void moduleConstructorReturnsEmptyWhenModuleHasNoLayer() throws IOException {
        // The unnamed module (test classpath) has no module layer.
        Module unnamed = getClass().getModule();
        ClasspathAssetSource source = new ClasspathAssetSource(unnamed, "web");
        assertThat(source.find("index.html")).isEmpty();
    }
}
