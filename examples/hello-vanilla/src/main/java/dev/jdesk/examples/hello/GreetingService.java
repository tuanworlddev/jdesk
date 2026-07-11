package dev.jdesk.examples.hello;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The example's single feature service. The jdesk-codegen annotation processor turns
 * this into {@code GreetingServiceCommands} (compile-time registration, ADR-005) plus
 * TypeScript interfaces for TS frontends.
 */
public class GreetingService {

    /** Request payload sent from the web page. */
    public record GreetRequest(String name) {
    }

    /** Response rendered by the web page. */
    public record GreetResponse(String message, String javaVersion, String threadName) {
    }

    @DesktopCommand("greeting.greet")
    @RequiresCapability("greeting:use")
    public CompletionStage<GreetResponse> greet(GreetRequest request, InvocationContext context) {
        String name = request.name() == null || request.name().isBlank()
                ? "world"
                : request.name().strip();
        return CompletableFuture.completedFuture(new GreetResponse(
                "Hello, " + name + "! Greetings from Java.",
                Runtime.version().toString(),
                Thread.currentThread().getName()));
    }
}
