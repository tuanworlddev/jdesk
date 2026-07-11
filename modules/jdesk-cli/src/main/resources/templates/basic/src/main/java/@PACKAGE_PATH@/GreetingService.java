package @PACKAGE@;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class GreetingService {
    public record Request(String name) {
    }

    public record Response(String message) {
    }

    @DesktopCommand("greeting.greet")
    @RequiresCapability("greeting:use")
    public CompletionStage<Response> greet(Request request, InvocationContext context) {
        String name = request.name() == null || request.name().isBlank()
                ? "world" : request.name().strip();
        return CompletableFuture.completedFuture(new Response("Hello, " + name + "!"));
    }
}
