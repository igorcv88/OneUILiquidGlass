# Screenshot and perceptibility harness

Renders the app's real screens, sheets and notification samples on the JVM with
[Paparazzi](https://github.com/cashapp/paparazzi) (layoutlib), without a device or emulator. It is a separate
Gradle build: the app build and CI do not depend on it.

It compiles `app/src/main/java` and `app/src/main/res` as a library and drives the pages through a test
`Host` (`ShotHost`): same views, theme and model as `MainActivity`, in-memory preferences, no lifecycle.

```sh
cd tools/screenshots
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle testDebugUnitTest            # Gradle 8.14 (AGP 8.13); not the app's wrapper
```

- Images: `build/reports/paparazzi/debug/images/` (names in `build/reports/paparazzi/debug/runs/*.js`).
  The ones in `docs/img/ui/` come from here.
- `PerceptibilityTest` renders every control the preview claims to represent at its minimum and maximum,
  on all backdrops and themes, and fails if the focused sample (the editor's) changes in fewer than 2 % of
  its pixels. Results: `build/perceptibility.tsv`.
- Devices: `Devices` derives a Galaxy S25 Ultra-like screen (1440 x 3120, 19.5:9) with 130 % font and in
  landscape.

Limits: layoutlib is not One UI. Insets, system bars and window dim are approximated; RenderEffect and
RuntimeShader do render.
