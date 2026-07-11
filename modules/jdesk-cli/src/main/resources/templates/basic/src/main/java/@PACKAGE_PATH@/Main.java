package @PACKAGE@;

import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.runtime.config.Capabilities;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        GreetingService service = new GreetingService();
        JDeskApplication.Builder app = JDeskApplication.builder()
                .id("@APP_ID@")
                .commands(GreetingServiceCommands.create(service))
                .capabilities(Capabilities.fromResource("jdesk-capabilities.json"))
                .window(WindowConfig.builder()
                        .id("main")
                        .title("@PROJECT_NAME@")
                        .size(960, 680)
                        .entry("jdesk://app/index.html")
                        .build());
        String devUrl = System.getProperty("jdesk.devUrl");
        if (Boolean.getBoolean("jdesk.dev") && devUrl != null) {
            app.devServerUrl(devUrl);
        }
        System.exit(app.run(args));
    }
}
