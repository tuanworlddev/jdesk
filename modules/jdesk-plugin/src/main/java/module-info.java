/** Signed, integrity-checked, capability-gated third-party plugin loading (deny-by-default). */
module dev.jdesk.plugin {
    requires com.fasterxml.jackson.databind;

    exports dev.jdesk.plugin;
    opens dev.jdesk.plugin to com.fasterxml.jackson.databind;
}
