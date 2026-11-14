# Basement launcher development

This fork is a native Java Android TV launcher. The current home screen is free-standing app logos and wordmarks with small labels on an open canvas. A short focus underline, unboxed sports information, and room scenes replace the upstream banner tiles and glass dock. Keep this direction unless the user requests another design.

- Read `basement/README.md` for the personalized setup. The root README also retains the original project's debloating guide.
- Keep app discovery off the main thread. Cache unchanged component/label entries and invalidate changed packages. Use local vector brand marks for known apps. Other installed icons are bounded to 256 pixels per side, prepared off the main thread and cached. Never load large banners or blurred backdrops.
- Keep D-pad movement deterministic. App-row edges stop; Up from the first row reaches the header; Down from the final row reaches scenes; Up from scenes returns to the selected app.
- `launcher/local.properties` contains private Home Assistant settings. Never print, commit, or copy those settings into fixtures or emulator builds.
- Use `-PwithoutHomeAssistant=true` for emulator work. This disables both compiled Home Assistant fields without modifying local credentials. ESPN schedule requests remain enabled.
- Address ADB devices by explicit serial. Preview checks belong on an emulator; physical-TV installation and acceptance are separate gates. Do not run the full debloating/install script for a UI-only preview.
- Alert polling must wait for the previous request to finish. Camera decoding is bounded to the displayed size; pending shows, sticky alerts, and replacement dismissal each need regression coverage.

## Checks

JDK 17, Android SDK platform/build-tools 34, and the existing Gradle wrapper are required. This Mac's installed toolchain is `/opt/homebrew/opt/openjdk@17` and `/opt/homebrew/share/android-commandlinetools`.

```bash
cd launcher
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew -PwithoutHomeAssistant=true assembleDebug assembleDebugAndroidTest lintDebug --console=plain
tests/run-device.sh emulator-5554
```

The device script refuses physical devices, installs synthetic app fixtures, opens Settings, and displays synthetic alert overlays. It uses the platform instrumentation API and adds no test/runtime libraries. Keep generated APKs, screenshots, metrics, and device dumps out of Git.
