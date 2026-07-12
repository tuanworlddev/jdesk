# Security Policy

## Supported versions

JDesk is pre-1.0. Security fixes are applied to the latest published minor release and
the default development branch. An LTS window will be declared before the first 1.0
release; no older pre-1.0 line should be assumed supported.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub Security Advisories
for this repository and include:

- affected JDesk version and operating system;
- a minimal reproducer or malformed input;
- expected impact and required privileges;
- whether the issue is already public.

The project targets acknowledgement within two business days, initial triage within five
business days, and a remediation plan within ten business days. These are engineering
targets, not a contractual SLA. Coordinated disclosure timing is agreed with the reporter.

## Scope

The IPC/capability boundary, custom asset protocol, updater, single-instance transport,
packaging pipeline, native FFM callbacks, and bundled automation endpoint are in scope.
A compromised operating system, JVM, or vendor WebView binary is outside the framework
threat model, but dependency vulnerabilities in those components should still be reported.

Security releases include an advisory, affected/fixed versions, upgrade guidance, and
updated SBOM/checksums. Credentials, tokens, private paths, and unredacted customer data
must never be attached to an issue or evidence bundle.
