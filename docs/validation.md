# Validation — 2026-10-01

Version: 0.1.0-probe (code 1), application ID `io.github.igorcv88.oneuiliquidglass`.

Local build used JDK 17, Gradle 8.11.1, AGP 8.10.1, Android SDK 36 and Build Tools 35.0.0.

- `testDebugUnitTest lintDebug assembleDebug`: passed.
- Nine JVM tests: zero failures/errors.
- Lint: zero errors, 5 warnings (private reflection, backup configuration and untranslated diagnostic UI literals).
- APK signature verification with `apksigner verify`: passed.
- Manifest package/version/min SDK 33/target SDK 36: checked with `aapt dump badging`.
- Source diff whitespace check: passed.
- No workflow was dispatched.

APK SHA-256: `40fcd6b5dffd1800a4ea026d7fe6207a660a35bf43734f07e35484cde96342ea`.

No device test has been performed. Android runtime shader compilation, Samsung hook invocation, actual lower-app blur, window layering, animation synchronization and cleanup still require the physical test described in `physical-test.md`. JVM policy tests are not hook or compositor integration tests.
