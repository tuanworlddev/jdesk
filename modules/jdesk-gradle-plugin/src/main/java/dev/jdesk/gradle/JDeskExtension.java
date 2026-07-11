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

    @Nested
    public abstract JDeskFrontendExtension getFrontend();

    public void frontend(Action<? super JDeskFrontendExtension> action) {
        action.execute(getFrontend());
    }
}
