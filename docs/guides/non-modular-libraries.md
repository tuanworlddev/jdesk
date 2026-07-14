# Using non-modular libraries

JDesk apps are JPMS modules, but many useful Java libraries still ship without a `module-info`
(they are *automatic modules*). If your `:app` module `requires` such a library directly, the build
fails — `error: module not found: org.eclipse.lsp4j` — because Gradle puts a project's
automatic-module dependency on the classpath, not the module path, and it also does **not** package:
`jlink`/`jpackage` only accept explicit modules, so the runtime image can't include an automatic
module either. The fix is the same for both problems.

## Give the library a real module descriptor

Use the [`extra-java-module-info`](https://github.com/gradlex-org/extra-java-module-info) plugin to
synthesize a `module-info` for the dependency at build time — turning the automatic module into a
proper named module that resolves on the module path **and** links into the `jpackage` image:

```kotlin
plugins {
    id("dev.jdesk.application")
    id("org.gradlex.extra-java-module-info") version "1.+"
}

extraJavaModuleInfo {
    failOnMissingModuleInfo = false            // leave already-modular deps untouched
    module("org.eclipse.lsp4j:org.eclipse.lsp4j", "org.eclipse.lsp4j") {
        exportAllPackages()
        requires("java.logging")               // LSP4J uses java.util.logging
        // requiresTransitive("org.eclipse.lsp4j.jsonrpc")  // for its transitive deps
    }
}
```

Then `requires org.eclipse.lsp4j;` in your `module-info.java` resolves in `./gradlew run` **and** in
the packaged app, with no automatic-module warnings.

## When a dependency won't cooperate

Some libraries fight modularization (split packages, reflection, native `.so` loading from the jar).
If synthesizing a descriptor is impractical, run the tool as a **separate process** and talk to it
over a pipe/stdio or loopback — the same pattern a language server or a Node-based sidecar uses.
That keeps the JPMS runtime image clean and sidesteps the packaging constraint entirely; ship the
sidecar binary alongside the app image.

## Rule of thumb

- **Modular dependency** → just `requires` it.
- **Automatic module you can describe** → `extra-java-module-info`; works in dev and in the package.
- **Stubborn / non-Java tool** → run it out-of-process as a sidecar.
