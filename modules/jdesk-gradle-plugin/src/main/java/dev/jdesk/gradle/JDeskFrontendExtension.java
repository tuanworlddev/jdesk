package dev.jdesk.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;

/**
 * Frontend configuration nested inside the {@code jdesk} extension (spec section 14):
 *
 * <pre>{@code
 * jdesk {
 *     frontend {
 *         directory.set(layout.projectDirectory.dir("ui"))
 *         devCommand.set(listOf("npm", "run", "dev"))
 *         buildCommand.set(listOf("npm", "run", "build"))
 *         devUrl.set("http://127.0.0.1:5173")
 *         distDirectory.set(layout.projectDirectory.dir("ui/dist"))
 *     }
 * }
 * }</pre>
 *
 * All properties are lazy and configuration-cache compatible. Leaving {@code directory}
 * unset means "no frontend": frontend tasks skip with NO-SOURCE instead of failing.
 */
public interface JDeskFrontendExtension {

    /** Root of the frontend project (e.g. {@code ui/}). Unset = no frontend. */
    DirectoryProperty getDirectory();

    /** Command starting the dev server with HMR, run in {@link #getDirectory()}. */
    ListProperty<String> getDevCommand();

    /** Command producing the production bundle, run in {@link #getDirectory()}. */
    ListProperty<String> getBuildCommand();

    /** Exact dev-server origin, e.g. {@code http://127.0.0.1:5173}. */
    Property<String> getDevUrl();

    /** Where {@link #getBuildCommand()} writes the bundle. Default: {@code directory/dist}. */
    DirectoryProperty getDistDirectory();

    /**
     * Where generated TypeScript bindings are written by the jdesk-codegen annotation
     * processor. Default: {@code directory/src/generated}.
     */
    DirectoryProperty getTsOutputDir();

    /**
     * Static-copy mode: {@code jdeskFrontendBuild} recursively copies {@link #getDirectory()}
     * into {@link #getDistDirectory()} (excluding {@code node_modules}/{@code .git}/the dist
     * dir) instead of running {@link #getBuildCommand()}. Preserves the tree, so
     * {@code /src/main.js}-style paths resolve — a plain HTML/CSS/JS app needs no bundler
     * and no {@code Build.java}. Enable with {@code frontend { staticCopy() }}.
     */
    @Internal
    Property<Boolean> getStaticCopy();

    /** Enables {@linkplain #getStaticCopy() static-copy mode}. */
    default void staticCopy() {
        getStaticCopy().set(true);
    }
}
