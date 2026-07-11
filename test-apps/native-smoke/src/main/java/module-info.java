module dev.jdesk.testapps.nativesmoke {
    requires dev.jdesk.api;
    requires dev.jdesk.runtime;
    requires dev.jdesk.webview.spi;
    requires dev.jdesk.testkit;
    requires static com.fasterxml.jackson.databind;

    uses dev.jdesk.webview.spi.PlatformProvider;

    opens dev.jdesk.testapps.nativesmoke to com.fasterxml.jackson.databind;
}
