/** Annotation processor for compile-time command registration and TypeScript generation. */
module dev.jdesk.codegen {
    requires java.compiler;
    requires dev.jdesk.api;

    provides javax.annotation.processing.Processor
            with dev.jdesk.codegen.DesktopCommandProcessor;
}
