package dev.jdesk.runtime.assets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Extension-based MIME resolution (spec section 9.1: correct MIME types). */
class MimeTypesTest {

    @ParameterizedTest
    @CsvSource({
            "index.html, text/html; charset=utf-8",
            "a/b/page.htm, text/html; charset=utf-8",
            "app.js, text/javascript; charset=utf-8",
            "mod.mjs, text/javascript; charset=utf-8",
            "styles.css, text/css; charset=utf-8",
            "data.json, application/json; charset=utf-8",
            "app.js.map, application/json; charset=utf-8",
            "icon.svg, image/svg+xml",
            "img.png, image/png",
            "img.jpg, image/jpeg",
            "img.jpeg, image/jpeg",
            "anim.gif, image/gif",
            "img.webp, image/webp",
            "img.avif, image/avif",
            "favicon.ico, image/x-icon",
            "mod.wasm, application/wasm",
            "font.woff, font/woff",
            "font.woff2, font/woff2",
            "font.ttf, font/ttf",
            "font.otf, font/otf",
            "readme.txt, text/plain; charset=utf-8",
            "feed.xml, application/xml",
            "doc.pdf, application/pdf",
            "song.mp3, audio/mpeg",
            "clip.mp4, video/mp4",
            "clip.webm, video/webm",
    })
    void knownExtensions(String path, String expected) {
        assertThat(MimeTypes.forPath(path)).isEqualTo(expected);
    }

    @Test
    void unknownExtensionFallsBackToOctetStream() {
        assertThat(MimeTypes.forPath("file.xyz")).isEqualTo("application/octet-stream");
    }

    @Test
    void noExtensionFallsBackToOctetStream() {
        assertThat(MimeTypes.forPath("Makefile")).isEqualTo("application/octet-stream");
        assertThat(MimeTypes.forPath("a/b/noext")).isEqualTo("application/octet-stream");
    }

    @Test
    void dotfileHasNoExtension() {
        // ".gitignore" at the root: the leading dot is not an extension separator here.
        assertThat(MimeTypes.forPath(".gitignore")).isEqualTo("application/octet-stream");
        assertThat(MimeTypes.forPath("dir/.gitignore")).isEqualTo("application/octet-stream");
    }

    @Test
    void trailingDotFallsBackToOctetStream() {
        assertThat(MimeTypes.forPath("weird.")).isEqualTo("application/octet-stream");
    }

    @Test
    void extensionLookupIsCaseInsensitive() {
        assertThat(MimeTypes.forPath("INDEX.HTML")).isEqualTo("text/html; charset=utf-8");
        assertThat(MimeTypes.forPath("APP.JS")).isEqualTo("text/javascript; charset=utf-8");
        assertThat(MimeTypes.forPath("Photo.PnG")).isEqualTo("image/png");
    }

    @Test
    void dotInDirectoryNameDoesNotLeakAsExtension() {
        // Last dot before the last slash must not count as an extension.
        assertThat(MimeTypes.forPath("v1.2/binary")).isEqualTo("application/octet-stream");
    }
}
