# Native adblock (not integrated)

This project originally planned to use Brave's [adblock-rust](https://github.com/brave/adblock-rust) via JNI.

**Current status:** not wired. `external/adblock-rust` is a vendored clone; `external/adblock-ffi` and `jniLibs` are empty. OTube ships a Kotlin EasyList/EasyPrivacy network matcher instead.

To integrate later you would need:

1. Rust + Android NDK + `cargo-ndk`
2. A thin `cdylib` FFI crate around `adblock::Engine`
3. JNI bindings called from `RequestInterceptor`
4. Packaging `.so` files under `app/src/main/jniLibs/`
