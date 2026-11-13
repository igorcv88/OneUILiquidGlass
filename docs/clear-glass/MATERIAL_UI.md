# Glass material (Frosted / Clear) in the app

This describes the Android side of the choice between the original look (Frosted) and Clear Glass optics 12 for pop-ups and the lock screen. It does not change the compositor, its shaders, the meniscus, the adaptive blur or the colour curve; those are the approved state of PRs #184 to #186. The properties and their ranges are in `UI_HANDOFF.md`; the visibility rules per material are generated in `MATERIAL_MATRIX.md`.

## What the person does

1. **Select.** Home, card "Glass material" (also reachable from the pop-up and lock-screen tabs): Frosted or Clear (iOS style). The choice is global for the pop-up and the lock screen. It only saves two compositor selectors in one edit: no `su`, no property write, no restart.
2. **Configure.** The pop-up and lock-screen tabs show the controls that act on the material in force for that tab. Main controls first (meniscus, outer-edge blur, centre blur, adaptive blur for pop-ups, saturation, brightening), the rest in the collapsed groups. Values of the other material stay saved.
3. **Review.** Every saved compositor value that the running compositor was not compiled with is listed on the lens screen ("Waiting for a restart", old to new value). Home shows a notice with the count.
4. **Apply.** One action on the lens screen, one confirmation (it says that the userspace restarts, apps close and it can fail), one `setprops` call with every waiting value, one `restart rewrite`. Nothing is applied when a slider moves or a screen changes.
5. **Verify.** The restart is checked by the existing status read after the injector's health window. A property read-back is not a compile acknowledgement.

## State shown for the material

| State | Evidence | Home text |
| --- | --- | --- |
| Mixed | `sf.huclear` and `sf.kgclear` differ, or one is 2 (per card) | Set outside the app: pop-ups X, lock screen Y. Nothing is selected. |
| Unavailable | Clear chosen and the compositor is not running the dynamic lens | Saved; Clear needs the dynamic lens (action: Set up) |
| Pending | a selector is saved and differs from what the compositor was compiled with (or last sent) | Saved, not applied yet (action: Review and apply; Undo choice when a compiled material is known) |
| Not confirmed | values match, but no verified restart / stable status / matching generation | Not confirmed (action: Check) |
| Applied | verified restart, nothing pending, last apply not NOT_LOADED/CHANGED | Applied. The last verified restart compiled this material. |
| Not required | Frosted without the compositor | Standard look. Nothing to apply. |

`MaterialStatus.of` is a pure function over `Mode`, the pending keys, `AppModel.compositorUnverified()` and `CompositorValues.Outcome`; it reuses `CompositorValues`, `SfLiveController` and `LensSheet` and adds no new confirmation path. "Applied" never comes from `setprops=ok`. Physical appearance is never claimed.

## Persistence and ownership

- The material is not a separate preference: it is `desired.sf.huclear` and `desired.sf.kgclear` (Frosted = 0/0, Clear = 1/1). It therefore goes through the same saved / pending / compiled bookkeeping, undo, styles and export/import as every other compositor value.
- Switching writes both selectors in one `ConfigRepository.replace` (one undo step) and nothing else. Clear parameters, Frosted parameters and the shade are not touched.
- A selector the Terminal changed since the app last saved it is a conflict: the choice is refused and the existing conflicts screen opens. Nothing is overwritten.
- Opening the app, Home, the surface tabs or search writes nothing. Values already present on the device (getprop) are shown as they are (a row's value is draft, saved, then device).
- Per-card mode (2) is never selected by the app; it shows as "set outside the app" and keeps both groups visible.

## First use

The native Clear-only defaults now select the approved meniscus/blur/adaptive settings, including HU lift 0.30; Frosted remains unaffected. Explicit properties and previously saved overrides still take precedence, so a material switch never replaces them. When Clear is chosen and no Clear parameter has a value in the app or on the device, the card offers "Use recommended Clear settings": a review sheet lists each change, and saving writes desired values only. The set is the reviewed look (`hcband` 0.42, `hcfold` 2, `hcedge` 24, `hccore` 24, `hcbody` 0, `hcbody2` 0, `hcadapt` 1, `hcsat` 1.05, `hcscrim` 0, `hcscrimmax` 0 and `kcfold` 2, `kcband` 0.42, `kcedge` 24, `kccore` 24, `kcbody` 0, `kcbody2` 0). `hclift`, `kclift` and `hcneutral` are left alone. It is not a library default and is never applied automatically. When Clear values already exist on the device and are not saved in the app, the card offers "Keep this device's Clear settings" instead, with the same review sheet; it saves the values the device already runs.

## Which controls show

The classification is central (`GlassMaterial.scope(key)`), by key and not by prefix:

- **clear**: the 29 `hc*`/`kc*` parameters (and only for their own profile).
- **frosted**: the optics-11 knobs the clear variant replaces (`INTEGRATION_CONTRACT.md` section 4, `UI_HANDOFF.md` sections 3 to 5): `core`, `edgeblur`, `ramp`, `hubodylens`, `kgcore`, `kgedgeblur`, `kgcorecap`, `kgramp`, `kgblurexp`, `kggradient`, `kgband`, `kgbodylens`, `kgsat`, `kgtone`, `kgflat`, `kglevel`, `kgcool`, `kgflatcap`, `kgcoredim`.
- **common**: everything else, including the knobs that keep acting on Clear (`hurefract`, `hublur`, `taps`, `hulight`, `hubloom`, `kgrefract`, `kgblur`, `kgtaps`, `kglight`, `kgdepth`, `kgglow`, `kgrim`, `kgspec`, `kgline`, `kgtilt`, `kgfixedrim`).

The tab's material comes from its own selector. The ordinary lists, favorites, search, the per-surface reset and the customised-count on Home follow it; "All parameters" lists everything and says which material uses each key. The shade and Quick Settings are never filtered. Applicability adds the dependencies: `hcamp`/`kcamp` only while the same profile's meniscus is 0; `hcneutral` only while `hcsat` is not 1, `hcscrimmax` is above 0 or `hclift` is above 0 (a silent change of `hcsat` is not used as a workaround); Clear parameters are inactive when the profile is not Clear, Frosted knobs when it is.

## Decisions to review

- `sflens` and `kglens` stay common: the tag strength still caps the slab amplitude when the meniscus is 0 (`UI_HANDOFF.md` section 3), although the contract also lists `sflens` among replaced knobs.
- `kggradient` is classified frosted by the contract's "and similar for KG"; it is not named in `UI_HANDOFF.md` section 4. Check it on the device before relying on it.
- The module-drawn standard-glass controls (`liveradius`, `livedarkfill`, `liverim`, ...) stay common: they are the look whenever the compositor is not running, and the existing mode rules already disable them under the dynamic lens.
- A compiled baseline recorded before this change does not contain the Clear keys. `CompositorValues.pending` skips keys the baseline never recorded, so Clear values that were already on the device are not reported as changes. A value the app saves is still pending.
- Slider steps of the Clear blur radii (1 px), meniscus, blur rise and lift (0.05) are set in `Controls` (`Control.step`); the registry steps and ranges are unchanged.
- The preview cannot reproduce the Clear shader, so Clear controls state that there is no visual sample instead of drawing an approximation.
- `hclift`/`kclift` at 1 or more show "Can reduce text contrast": the module still chooses text colour from the backdrop, not from the composited body (`UI_HANDOFF.md` section 7). The warning is not a fix; the text rule is not changed here.

## Native or optical items left out

- Java text colour from the effective body luma (lift, dim, scrim).
- `kgsharedoptics` Java edge over the compositor contour on clear lock-screen cards.
- Per-card mode (2) and its tagging.
- Anything in `sfhook/`.

## Validation on the device (not run in this change)

1. Install the APK and the bundle; open Home. Nothing must be written or asked of root. Check that a Terminal-set Clear configuration is shown, not replaced.
2. Choose Clear, set two pop-up and one lock-screen value, then open the lens screen: the list must show exactly those changes (including both selectors). Cancel the confirmation: nothing restarts.
3. Confirm once. After the restart and the status read, Home must say Applied only when the status is stable and the generation matches; otherwise Not confirmed. Compare `getprop debug.oulg.sf.clear` with the choice.
4. Switch back to Frosted and apply: the pop-up and lock screen must return to the optics-11 look; the Clear values must still be in the rows after choosing Clear again.
5. With a Terminal change to `debug.oulg.sf.huclear` after a save, choosing a material must open the conflicts screen.
6. Language: change to Portuguese and back while on the surface tabs; the selection, tab and scroll position stay.
7. Shade, Quick Settings, Now Bar and toasts: unchanged.
