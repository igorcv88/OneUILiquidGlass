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

`version`/`versionCode` come from `sfhook/live/version.prop` (now `v0.25.0` / `26`). Bump them when anything
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
| `status` | reads only | `helper`, `fingerprint`, `fingerprint_ok`, `bundled_ok`, `bundled_version`, `bundled_versionCode`, `bundled_commit`, `installed_version`, `installed_versionCode`, `installed_commit`, `installed_ok`, `update_needed`, `selftest_ok`, `sf_pid`, `sf_generation`, `sf_loaded`, `sf_got`, `sf_rewrite_ok`, `sf_stable`, `sf_rewrite`, `sf_optics`, `sfrefract` |
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

### Parameter readback versus compiled shader

`getprop debug.oulg.sf.*` changes immediately; GLSL constants do not. The UI must not consider
matching desired and observed properties proof that the running shader contains those values.
Before an app-initiated restart, the controller captures **all** compositor inputs, including
properties owned by Terminal (no saved app override), and the old SurfaceFlinger generation (boot ID, PID, process start ticks). Only after
the user explicitly checks status and the helper reports an injected **new** SurfaceFlinger with
refraction/rewrite enabled, GOT acknowledgement, at least one successfully compiled fragment rewrite,
completion of the bounded monitor, and matching properties does the app persist that input snapshot as its
last verified restart baseline. Subsequent Terminal changes are pending against that baseline even
if the user chose “Usar o do terminal” in the conflict dialog. Without a verified baseline, the
app shows an explicit unverified state, not an “already applied” claim.

Between the restart request and that confirmation, the inputs **sent** by the restart are the
baseline, not the previous compile: a value that went out with the restart awaits verification, it
is not pending again, and the panel does not offer another restart for it (before, “3 % → 3 %” stayed
pending and every “Aplicar” restarted again without ever confirming). The pending list shows the
baseline value on the left (what the compositor compiled or was last sent), not the current property.

This is a verified restart/input readback, **not** proof of a successful shader rewrite for every
program; a failed injection or unexplained process replacement still requires the injector log and the shader
rewrite diagnostics. Restarts outside the app cannot safely be acknowledged automatically.

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

## Bounded restart health (v0.25)

The existing SF + zygote restart sequence is preserved. The detached injector now observes the
transaction until 120 seconds after SF start, instead of ending about 33 seconds into recovery.
It logs `SF_TRANSITION` with old/new PID and start ticks. This detects replacement; it does not
identify a crash. A single reinjection is allowed during the first 30 seconds after the zygote
restart, only after a clean first injection and a minimum 1 second cooldown. A generation caught
during cooldown is refused rather than delayed past its entry point. A failed attach, a used retry
budget or an expired retry window stops further injection. There are no additional SF/zygote
restarts, persistent services, boot hooks or resident watchdogs. A file lock serializes transactions.

The library writes an atomic `oulg_health` record only inside SurfaceFlinger, never in the
throwaway selftest. Its PID/start ticks bind GOT acknowledgement and successful fragment compilation
to that process. The root worker writes `oulg_monitor` (PID/start ticks, outcome, boot ID) at the end.
The helper verifies both against the active process, plus the installed library inode, and repeats
the process identity check after reading status. Missing files, SELinux write denials, old generations,
old library inodes and residual `sf.optics=9` cannot confirm an apply. `sf_rewrite` is the requested
mode; `sf_rewrite_ok` is independent observed compilation evidence. Neither proves every shader
compiled or that a program linked/drew correctly.

`sf_stable=1` acknowledges the bounded process/GOT observation, not all future process lifetime.
A fragment may compile on demand after that window; until then the app remains unverified.
If SF changes after monitoring ends, the next explicit status check detects the loss. After an
app-initiated restart, up to three read-only status reads run automatically while the app is in the
foreground: 125, 155 and 185 s after the request (the monitor's 120 s start only once SurfaceFlinger
has stopped). Each checks root (granted to this APK for the restart itself) and reads `status`,
which records the baseline when the restart is confirmed; reads stop as soon as it is. A read due
while a lens or root operation runs is deferred 5 s and not counted. Nothing else runs on resume. A reopened app asks for
verification before treating the stored baseline as current. “Verificar estado” reads only; “Reiniciar lente” is a separate confirmed
action. Open a notification, wait two minutes after activation and check again.

`log` adds the current health record and a bounded Android logcat tail with epoch timestamps.
The injector's times are monotonic milliseconds from transaction start; `HEALTH_FINAL` closes the
window. To investigate an actual replacement, collect the log immediately, contemporaneous init /
crash-buffer messages and any tombstone with its event time. An older SIGILL is not evidence for a
new occurrence. This implementation has no device trace explaining the 27 October replacement.

## Export provenance and defaults

Schema 1 remains import-compatible. `desired` contains app overrides only; `observed` records a fresh
unprivileged property snapshot (including an availability flag), and `verifiedRestart` records the
historical generation/request time/input snapshot. Those diagnostic sections are **never imported**
as desired settings. Their timestamps do not imply current health or per-shader compilation.
A desired-only backup cannot reproduce Terminal-only settings or guarantee the complete appearance.

No defaults are changed by this userspace fix. `semradiuslens=22` remains the accepted test base;
`core=90 / ramp=1 / taps=48` is a reproduced, visually rejected experiment, not a new-user preset.
Part C's default promotion remains gated on real-device acceptance of A and B; no automatic profile
migration is installed. The profile-0 shader work already in PR 68 is left intact.

### Verification for this change

- Host: `tools/live_check.sh` covers current/old PID/start identities, monitor acknowledgement,
  residual optics, missing rewrite evidence, deleted/wrong inodes and helper installation.
- Host: `tools/health_check.c` checks native generation parsing/records and retry cooldown/budget.
- Java: Terminal-only changes (including `sf.edgeblur`), reset-to-default, live settings, complete
  restart inputs, incomplete operational evidence and diagnostic-export import isolation.
- Device still required: selftest; install/restart; open tagged cards; wait two minutes; verify
  current inode + GOT + fragment rewrite + final monitor record. Change props externally, choose
  “Usar o do terminal”, return Home and apply. Capture health/logcat/tombstone evidence immediately
  if SF is replaced, including after the monitor window. Compare all three visual profiles.
