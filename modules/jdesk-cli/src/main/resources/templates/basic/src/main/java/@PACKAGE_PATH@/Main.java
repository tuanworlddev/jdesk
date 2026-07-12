package @PACKAGE@;

import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.runtime.config.Capabilities;
import java.util.Arrays;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        boolean smokeTest = Arrays.asList(args).contains("--jdesk-smoke");
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
                        .build())
                .lifecycle(new LifecycleListener() {
                    @Override
                    public void onReady(ApplicationHandle application) {
                        if (smokeTest) {
                            application.requestStop();
                        }
                    }
                });
        String devUrl = System.getProperty("jdesk.devUrl");
        if (Boolean.getBoolean("jdesk.dev") && devUrl != null) {
            app.devServerUrl(devUrl);
        }
        System.exit(app.run(args));
    }
}
