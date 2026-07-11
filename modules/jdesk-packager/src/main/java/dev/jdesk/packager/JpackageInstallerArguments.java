package dev.jdesk.packager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds the argument list for a {@code jpackage} installer run from an already-built
 * application image (spec section 16.2). Installers are always built on their target OS;
 * cross-packaging is forbidden. Pure argument construction; execution is the caller's job.
 */
public final class JpackageInstallerArguments {

    /** Installer package types, mapped to the OS that can produce them. */
    public enum Type {
        DMG("mac"), PKG("mac"), MSI("windows"), EXE("windows"), DEB("linux"), RPM("linux");

        private final String os;

        Type(String os) {
            this.os = os;
        }

        public String jpackageType() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String targetOs() {
            return os;
        }
    }

    /** Default installer type for the current OS, or empty if unknown. */
    public static java.util.Optional<Type> defaultForOs(String osName) {
        String lower = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac")) {
            return java.util.Optional.of(Type.DMG);
        }
        if (lower.contains("win")) {
            return java.util.Optional.of(Type.MSI);
        }
        if (lower.contains("linux")) {
            return java.util.Optional.of(Type.DEB);
        }
        return java.util.Optional.empty();
    }

    private final Type type;
    private final String name;
    private final Path appImage;
    private final Path destination;
    private final String appVersion;
    private final String macSigningIdentity;

    private JpackageInstallerArguments(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type");
        this.name = require(builder.name, "name");
        this.appImage = Objects.requireNonNull(builder.appImage, "appImage");
        this.destination = Objects.requireNonNull(builder.destination, "destination");
        this.appVersion = require(builder.appVersion, "appVersion");
        this.macSigningIdentity = builder.macSigningIdentity;
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("jpackage installer requires " + what);
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Arguments to pass after the {@code jpackage} executable. */
    public List<String> toArguments() {
        List<String> args = new ArrayList<>(List.of(
                "--type", type.jpackageType(),
                "--name", name,
                "--app-image", appImage.toString(),
                "--dest", destination.toString(),
                "--app-version", appVersion));
        // Signing is applied by the OS toolchain when an identity is configured; without
        // one the installer is UNSIGNED and does not satisfy a signed-release gate.
        if (type.targetOs().equals("mac") && macSigningIdentity != null
                && !macSigningIdentity.isBlank()) {
            args.add("--mac-sign");
            args.add("--mac-signing-key-user-name");
            args.add(macSigningIdentity);
        }
        return List.copyOf(args);
    }

    public Type type() {
        return type;
    }

    public static final class Builder {
        private Type type;
        private String name;
        private Path appImage;
        private Path destination;
        private String appVersion;
        private String macSigningIdentity;

        private Builder() {
        }

        public Builder type(Type value) {
            this.type = value;
            return this;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        /** The built application image directory (jpackage {@code --app-image}). */
        public Builder appImage(Path value) {
            this.appImage = value;
            return this;
        }

        public Builder destination(Path value) {
            this.destination = value;
            return this;
        }

        public Builder appVersion(String value) {
            this.appVersion = value;
            return this;
        }

        public Builder macSigningIdentity(String value) {
            this.macSigningIdentity = value;
            return this;
        }

        public JpackageInstallerArguments build() {
            return new JpackageInstallerArguments(this);
        }
    }
}
