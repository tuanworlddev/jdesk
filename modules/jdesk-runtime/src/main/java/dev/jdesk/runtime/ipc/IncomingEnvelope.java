package dev.jdesk.runtime.ipc;

import java.util.Optional;

/** Parsed, validated incoming envelope. Payload stays raw JSON until capabilities pass. */
public sealed interface IncomingEnvelope {
    int version();

    String nonce();

    record Hello(int version, String client, String clientVersion, String nonce)
            implements IncomingEnvelope {
    }

    record Invoke(int version, String id, String command, Optional<String> payloadJson, String nonce)
            implements IncomingEnvelope {
    }

    record Cancel(int version, String id, String nonce) implements IncomingEnvelope {
    }
}
