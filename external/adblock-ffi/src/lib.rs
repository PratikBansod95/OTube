//! JNI bridge for the adblock-rust engine.
//!
//! Exposed to Kotlin as `com.lightshield.adblock.NativeAdblock`.

use adblock::{
    lists::{FilterSet, ParseOptions},
    request::Request,
    Engine,
};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde_json::json;
use std::panic::{catch_unwind, AssertUnwindSafe};

fn engine_from_ptr<'a>(handle: jlong) -> Option<&'a Engine> {
    if handle == 0 {
        return None;
    }
    Some(unsafe { &*(handle as *const Engine) })
}

fn build_engine(rules_text: &str) -> Engine {
    let mut filter_set = FilterSet::new(false);
    let lines: Vec<&str> = rules_text.lines().collect();
    filter_set.add_filters(&lines, ParseOptions::default());
    Engine::from_filter_set(filter_set, true)
}

#[no_mangle]
pub extern "system" fn Java_com_lightshield_adblock_NativeAdblock_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    rules: JString,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let rules_java: String = match env.get_string(&rules) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let engine = build_engine(&rules_java);
        Box::into_raw(Box::new(engine)) as jlong
    }));
    match result {
        Ok(handle) => handle,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lightshield_adblock_NativeAdblock_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        unsafe {
            drop(Box::from_raw(handle as *mut Engine));
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_lightshield_adblock_NativeAdblock_nativeShouldBlock(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
    source_url: JString,
    request_type: JString,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let engine = match engine_from_ptr(handle) {
            Some(e) => e,
            None => return JNI_FALSE,
        };
        let url: String = env.get_string(&url).map(|s| s.into()).unwrap_or_default();
        let source: String = env
            .get_string(&source_url)
            .map(|s| s.into())
            .unwrap_or_default();
        let rtype: String = env
            .get_string(&request_type)
            .map(|s| s.into())
            .unwrap_or_else(|_| "other".into());

        if url.is_empty() {
            return JNI_FALSE;
        }
        let source_ref = if source.is_empty() { &url } else { &source };
        let request = match Request::new(&url, source_ref, &rtype) {
            Ok(r) => r,
            Err(_) => return JNI_FALSE,
        };
        let blocker = engine.check_network_request(&request);
        if blocker.matched {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }));
    match result {
        Ok(v) => v,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lightshield_adblock_NativeAdblock_nativeUrlCosmetics(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let engine = match engine_from_ptr(handle) {
            Some(e) => e,
            None => {
                return env
                    .new_string("{}")
                    .map(|s| s.into_raw())
                    .unwrap_or(std::ptr::null_mut());
            }
        };
        let url: String = env.get_string(&url).map(|s| s.into()).unwrap_or_default();
        let resources = engine.url_cosmetic_resources(&url);
        let hide: Vec<String> = resources.hide_selectors.into_iter().collect();
        let payload = json!({
            "hide_selectors": hide,
            "injected_script": resources.injected_script,
            "generichide": resources.generichide,
        })
        .to_string();
        env.new_string(payload)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));
    match result {
        Ok(s) => s,
        Err(_) => env
            .new_string("{}")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lightshield_adblock_NativeAdblock_nativeIsAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}
