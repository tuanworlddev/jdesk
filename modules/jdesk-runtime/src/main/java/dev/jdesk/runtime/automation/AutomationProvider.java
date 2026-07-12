package dev.jdesk.runtime.automation;

/** Service provider for optional development automation transports. */
public interface AutomationProvider {
    AutomationSession start(AutomationHost host, String applicationId) throws Exception;
}
