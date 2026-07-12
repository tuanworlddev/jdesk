package dev.jdesk.automation;

import dev.jdesk.runtime.automation.AutomationHost;
import dev.jdesk.runtime.automation.AutomationProvider;
import dev.jdesk.runtime.automation.AutomationSession;

/** Token-gated loopback HTTP automation provider. */
public final class HttpAutomationProvider implements AutomationProvider {
    public HttpAutomationProvider() {
    }

    @Override
    public AutomationSession start(AutomationHost host, String applicationId) throws Exception {
        return new AutomationServer(host, applicationId);
    }
}
