module dev.jdesk.examples.notes {
    requires dev.jdesk.api;
    requires dev.jdesk.runtime;
    requires static com.fasterxml.jackson.databind;

    opens dev.jdesk.examples.notes to com.fasterxml.jackson.databind;
}
