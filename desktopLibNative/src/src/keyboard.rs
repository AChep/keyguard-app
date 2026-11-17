#[cfg_attr(target_os = "macos", path = "keyboard/macos.rs")]
#[cfg_attr(target_os = "windows", path = "keyboard/windows.rs")]
#[cfg_attr(target_os = "linux", path = "keyboard/linux.rs")]
#[cfg_attr(
    not(any(target_os = "macos", target_os = "windows", target_os = "linux")),
    path = "keyboard/stub.rs"
)]
mod imp;

/// Returns the language of the active keyboard layout as the platform reports
/// it: a BCP-47 tag on macOS and Windows, an XKB group name such as
/// `English (US)` on Linux. `None` when unknown.
pub(crate) fn input_language() -> Option<String> {
    imp::input_language()
}
