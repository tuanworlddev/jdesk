package dev.jdesk.codegen;

/** Shared source fixtures. The golden files under src/test/resources/golden match these. */
final class Fixtures {

    private Fixtures() {
    }

    static final String GREETING_SERVICE_NAME = "com.example.app.GreetingService";

    static final String GREETING_SERVICE = """
            package com.example.app;

            import dev.jdesk.api.DesktopCommand;
            import dev.jdesk.api.InvocationContext;
            import dev.jdesk.api.PublicDesktopCommand;
            import dev.jdesk.api.RequiresCapability;
            import java.util.concurrent.CompletionStage;

            public class GreetingService {

                public record GreetRequest(String name) {
                }

                public record GreetResponse(String message) {
                }

                @DesktopCommand("greeting.greet")
                @RequiresCapability("greeting.read")
                public CompletionStage<GreetResponse> greet(GreetRequest request, InvocationContext context) {
                    return null;
                }

                @DesktopCommand("greeting.ping")
                @PublicDesktopCommand
                public CompletionStage<String> ping(InvocationContext context) {
                    return null;
                }
            }
            """;

    static final String CATALOG_SERVICE_NAME = "com.example.app.CatalogService";

    static final String CATALOG_SERVICE = """
            package com.example.app;

            import dev.jdesk.api.DesktopCommand;
            import dev.jdesk.api.InvocationContext;
            import dev.jdesk.api.RequiresCapability;
            import java.util.List;
            import java.util.Map;
            import java.util.Optional;
            import java.util.concurrent.CompletionStage;

            public class CatalogService {

                public record Money(long amount, String currency) {
                }

                public record Item(String id, Money price, Optional<String> note, List<String> tags) {
                }

                public record Query(Map<String, List<Integer>> filters, Optional<Item> sample) {
                }

                public record Page(List<Item> items, int total, boolean hasMore) {
                }

                @DesktopCommand("catalog.search")
                @RequiresCapability("catalog.read")
                public CompletionStage<Page> search(Query query, InvocationContext context) {
                    return null;
                }
            }
            """;

    /** Wraps command method declarations in a minimal public service class. */
    static String service(String body) {
        return """
                package com.example.app;

                import dev.jdesk.api.DesktopCommand;
                import dev.jdesk.api.InvocationContext;
                import dev.jdesk.api.PublicDesktopCommand;
                import dev.jdesk.api.RequiresCapability;
                import java.util.concurrent.CompletionStage;

                public class TestService {
                """ + body + "\n}\n";
    }
}
