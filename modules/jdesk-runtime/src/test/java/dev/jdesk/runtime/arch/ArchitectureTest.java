package dev.jdesk.runtime.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rules (spec sections 17.2 and ADR-003/ADR-005): the pure-Java core must
 * never grow platform, UI-toolkit, or hidden serialization dependencies. Analyzes the
 * compiled production classes of dev.jdesk.api, dev.jdesk.webview.spi, and
 * dev.jdesk.runtime; test classes are excluded.
 */
@AnalyzeClasses(packages = "dev.jdesk", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The public API module is dependency-free: only the JDK and itself. */
    @ArchTest
    static final ArchRule apiDependsOnlyOnJdkAndItself =
            classes().that().resideInAPackage("dev.jdesk.api..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", "dev.jdesk.api..")
                    .because("dev.jdesk.api is the zero-dependency public API (spec section 5)");

    /** No desktop UI toolkits or JDK-internal APIs in the runtime core. */
    @ArchTest
    static final ArchRule runtimeUsesNoUiToolkitsOrJdkInternals =
            noClasses().that().resideInAPackage("dev.jdesk.runtime..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("java.awt..", "javax.swing..", "javafx..", "sun.misc..")
                    .because("the runtime renders through system WebViews only (ADR-003)");

    /** The runtime core never references platform adapters or FFM bindings. */
    @ArchTest
    static final ArchRule runtimeDoesNotDependOnPlatformOrFfm =
            noClasses().that().resideInAPackage("dev.jdesk.runtime..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("dev.jdesk.platform..", "dev.jdesk.ffm..", "dev.jdesk.native..")
                    .because("jdesk-runtime contains no native classes; adapters depend on it, "
                            + "never the reverse (spec section 4)");

    /** The dependency-free surfaces must not know Jackson exists. */
    @ArchTest
    static final ArchRule apiAndSpiAreJacksonFree =
            noClasses().that()
                    .resideInAnyPackage("dev.jdesk.api..", "dev.jdesk.webview.spi..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fasterxml.jackson..")
                    .because("serialization is a runtime implementation detail (spec section 11)");

    /** Jackson usage in the runtime is confined to serialization-owning packages. */
    @ArchTest
    static final ArchRule jacksonConfinedToSerializationPackages =
            noClasses().that().resideInAPackage("dev.jdesk.runtime..")
                    .and().resideOutsideOfPackages(
                            "dev.jdesk.runtime.internal..",
                            "dev.jdesk.runtime.json..",
                            "dev.jdesk.runtime.config..",
                            "dev.jdesk.runtime.ipc..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fasterxml.jackson..")
                    .because("only the JSON codec, defensive parsing, configuration and IPC "
                            + "internals may touch Jackson directly");
}
