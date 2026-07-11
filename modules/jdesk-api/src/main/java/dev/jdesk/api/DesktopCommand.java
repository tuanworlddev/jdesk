package dev.jdesk.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an IPC command. Discovered at compile time by the JDesk annotation
 * processor; never by runtime classpath scanning. The value is the wire name, e.g.
 * {@code "greeting.greet"}: 1..128 chars, dot-separated lowerCamel segments.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface DesktopCommand {
    String value();
}
