# Release and support policy

JDesk is pre-1.0. Until 1.0, only the latest minor line receives fixes and any release may
contain source-incompatible changes documented in its migration notes.

Before 1.0:

- every change to dev.jdesk.api must update the reviewed API baseline intentionally;
- releases follow Semantic Versioning and use one immutable version tag;
- the exact tag commit must pass unit, plugin, native, security and package gates on the
  three primary platforms;
- release artifacts include checksums, CycloneDX inventory and, when the repository
  enables it, OIDC build provenance;
- security issues follow [SECURITY.md](../../SECURITY.md).

The first 1.0 release must declare an LTS duration, supported OS, WebView and JDK matrix,
deprecation window and end-of-life policy. No contractual SLA exists in the pre-1.0 phase.
