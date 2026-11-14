# Emulator preview validation — October 20, 2026

The current local preview uses free-standing app logos/wordmarks, small labels, a focus underline, and unboxed sports/room controls. Source changes are uncommitted on `basement-home-theater`, based on `59ebae821705f9e1bf70fe151171c787abf65023`. No push, pull request, or physical-TV deployment was performed.

## Checks observed

- `./gradlew -PwithoutHomeAssistant=true assembleDebug assembleDebugAndroidTest lintDebug --console=plain`: passed. Lint reports **0 errors, 34 warnings**; warnings remain visible in the generated report.
- Platform instrumentation on the Android 12 TV emulator: **PASS: 7 launcher checks**. Coverage includes deterministic app navigation, package-cache identity, package updates preserving header focus, Settings-to-Home focus restoration, long input names scrolling into view, bounded 4K camera decoding, replacement-dismissal ownership, pending camera reservations, sticky priority, and notification queue bounds.
- `git diff --check`: passed.
- Device script shell syntax and fixture generator Python syntax: passed. The device script's physical-device rejection was exercised and refused the target before any ADB operation.
- The visible Android TV emulator was visually inspected after confirming `com.gathin.tvhome/.MainActivity` was resumed. Its temporary Home target was set to the preview so the Home key returns there. The physical TV was not changed.

## Memory observation

The original revision and final preview were exercised on the same hidden Android TV emulator with eight synthetic apps containing oversized banners, plus its installed apps. Each run used ten Settings-to-Home returns.

| App process measurement | Original | Logo preview |
| --- | ---: | ---: |
| Total PSS | 138,247 KiB | 52,081 KiB |
| Native heap PSS | 113,012 KiB | 27,160 KiB |

This is roughly 62% lower total PSS in that synthetic workload. It demonstrates the effect of removing banner decoding and animated blur, caching catalogue entries, and bounding fallback icons. It does not establish the exact saving on the Hisense with its actual app mix.

Raw screenshots and process dumps remain outside Git. Software-emulator frame timing is not physical-TV performance evidence.

## Remaining acceptance

The user is reviewing the visible simulator. Actual TV viewing-distance legibility, physical remote/input behavior, real camera alerts, notification-listener reconnection, Home Assistant scenes, and the sleep timer have not been validated on the new build. Emulator builds have Home Assistant disabled and cannot establish those live integration claims.
