# ADR-001: Java 25, JPMS, and FFM

Status: accepted (spec section 3, ADR-001)
Date: 2026-07-11

## Decision

- Java 25 toolchains via Gradle `java.toolchain`; wrapper checked in.
- Every framework production module has `module-info.java`.
- Native access is granted only to platform modules and `dev.jdesk.ffm` via
  `--enable-native-access=<module>`; `ALL-UNNAMED` is forbidden in production images.
- All native interop uses `java.lang.foreign` (JEP 454): `SymbolLookup`, layouts,
  downcalls, upcall stubs, arena-scoped memory. `sun.misc.Unsafe` is forbidden.
- No JNI glue authored by this project, no JNA, no Rust.

## Naming note

The spec names the module directory `jdesk-native-ffm`. `native` is a reserved Java
keyword, so the JPMS module and package are `dev.jdesk.ffm`. The Gradle project path
stays `:modules:jdesk-native-ffm` as specified.

## Consequences

- Deterministic native memory lifetime is enforced by the arena-ownership rules in
  spec section 6 and tested per platform.
- jpackage launchers embed the exact per-platform `--enable-native-access` option and
  `--illegal-native-access=deny`; the shared jlink image carries no global privilege.
