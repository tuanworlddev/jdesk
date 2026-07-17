package dev.jdesk.packager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure builders for the platform signing/notarization command lines, mirroring
 * {@link JpackageArguments} / {@link JlinkArguments}. Construction is separate from execution so it
 * is unit-testable without certificates; the installer task invokes these against the real
 * toolchains ({@code codesign}, {@code xcrun notarytool/stapler}, {@code signtool}, {@code gpg}).
 * A signed macOS build needs Hardened Runtime + a secure timestamp to notarize, which the
 * {@link #macCodesign} flags below apply.
 */
public final class SigningCommands {

    private SigningCommands() {
    }

    /** {@code codesign} with Hardened Runtime + secure timestamp — required before notarization. */
    public static List<String> macCodesign(Path target, String signingIdentity) {
        require(signingIdentity, "signingIdentity");
        return List.of("codesign", "--force", "--options", "runtime", "--timestamp",
                "--sign", signingIdentity, target.toString());
    }

    /** Submits an artifact to Apple notarization and waits for the result. */
    public static List<String> macNotarize(Path artifact, String keychainProfile) {
        require(keychainProfile, "keychainProfile");
        return List.of("xcrun", "notarytool", "submit", artifact.toString(),
                "--keychain-profile", keychainProfile, "--wait");
    }

    /** Staples the notarization ticket into the artifact for offline Gatekeeper checks. */
    public static List<String> macStaple(Path artifact) {
        return List.of("xcrun", "stapler", "staple", artifact.toString());
    }

    /** Validates that a stapled ticket is present and accepted. */
    public static List<String> macStapleValidate(Path artifact) {
        return List.of("xcrun", "stapler", "validate", artifact.toString());
    }

    /**
     * Windows Authenticode via {@code signtool}, SHA-256 with an RFC-3161 timestamp. The
     * certificate reference selects the cert (e.g. a subject-name substring in the user store);
     * Azure Trusted Signing plugs in through the same {@code signtool} with a dlib, unchanged here.
     */
    public static List<String> windowsSigntool(Path artifact, String certificateSubject,
            String timestampUrl) {
        require(certificateSubject, "certificateSubject");
        require(timestampUrl, "timestampUrl");
        List<String> command = new ArrayList<>(List.of("signtool", "sign", "/fd", "SHA256",
                "/tr", timestampUrl, "/td", "SHA256"));
        String compact = certificateSubject.replaceAll("\\s+", "");
        if (compact.matches("(?i)[0-9a-f]{40}")) {
            command.add("/sha1");
            command.add(compact);
        } else {
            command.add("/n");
            command.add(certificateSubject);
        }
        command.add(artifact.toString());
        return List.copyOf(command);
    }

    /** Authenticode policy verification after signing. */
    public static List<String> windowsVerify(Path artifact) {
        return List.of("signtool", "verify", "/pa", "/all", "/v", artifact.toString());
    }

    /** Detached GPG signature (`.asc`) for a Linux artifact under the given key id. */
    public static List<String> linuxGpgDetachSign(Path artifact, String keyId) {
        require(keyId, "keyId");
        return List.of("gpg", "--yes", "--armor", "--detach-sign",
                "--local-user", keyId, artifact.toString());
    }

    /** Headless variant: the caller must provide the passphrase on stdin. */
    public static List<String> linuxGpgDetachSignHeadless(Path artifact, String keyId) {
        require(keyId, "keyId");
        return List.of("gpg", "--batch", "--yes", "--pinentry-mode", "loopback",
                "--passphrase-fd", "0", "--armor", "--detach-sign", "--local-user", keyId,
                artifact.toString());
    }

    /** Verifies the conventional armored detached signature emitted beside an artifact. */
    public static List<String> linuxGpgVerify(Path artifact) {
        return List.of("gpg", "--batch", "--verify", artifact + ".asc", artifact.toString());
    }

    private static void require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Signing requires " + what);
        }
    }
}
