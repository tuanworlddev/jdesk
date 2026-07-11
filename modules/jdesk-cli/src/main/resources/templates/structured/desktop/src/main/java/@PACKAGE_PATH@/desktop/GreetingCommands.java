package @PACKAGE@.desktop;

import @PACKAGE@.application.GreetingUseCase;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class GreetingCommands {
    private final GreetingUseCase greetings;

    public GreetingCommands(GreetingUseCase greetings) {
        this.greetings = greetings;
    }

    public record Request(String name) {
    }

    public record Response(String message) {
    }

    @DesktopCommand("greeting.greet")
    @RequiresCapability("greeting:use")
    public CompletionStage<Response> greet(Request request, InvocationContext context) {
        return CompletableFuture.completedFuture(
                new Response(greetings.greet(request.name()).message()));
    }
}
