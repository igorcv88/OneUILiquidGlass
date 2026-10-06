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

## Revalidation — 2026-10-06

Same toolchain (JDK 21 runtime, Gradle 8.11.1, AGP 8.10.1, SDK 36, Build Tools 35.0.0).

- CI failure root cause: `android-actions/setup-android@v3` installs its default package list `tools platform-tools`; current cmdline-tools no longer publish `tools`, so `sdkmanager` exits 1 before Gradle runs. The workflow now passes an explicit package list. The Gradle build itself was not broken.
- `clean testDebugUnitTest lintDebug assembleDebug`: passed. Nine JVM tests, zero failures. Lint: zero errors, 6 warnings (same categories plus the Gradle-version notice).
- `dexdump`: no `de.robv.android.xposed` classes bundled (compileOnly respected). `assets/xposed_init` and xposed meta-data present in the APK.
- Fixed: the app wrote preferences with `MODE_PRIVATE`, which LSPosed does not redirect, so SystemUI's `XSharedPreferences` could never read `enabled=true`. The app now uses `MODE_WORLD_READABLE` (LSPosed new-XSharedPreferences contract) and shows a warning when the module is not active.

Still no device test.
