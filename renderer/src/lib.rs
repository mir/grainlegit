#[cfg(any(target_os = "android", all(test, target_os = "macos")))]
mod edit;
#[cfg(any(target_os = "android", all(test, target_os = "macos")))]
mod emulsion;
#[cfg(any(target_os = "android", all(test, target_os = "macos")))]
mod gpu;
#[cfg(any(target_os = "android", all(test, target_os = "macos")))]
mod lens;
#[cfg(any(target_os = "android", all(test, target_os = "macos")))]
mod preview;

#[cfg(target_os = "android")]
mod android;
#[cfg(all(test, target_os = "macos"))]
mod gpu_test;
#[cfg(target_os = "android")]
mod jpeg;
