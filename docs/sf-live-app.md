# SurfaceFlinger live lens in the app: contract for the control panel

Date: 2026-10-26. For whoever builds the app's "SurfaceFlinger" button. The app only extracts a bundle
and runs one root helper with a fixed set of commands; everything privileged is in that helper.
Route A itself (the injector, the library, the device results) is in `docs/sf-sem-overlay.md`.

## What ships

The release publishes **only the APK**. It carries the live bundle in `assets/sfhook/`, built by
`sfhook/build.sh` before Gradle (`app/build.gradle` adds `sfhook/build/apk-assets` as an asset source):

| File | What it is |
| --- | --- |
| `oulg_inject` | static arm64 injector (`sfhook/inject.c`) |
| `liboulg_sf.so` | the compositor library (`sfhook/oulg_sf.c` + `refract.h`) |
| `oulg-live.sh` | the root helper (`sfhook/live/oulg-live.sh`) |
| `allowed_fingerprints` | `ro.build.fingerprint` values the route was validated on (DZIF only) |
| `live.prop` | manifest: `version`, `versionCode`, `commit`, `abi`, `sha256_inject`, `sha256_lib`, `sha256_helper` |

`version`/`versionCode` come from `sfhook/live/version.prop` (now `v0.21.0` / `22`). Bump them when anything
that ends up in the binaries changes. The injector embeds that version, not the commit, so binaries
rebuilt from unchanged sources hash the same: an APK update alone does not ask for a reinstall.

A local build without the NDK step has no bundle; `SfLive.bundled(context)` is then false and the
feature should show as unavailable.

## Java side (`live/SfLive.java`)

- `bundled(context)`, `manifest(context)`: whether the APK carries the bundle, and its `live.prop`.
- `extract(context)`: copies the bundle to `<filesDir>/sfhook` (atomically per file) and returns it.
- `command(dir, Command, mode)`: the argv `su -c "sh '<dir>/oulg-live.sh' <command> [mode]"`. Only the
  fixed commands, and for `RESTART` only `rewrite` or `dump`, are accepted.
- `parse(text)`: the helper's key=value output as a map.
- `state(statusMap)`: what the button should do next (below).

Nothing in `SfLive` starts a process; the caller owns the thread, the su prompt and the UI.

## Helper commands

`sh oulg-live.sh <command>` as root. Output: `key=value` lines. Exit: 0 ok, 1 failed, 2 refused (a
precondition or usage; `error=<reason>` says which).

| Command | Does | Keys |
| --- | --- | --- |
| `status` | reads only | `helper`, `fingerprint`, `fingerprint_ok`, `bundled_ok`, `bundled_version`, `bundled_versionCode`, `bundled_commit`, `installed_version`, `installed_versionCode`, `installed_commit`, `installed_ok`, `update_needed`, `selftest_ok`, `sf_pid`, `sf_loaded`, `sf_rewrite`, `sf_optics`, `sfrefract` |
| `install` | checks the bundle against its manifest and the fingerprint, then copies the injector to `/data/local/tmp/oulg/`, the library to `/data/misc/surfaceflinger/` (`system_lib_file`) and the manifest | `install`, `version` |
| `selftest` | the whole injection into a throwaway `/system/bin/sleep`, with the BTI control (`docs/sf-sem-overlay.md`) | `selftest` (`ok`/`failed`), `rc`, `log` |
| `restart rewrite` / `restart dump` | **restarts the whole userspace** (the app dies with it): stop SurfaceFlinger, clear its shader cache, start it, inject at its entry point, restart the zygote 3 s later. `rewrite` turns the lens on; `dump` only records shaders | `restart=started`, `log` |
| `log` | the last selftest and injection logs | plain text |
| `uninstall` | clears `sfrefract`/`sf.norewrite`, removes the files, the selftest stamp and the shader cache | `uninstall`, `restart_needed` |

Meaning of the status keys that drive the button:
- `update_needed=0` only when the installed files' sha256 are the bundle's. Not installed, a different
  library or injector, or a corrupt install all read 1.
- `selftest_ok=1` only when a passing selftest of the installed injector *with the installed library*
  is on record (the stamp holds both hashes). Every install needs a new selftest.
- `sf_loaded=1` only when the running SurfaceFlinger maps the installed library file. After a reinstall
  it still maps the old inode (`(deleted)` in its maps); that reads 0.

## The button, as `SfLive.state` reads `status`

| State | Means | Next |
| --- | --- | --- |
| `NO_ROOT` | the helper did not answer (no su, prompt refused) | explain; nothing else |
| `UNSUPPORTED` | firmware not on `allowed_fingerprints` | explain; nothing is installed or restarted |
| `UPDATE` | bundled binaries differ from the installed ones, or none are installed | `install`, then `selftest` |
| `SELFTEST` | installed and current, no passing selftest on record | `selftest` |
| `RESTART` | installed, current, self-tested, not loaded (after a reboot, or after an update) | ask, then `restart rewrite` |
| `LOADED` | SurfaceFlinger runs the current library | nothing: only the APK needed updating |

`selftest` failing is a stop: show its `log`, do not offer the restart.

The confirmation before `restart` must say that the screen goes black and the userspace restarts
(~20 s), that open apps close, and that a reboot always returns to the stock compositor.

## What the helper and the injector guarantee

- **Atomic install:** new files are written beside the old ones and renamed over them. Copying over
  the library a running SurfaceFlinger has mapped would change its code under it.
- **Selftest first:** the injector refuses any injection without a passing selftest of the same
  injector and library.
- **Lens mode only with the library:** `restart rewrite` passes `--sfrefract`. The injector clears
  `debug.oulg.sfrefract` at the start and sets it to 1 only after SurfaceFlinger loaded the library,
  before the zygote restart, so the new SystemUI tags its cards. It is cleared again if the
  injection failed. `sfrefract=1` without the library turns off the app's live mode.
- **Recovery:** a SurfaceFlinger that cannot be left intact is killed and init starts a clean one;
  the zygote is restarted whatever the injection did. Nothing is loaded at boot.

## Not done here

- The button and its UI (the app refactor, `docs/HANDOFF_APP_REFACTOR_2026-10-26.md`).
- Re-injection at boot: the route is manual by design; after a reboot the state is `RESTART`.
- Play Integrity and Wallet were not re-measured after an injection with the lens on.
