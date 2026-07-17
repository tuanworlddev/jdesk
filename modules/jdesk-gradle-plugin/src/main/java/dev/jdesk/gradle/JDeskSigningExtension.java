package dev.jdesk.gradle;

import org.gradle.api.provider.Property;

/**
 * Signing configuration hooks (spec section 16.3). These describe the signing surface;
 * CI packages built without them are {@code UNSIGNED} and do not satisfy a signed-release
 * gate. Signing itself is delegated to the platform toolchains (signtool / codesign +
 * notarytool / dpkg-sig / rpmsign) invoked from the values below; see
 * docs/packaging/packaging-and-signing.md.
 */
public abstract class JDeskSigningExtension {

    /** Windows Authenticode signing identity (certificate subject or thumbprint). */
    public abstract Property<String> getWindowsCertificate();

    /** Windows timestamp URL (RFC 3161). */
    public abstract Property<String> getWindowsTimestampUrl();

    /** macOS Developer ID Application identity, e.g. {@code Developer ID Application: ...}. */
    public abstract Property<String> getMacSigningIdentity();

    /** macOS notarization keychain profile name (notarytool {@code --keychain-profile}). */
    public abstract Property<String> getMacNotarizationProfile();

    /** Linux GPG key id for an armored detached installer signature. */
    public abstract Property<String> getLinuxSigningKey();

    /**
     * Optional GPG passphrase for headless signing. Prefer a lazy environment-variable provider;
     * the installer task sends it to gpg over stdin and never places it on the command line.
     */
    public abstract Property<String> getLinuxSigningPassphrase();

    /** True when at least one signing identity is configured for the current OS. */
    public boolean isConfiguredForAnyPlatform() {
        return getWindowsCertificate().isPresent()
                || getMacSigningIdentity().isPresent()
                || getLinuxSigningKey().isPresent();
    }
}
