module @APP_ID@.desktop {
    requires @APP_ID@.application;
    requires @APP_ID@.infrastructure;
    requires dev.jdesk.api;
    requires dev.jdesk.runtime;
    requires static com.fasterxml.jackson.databind;

    opens @PACKAGE@.desktop to com.fasterxml.jackson.databind;
}
