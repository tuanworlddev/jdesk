# Quality policy

## Compiler warnings

Production sources compile with `-Xlint:all,-processing,-restricted -Werror`.
The `restricted` category is excluded because platform adapters intentionally use FFM;
native access is instead constrained by the packaged launcher's module-specific
`--enable-native-access` and `--illegal-native-access=deny` flags. Any other suppression
requires a review comment explaining why.

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
