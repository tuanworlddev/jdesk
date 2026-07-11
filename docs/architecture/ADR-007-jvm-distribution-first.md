# ADR-007: JVM distribution first

Status: accepted (spec section 3, ADR-007)
Date: 2026-07-11

## Decision

The default v1 release is a trimmed `jlink` runtime image packaged with `jpackage`
(EXE/MSI, .app/DMG, DEB). Packages are always built on their target OS —
cross-packaging claims are forbidden. GraalVM Native Image is a possible later
optional profile and must not delay or compromise the JVM release.
