# JDesk Documentation

JDesk is a desktop application framework: **Java 25 core + web frontend + system
WebView** — the Tauri model without Rust. Start with the
[top-level README](../README.md) for honest status and the
[implementation status](../IMPLEMENTATION_STATUS.md) /
[verification matrix](../VERIFICATION.md) for what is actually verified.

## Architecture

- [Overview](architecture/overview.md) — module map, request/response data flow, provider selection, architecture principles.
- [IPC protocol](architecture/ipc-protocol.md) — the wire format and compatibility policy.
- ADRs: [001 Java 25 / JPMS / FFM](architecture/ADR-001-java25-jpms-ffm.md) ·
  [002 Gradle-first](architecture/ADR-002-gradle-first.md) ·
  [003 System WebViews](architecture/ADR-003-system-webviews.md) ·
  [004 No production localhost](architecture/ADR-004-no-localhost-production.md) ·
  [005 Compile-time registration](architecture/ADR-005-compile-time-registration.md) ·
  [006 Async message passing](architecture/ADR-006-async-message-passing.md) ·
  [007 JVM distribution first](architecture/ADR-007-jvm-distribution-first.md)

## Development

- [Quick start](development/quick-start.md) — get an app running end to end.
- [Project structure](development/project-structure.md) — application layout and templates.
- [Gradle plugin reference](development/gradle-plugin-reference.md) — `dev.jdesk.application` extension and every task.
- [Troubleshooting](development/troubleshooting.md) — common problems and fixes.
- [Contributing](development/contributing.md) — add a platform adapter or a command/plugin.
- [Quality gates](development/quality.md) — tests, coverage, boundaries.

## Platform

- [Prerequisites and limitations](platform/prerequisites.md) — per-OS runtime + build requirements.

## Security

- [Threat model and capability guide](security/threat-model.md).

## Verification

- [Native testing and evidence](verification/native-testing-and-evidence.md) — the anti-fake evidence system and probe suites.

## Packaging

- [Packaging and signing](packaging/packaging-and-signing.md) — jlink/jpackage, signing hooks, SBOM/checksums.

## Client SDK

- [`@jdesk/client`](../js/jdesk-client/README.md) — the zero-dependency TypeScript IPC runtime.
