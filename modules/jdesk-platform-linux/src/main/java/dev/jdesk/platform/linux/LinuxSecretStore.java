package dev.jdesk.platform.linux;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.SecretStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link SecretStore} backed by the freedesktop Secret Service through the
 * {@code secret-tool} CLI (libsecret). The secret value travels over stdin/stdout —
 * never through argv, which other users could read from the process list. When
 * {@code secret-tool} is not installed this store fails loudly; JDesk never falls back
 * to plaintext files for secrets.
 */
final class LinuxSecretStore implements SecretStore {
    private final String service;

    LinuxSecretStore(String applicationId) {
        this.service = "jdesk:" + applicationId;
    }

    @Override
    public Optional<String> get(String key) {
        validateKey(key);
        ProcessResult result = run(List.of("secret-tool", "lookup",
                "service", service, "key", key), null);
        if (result.exitCode() == 1) {
            return Optional.empty(); // not found
        }
        if (result.exitCode() != 0) {
            throw failure("lookup", result.exitCode());
        }
        return Optional.of(result.stdout());
    }

    @Override
    public void put(String key, String value) {
        validateKey(key);
        if (value == null) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Secret value must not be null");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new JDeskException(ErrorCode.PAYLOAD_TOO_LARGE, "Secret value exceeds 64 KiB");
        }
        ProcessResult result = run(List.of("secret-tool", "store",
                "--label", service + "/" + key, "service", service, "key", key), value);
        if (result.exitCode() != 0) {
            throw failure("store", result.exitCode());
        }
    }

    @Override
    public void delete(String key) {
        validateKey(key);
        ProcessResult result = run(List.of("secret-tool", "clear",
                "service", service, "key", key), null);
        // Exit 1 covers "nothing matched"; deleting an absent key is a no-op.
        if (result.exitCode() != 0 && result.exitCode() != 1) {
            throw failure("clear", result.exitCode());
        }
    }

    private record ProcessResult(int exitCode, String stdout) {
    }

    private static ProcessResult run(List<String> command, String stdin) {
        try {
            // Discard stderr so a chatty child can never deadlock against a full pipe;
            // read stdout on its own thread so the timeout below stays effective even
            // when secret-tool blocks (e.g. waiting on a keyring unlock prompt).
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (stdin != null) {
                try (OutputStream out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            java.util.concurrent.CompletableFuture<String> stdout =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return new String(process.getInputStream().readAllBytes(),
                                    StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            return "";
                        }
                    }, runnable -> Thread.ofVirtual().start(runnable));
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new JDeskException(ErrorCode.TIMEOUT, "secret-tool did not answer");
            }
            return new ProcessResult(process.exitValue(),
                    stdout.get(5, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException e) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR, "secret-tool output unreadable", e);
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Secret storage requires secret-tool (libsecret); install e.g."
                            + " libsecret-tools. JDesk does not fall back to plaintext.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JDeskException(ErrorCode.CANCELLED, "Secret operation interrupted", e);
        }
    }

    private static JDeskException failure(String operation, int exitCode) {
        return new JDeskException(ErrorCode.INTERNAL_ERROR,
                "secret-tool " + operation + " failed (exit " + exitCode + ")");
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Secret key must be 1..128 non-blank characters");
        }
    }
}
