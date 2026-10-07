# Technical references

The implementation in this repository was written independently. No renderer, shader or source file from the projects below was copied. Repository licenses must be reviewed again before importing any upstream implementation.

## Inspected for this implementation

- [Iconify HeadsUpBlur.kt](https://github.com/Mahmud0808/Iconify/blob/738bfc1aa07adf5eb31d1ea5c98d5216102a89a5/app/src/main/java/com/drdisagree/iconify/xposed/modules/quicksettings/HeadsUpBlur.kt): lifecycle candidates, `mBackgroundNormal`, ViewRoot blur and native background corner/tint assumptions. Upstream repository has a GPL license; no code copied.
- [OneUIX Notification.kt](https://github.com/SoClear/OneUIX/blob/b2c46dbfb26d390b91c20c02ff0cdc40c552a6fa/hook/src/main/java/io/github/soclear/oneuix/hook/systemui/Notification.kt): Samsung keeps `ExpandableNotificationRow` in this snapshot and has explicit version-dependent hooks elsewhere in its notification handling. This does not prove One UI 9 compatibility.
- [AOSP NotificationBackgroundView.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/packages/SystemUI/src/com/android/systemui/statusbar/notification/row/NotificationBackgroundView.java): native draw contract, corner/tint assumptions and expansion bounds; inspected on 2026-10-01, not used as a Samsung firmware dump.
- [WaEnhancerX Community `LiquidLens`/`GlassSpec`](https://github.com/igorcv88/WaEnhancerXCommunity) at `88f5799` (GPL-3.0; its optical model credits MIT-licensed QWEA0/Liquid-Glass-Android and styropyr0/Prismal): read on 2026-10-07 for its documented, device-measured tuning rules. No code copied. Principles adopted in this module's independently written `LiquidGlassShader`: no vertical (dome) shading, every term a function of distance to the outline; a narrow full-contour hairline with a small geometric fringe; uneven lit runs on the light-facing side and a fainter opposite run with the lower edge damped; thickness shadow starting inside the outline; near-zero flat fill with tone carried elsewhere; lower blur so the backdrop survives. Not adoptable here: refraction and dispersion of the backdrop, which require sampling backdrop pixels that a cross-process compositor blur does not expose.
- [AOSP window blur documentation](https://source.android.com/docs/core/display/window-blurs): compositor availability and lifecycle requirements.

## Research queue from the project brief

- QWEA0 / Liquid-Glass-Android: snapshot `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`; sampled backdrop and optical rendering research.
- Abdullajon1881 / LiquidGlass: snapshot `72ad05c49628ea2270116b2943629cd81c0cc496`; shared backdrop provider and render tiers.
- HyperLight: SystemUI visual and architectural comparison; precise repository/version still needs selection.

These queued engines have not been claimed as validated for cross-process Samsung SystemUI backdrop acquisition.
