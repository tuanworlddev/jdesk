package dev.jdesk.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** Development-loop settings for {@code jdeskDev}. */
public interface JDeskDevelopmentExtension {

    /** Recompile and restart the Java application when Java or resource files change. */
    Property<Boolean> getJavaReload();

    /** Optional command used to rebuild application classes before a restart. */
    ListProperty<String> getReloadCommand();

    /** Quiet period after a source change before rebuilding. */
    Property<Integer> getReloadDebounceMillis();

    /** Additional source/resource roots that should trigger the rebuild command. */
    ConfigurableFileCollection getReloadSources();
}
