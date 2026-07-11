package dev.jdesk.codegen;

import java.util.List;
import java.util.Optional;

/**
 * Validated model of one {@code @DesktopCommand} method, carrying everything the Java and
 * TypeScript emitters need.
 *
 * @param name wire name, e.g. {@code greeting.greet}
 * @param capability required capability; empty for {@code @PublicDesktopCommand} commands
 * @param shape parameter shape of the handler method
 * @param methodName simple name of the annotated method
 * @param requestCanonicalName canonical name of the request DTO; {@code java.lang.Void}
 *        when the command takes no payload
 * @param tsRequestType TypeScript type of the request; {@code null} when there is no payload
 * @param tsResponseType TypeScript type of the response ({@code void} for {@code Void})
 * @param tsReferencedRecords sorted simple names of record interfaces referenced by the
 *        request/response types (for the {@code commands.ts} import line)
 */
record CommandModel(
        String name,
        Optional<String> capability,
        Shape shape,
        String methodName,
        String requestCanonicalName,
        String tsRequestType,
        String tsResponseType,
        List<String> tsReferencedRecords) {

    enum Shape {
        REQUEST_AND_CONTEXT,
        CONTEXT_ONLY,
        NO_ARGS
    }
}
