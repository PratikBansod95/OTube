# OTube

YouTube-focused Android browser with ad/tracker blocking and privacy defaults.

## What it does

- Opens YouTube in a full-screen WebView (no browser chrome clutter)
- Blocks ads and trackers using EasyList + EasyPrivacy network rules
- Hides YouTube ad UI with targeted cosmetic CSS and skip-ad helpers
- Upgrades `http://` navigations to HTTPS
- Disables third-party cookies and denies camera/mic permission prompts
- Back gesture exits fullscreen, then walks WebView history, then leaves the app

## Build

```bat
gradlew.bat assembleDebug
gradlew.bat installDebug
```

Requires Android SDK (see `local.properties`) and JDK 11+.

## Notes

- Package id: `com.lightshield` (app label: **OTube**)
- Native Brave `adblock-rust` was explored under `external/` but is not wired (no Rust/NDK toolchain in this rebuild). The Kotlin ABP matcher indexes host-anchored rules for performance and skips cosmetic list entries.
- Filter lists ship as small seeds in `assets/` and refresh from easylist.to every 24 hours when online.
