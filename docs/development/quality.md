# Quality policy

## Compiler warnings

All modules compile with `-Xlint:all,-processing`. Policy (spec section 21): warnings
are surfaced in every build; `-Werror` is enabled once the Phase 1 static-analysis
baseline lands, so that new code cannot add warnings. Any suppression requires a
review comment explaining why.

## Test categories

`jdesk.test.category` system property marks report category (`unit` by default).
Native/package/integration categories are set only by the real native test harnesses;
unit reports can never satisfy native gates (spec section 17.1).

## Dependency hygiene

- Versions centralized in `gradle/libs.versions.toml`.
- All configurations locked (`gradle.lockfile` per project); regenerate with
  `./gradlew resolveAndLockAll --write-locks`.
- Checksum verification via `gradle/verification-metadata.xml`; regenerate with
  `./gradlew --write-verification-metadata sha256 build` and review the diff.
- No dynamic versions, no Git dependencies.
