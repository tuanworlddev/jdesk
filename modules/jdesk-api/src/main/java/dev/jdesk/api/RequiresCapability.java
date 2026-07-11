package dev.jdesk.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Capability required to invoke the annotated command. Evaluated before deserialization. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface RequiresCapability {
    String value();
}
