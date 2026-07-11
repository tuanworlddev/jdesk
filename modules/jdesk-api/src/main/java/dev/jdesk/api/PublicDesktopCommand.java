package dev.jdesk.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit opt-out of the capability requirement for a command that is safe to expose to
 * any window of this application. The annotation processor rejects commands that have
 * neither {@link RequiresCapability} nor this annotation (deny by default).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface PublicDesktopCommand {
}
