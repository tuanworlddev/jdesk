package dev.jdesk.examples.hello;

import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.runtime.capability.Capabilities;

/**
 * hello-vanilla: the smallest real JDesk application, built exclusively on public APIs.
 *
 * <ul>
 *   <li>Commands: {@link GreetingService} registered through the generated
 *       {@code GreetingServiceCommands} (compile-time, no reflection scanning);</li>
 *   <li>Capabilities: deny-by-default, {@code jdesk-capabilities.json} grants
 *       {@code greeting:use} to the main window only;</li>
 *   <li>Assets: plain HTML/JS/CSS served from {@code jdesk://app/} (the {@code run}
 *       task points {@code jdesk.assets.dir} at {@code src/main/resources/web});</li>
 *   <li>Dev mode: the Gradle plugin's {@code jdeskDev} passes
 *       {@code -Djdesk.dev=true -Djdesk.devUrl=...}, honored below.</li>
 * </ul>
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        GreetingService greetings = new GreetingService();
        JDeskApplication.Builder builder = JDeskApplication.builder()
                .id("dev.jdesk.examples.hello")
                .commands(GreetingServiceCommands.create(greetings))
                .capabilities(Capabilities.fromResource("jdesk-capabilities.json"))
                .window(WindowConfig.builder()
                        .id("main")
                        .title("JDesk — hello-vanilla")
                        .size(900, 640)
                        .entry("jdesk://app/index.html")
                        .build());
        String devUrl = System.getProperty("jdesk.devUrl");
        if (Boolean.getBoolean("jdesk.dev") && devUrl != null) {
            builder.devServerUrl(devUrl);
        }
        System.exit(builder.run(args));
    }
}
