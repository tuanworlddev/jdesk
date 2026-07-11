module dev.jdesk.testapps.securityprobe {
    requires dev.jdesk.api;
    requires dev.jdesk.runtime;
    requires dev.jdesk.webview.spi;
    requires dev.jdesk.testkit;
    requires static com.fasterxml.jackson.databind;

    uses dev.jdesk.webview.spi.PlatformProvider;

    opens dev.jdesk.testapps.securityprobe to com.fasterxml.jackson.databind;
}
