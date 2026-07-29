# Native adblock

OTube can use the adblock-rust engine via JNI.

## Status

| Piece | Location |
|--------|----------|
| Native engine (vendored) | `external/adblock-rust/` |
| JNI cdylib | `external/adblock-ffi/` |
| Kotlin bridge | `app/src/main/java/com/lightshield/adblock/NativeAdblock.kt` |
| Built `.so` output | `app/src/main/jniLibs/{arm64-v8a,x86_64}/libadblock_ffi.so` |

If the `.so` is missing, the app falls back to the Kotlin filter matcher automatically.

## Build the native library

Prerequisites:

- Rust (`rustup`)
- Android NDK (SDK Manager)
- Network (first build downloads crates)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-native-adblock.ps1
```

Then:

```bat
gradlew.bat assembleDebug
```

## Runtime behavior

1. `FilterListManager` downloads EasyList + EasyPrivacy (24h cache).
2. Rules are compiled into an `adblock::Engine` inside `libadblock_ffi.so`.
3. `shouldInterceptRequest` calls `nativeShouldBlock`.
4. `onPageFinished` applies cosmetic hide selectors + scriptlets, plus YouTube fallback CSS.
