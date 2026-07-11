module dev.jdesk.examples.hello {
    requires dev.jdesk.api;
    requires dev.jdesk.runtime;
    requires static com.fasterxml.jackson.databind;

    opens dev.jdesk.examples.hello to com.fasterxml.jackson.databind;
}
