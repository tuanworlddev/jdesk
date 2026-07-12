package dev.jdesk.runtime.automation;

import dev.jdesk.api.WindowId;

/** Running optional automation transport and its console sink. */
public interface AutomationSession extends AutoCloseable {
    void recordConsole(WindowId windowId, String level, String message);

    @Override
    void close();
}
