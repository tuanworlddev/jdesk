package dev.jdesk.runtime.ipc;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;

/** Envelope-level protocol violation with a deterministic public error code. */
public final class ProtocolException extends JDeskException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(ErrorCode code, String publicMessage) {
        super(code, publicMessage);
    }
}
