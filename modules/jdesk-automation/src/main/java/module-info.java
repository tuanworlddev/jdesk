/** Optional E2E automation transport; exclude this module from production images. */
module dev.jdesk.automation {
    requires dev.jdesk.runtime;
    requires jdk.httpserver;
    requires com.fasterxml.jackson.databind;

    opens dev.jdesk.automation to com.fasterxml.jackson.databind;

    provides dev.jdesk.runtime.automation.AutomationProvider
            with dev.jdesk.automation.HttpAutomationProvider;
}
