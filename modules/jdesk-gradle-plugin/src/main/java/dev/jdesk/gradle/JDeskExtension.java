package dev.jdesk.gradle;

import org.gradle.api.Action;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
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

    /**
     * Human-readable application name shown to the user, e.g. {@code "Dragon 7"}. Drives the
     * packaged app's {@code CFBundleName} (the bold name in the macOS menu bar) and, via a
     * {@code -Djdesk.applicationName} launch option jpackage embeds, the runtime {@code Quit <Name>}
     * menu item — so both surfaces always agree. When unset, both derive from the last
     * {@link #getApplicationId() applicationId} segment.
     */
    public abstract Property<String> getApplicationName();

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

    @Nested
    public abstract JDeskDeepLinkExtension getDeepLink();

    /** {@code scheme://} deep-link registration for packaged apps. */
    public void deepLink(Action<? super JDeskDeepLinkExtension> action) {
        action.execute(getDeepLink());
    }

    /** Application icon file ({@code .icns} on macOS, {@code .ico} on Windows). */
    public abstract RegularFileProperty getAppIcon();

    /**
     * File associations, each encoded as {@code extension\tmimeType\tdescription}; add via
     * {@link #fileAssociation}. The package task materializes jpackage
     * {@code --file-associations} properties files and {@code CFBundleDocumentTypes}.
     */
    public abstract ListProperty<String> getFileAssociations();

    /** Registers an "Open with" file association (e.g. {@code "hex", "application/x-hex", "JDesk Hex"}). */
    public void fileAssociation(String extension, String mimeType, String description) {
        getFileAssociations().add(extension + "\t" + mimeType + "\t" + description);
    }
}
