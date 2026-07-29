# OTube

YouTube-focused Android browser with **Brave adblock-rust** (when native libs are present) plus privacy defaults.

## What it does

- Opens YouTube in a full-screen WebView
- Blocks ads/trackers with Brave's `adblock-rust` engine via JNI (`libadblock_ffi.so`)
- Falls back to a Kotlin ABP matcher if the native library is missing
- Applies Brave cosmetic hide selectors / scriptlets, plus YouTube fallback CSS
- Upgrades `http://` to HTTPS, disables third-party cookies, denies camera/mic
- Back: exit fullscreen → WebView history → leave app

## Build app

```bat
gradlew.bat assembleDebug
gradlew.bat installDebug
```

Requires Android SDK (see `local.properties`) and JDK 11+.

## Build native Brave engine

Requires Rust, Android NDK, and MSVC Build Tools (host linker on Windows):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-native-adblock.ps1
```

See `README-native-adblock.md`.

## Notes

- Package id: `com.lightshield` (app label: **OTube**)
- Filter lists ship as seeds in `assets/` and refresh from easylist.to every 24 hours when online
- Prebuilt `.so` files live under `app/src/main/jniLibs/`
