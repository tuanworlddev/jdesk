package dev.jdesk.gradle;

import org.gradle.api.Action;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;

/**
 * The {@code jdesk { ... }} project extension (spec section 14):
 *
 * <pre>{@code
 * jdesk {
 *     applicationId.set("dev.example.app")
 *     mainClass.set("dev.example.App")
 *     frontend { ... }
 * }
 * }</pre>
 */
public abstract class JDeskExtension {

    /** Reverse-DNS application id, e.g. {@code dev.example.app}. */
    public abstract Property<String> getApplicationId();

    /** Fully qualified main class of the application. */
    public abstract Property<String> getMainClass();

    /** Named JPMS application module used by production packaging. */
    public abstract Property<String> getMainModule();

    @Nested
    public abstract JDeskFrontendExtension getFrontend();

    public void frontend(Action<? super JDeskFrontendExtension> action) {
        action.execute(getFrontend());
    }

    @Nested
    public abstract JDeskDevelopmentExtension getDevelopment();

    /** Java compile-and-restart settings used by {@code jdeskDev}. */
    public void development(Action<? super JDeskDevelopmentExtension> action) {
        action.execute(getDevelopment());
    }

    @Nested
    public abstract JDeskSigningExtension getSigning();

    /** Signing configuration hooks (spec 16.3); unconfigured builds are UNSIGNED. */
    public void signing(Action<? super JDeskSigningExtension> action) {
        action.execute(getSigning());
    }
}
