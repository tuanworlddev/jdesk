package dev.jdesk.runtime.automation;

import dev.jdesk.api.WindowId;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Narrow host surface exposed to an optional automation provider. */
public interface AutomationHost {
    Set<WindowId> openWindowIds();

    CompletionStage<String> evaluate(WindowId windowId, String script);

    CompletionStage<byte[]> snapshotPng(WindowId windowId);
}
