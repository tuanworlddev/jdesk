package dev.jdesk.updater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateManagerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMPTY_SIGNATURE =
            Base64.getEncoder().encodeToString(new byte[64]);

    @TempDir Path temp;
    private KeyPair keys;
    private HttpServer server;
    private byte[] packageBytes;
    private byte[] manifestBytes;

    @BeforeEach void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        packageBytes = "version-1.1.0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/manifest", exchange -> respond(exchange, manifestBytes));
        server.createContext("/package", exchange -> respond(exchange, packageBytes));
        server.start();
        manifestBytes = signedManifest("1.1.0", "stable", packageBytes, keys);
    }

    @AfterEach void tearDown() {
        server.stop(0);
    }

    @Test void downloadsVerifiesStagesAndRollsBackAnUnhealthyVersion() throws Exception {
        UpdateTransaction transaction = transactionWithHealthyBaseline();
        UpdateManager manager = manager(transaction, UpdateChannel.STABLE, false, 1024);

        UpdateResult result = manager.checkAndStage(uri("/manifest"), "1.0.0");

        assertThat(result.status()).isEqualTo(UpdateResult.Status.STAGED);
        assertThat(transaction.currentVersion()).isEqualTo("1.1.0");
        assertThat(transaction.isCurrentPending()).isTrue();
        UpdateLaunch candidate = transaction.prepareLaunch();
        assertThat(candidate.version()).isEqualTo("1.1.0");
        assertThat(candidate.packagePath()).exists();
        assertThat(transaction.prepareLaunch().rolledBack()).isTrue();
        try (var downloads = Files.list(temp.resolve("downloads"))) {
            assertThat(downloads).isEmpty();
        }
    }

    @Test void enforcesChannelDowngradeTransportAndSizePolicy() throws Exception {
        UpdateTransaction transaction = transactionWithHealthyBaseline();
        assertThat(manager(transaction, UpdateChannel.BETA, false, 1024)
                .checkAndStage(uri("/manifest"), "1.0.0").status())
                .isEqualTo(UpdateResult.Status.CHANNEL_MISMATCH);
        assertThat(manager(transaction, UpdateChannel.STABLE, false, 1024)
                .checkAndStage(uri("/manifest"), "2.0.0").status())
                .isEqualTo(UpdateResult.Status.NO_UPDATE);
        assertThatThrownBy(() -> manager(transaction, UpdateChannel.STABLE, false, 4)
                .checkAndStage(uri("/manifest"), "1.0.0"))
                .hasMessage("Update package exceeds policy size");

        UpdatePolicy secureOnly = new UpdatePolicy(true, UpdateChannel.STABLE, false,
                1024, 8192, Duration.ofSeconds(2), Duration.ofSeconds(5), false);
        UpdateManager secure = new UpdateManager(transaction, secureOnly, keys.getPublic(),
                temp.resolve("secure-downloads"));
        assertThatThrownBy(() -> secure.checkAndStage(uri("/manifest"), "1.0.0"))
                .hasMessage("Update transport must use HTTPS");
    }

    @Test void phasedRolloutHoldsBackUntilReachedThenStages() throws Exception {
        UpdateTransaction transaction = transactionWithHealthyBaseline();
        UpdatePolicy policy = new UpdatePolicy(true, UpdateChannel.STABLE, false, 1024, 8192,
                Duration.ofSeconds(2), Duration.ofSeconds(5), true);

        // rolloutPercentage 0 => no install is in reach yet, even a brand-new release.
        manifestBytes = signedManifest("1.1.0", "stable", packageBytes, keys, 0);
        UpdateManager gated = new UpdateManager(transaction, policy, keys.getPublic(),
                keys.getPublic(), temp.resolve("dl-held"), "install-abc");
        assertThat(gated.checkAndStage(uri("/manifest"), "1.0.0").status())
                .isEqualTo(UpdateResult.Status.HELD_BACK);

        // Same 0% manifest, but no install id configured => rollout gating is disabled entirely.
        UpdateManager ungated = new UpdateManager(transaction, policy, keys.getPublic(),
                keys.getPublic(), temp.resolve("dl-full"));
        assertThat(ungated.checkAndStage(uri("/manifest"), "1.0.0").status())
                .isEqualTo(UpdateResult.Status.STAGED);

        // Rollout reaches 100% => the gated install stages too.
        manifestBytes = signedManifest("1.1.0", "stable", packageBytes, keys, 100);
        UpdateManager reached = new UpdateManager(transactionWithHealthyBaseline(), policy,
                keys.getPublic(), keys.getPublic(), temp.resolve("dl-reached"), "install-abc");
        assertThat(reached.checkAndStage(uri("/manifest"), "1.0.0").status())
                .isEqualTo(UpdateResult.Status.STAGED);
    }

    @Test void rejectsTamperedManifestBeforeDownloadingPackage() throws Exception {
        manifestBytes[manifestBytes.length / 2] ^= 1;
        UpdateManager manager = manager(transactionWithHealthyBaseline(),
                UpdateChannel.STABLE, false, 1024);
        assertThatThrownBy(() -> manager.checkAndStage(uri("/manifest"), "1.0.0"))
                .isInstanceOf(UpdateVerificationException.class);
    }

    private UpdateTransaction transactionWithHealthyBaseline() throws Exception {
        UpdateTransaction transaction = new UpdateTransaction(temp.resolve("install"));
        Path baseline = temp.resolve("baseline.pkg");
        Files.writeString(baseline, "version-1.0.0");
        transaction.stageAndActivate(verified(baseline), "1.0.0");
        transaction.prepareLaunch();
        transaction.confirmHealthy("1.0.0");
        return transaction;
    }

    private UpdateManager manager(UpdateTransaction transaction, UpdateChannel channel,
            boolean downgrade, long maxBytes) throws Exception {
        UpdatePolicy policy = new UpdatePolicy(true, channel, downgrade, maxBytes, 8192,
                Duration.ofSeconds(2), Duration.ofSeconds(5), true);
        return new UpdateManager(transaction, policy, keys.getPublic(),
                temp.resolve("downloads"));
    }

    private byte[] signedManifest(String version, String channel, byte[] bytes, KeyPair pair)
            throws Exception {
        return signedManifest(version, channel, bytes, pair, 100);
    }

    private byte[] signedManifest(String version, String channel, byte[] bytes, KeyPair pair,
            Integer rolloutPercentage) throws Exception {
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        String packageSignature = sign(bytes, pair);
        UpdateManifest unsigned = new UpdateManifest(1, version, channel,
                uri("/package").toString(), bytes.length, hash, packageSignature,
                Instant.now().getEpochSecond(), "1.0.0", rolloutPercentage, EMPTY_SIGNATURE);
        UpdateManifest signed = new UpdateManifest(1, version, channel,
                unsigned.packageUri(), bytes.length, hash, packageSignature,
                unsigned.publishedAtEpochSeconds(), "1.0.0", rolloutPercentage,
                sign(unsigned.signingPayload(), pair));
        return MAPPER.writeValueAsBytes(signed);
    }

    private VerifiedUpdate verified(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        return SignedUpdateVerifier.verify(file, 1024, hash, sign(bytes, keys),
                keys.getPublic());
    }

    private static String sign(byte[] bytes, KeyPair pair) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(bytes);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(HttpExchange exchange, byte[] body) throws java.io.IOException {
        if (body == null) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
