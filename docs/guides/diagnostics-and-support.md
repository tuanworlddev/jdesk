# Diagnostics and support bundles

SupportBundle.create produces a ZIP only when the application or user explicitly asks for
one. It does not upload anything.

SupportBundleOptions.defaults accepts the application id, application version and an
explicit list of log paths. The resulting system.json contains an allowlist:
application, JDesk and protocol versions, OS and Java version, architecture, locale, time
zone, heap limit and processor count. It never dumps the process environment, JVM
arguments, clipboard, secret store or application data.

Log input is explicit, symlink logs are skipped, only a bounded tail is read, and both
per-log and aggregate byte limits are enforced. Authorization headers, bearer tokens,
JWTs, password, secret, token and API-key assignments, the user home and temporary
directory are redacted.

Applications remain responsible for avoiding customer content in logs and giving the user
a chance to review a bundle before upload.
