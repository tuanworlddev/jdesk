module dev.jdesk.updater {
    requires java.net.http;
    requires com.fasterxml.jackson.databind;

    exports dev.jdesk.updater;
    opens dev.jdesk.updater to com.fasterxml.jackson.databind;
}
