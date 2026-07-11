# ADR-005: Compile-time registration, minimal reflection

Status: accepted (spec section 3, ADR-005)
Date: 2026-07-11

## Decision

Commands are discovered by a Java annotation processor (`jdesk-codegen`) which emits:
a Java command registry, command metadata, JSON-schema-like structural metadata,
TypeScript request/response types, and a typed TypeScript client.

- No runtime classpath scanning.
- Java records are the default DTO form.
- Polymorphic runtime deserialization and Jackson default typing stay disabled.
- Generated output is deterministic (byte-identical for identical inputs), proven by
  golden tests plus a generate-twice comparison.
- Compile-time rejections per spec section 11 (duplicate names, unsupported types,
  missing capability annotations, ambiguous overloads, forbidden types in contracts).
