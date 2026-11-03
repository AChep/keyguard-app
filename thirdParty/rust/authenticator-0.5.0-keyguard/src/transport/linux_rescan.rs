/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Keyguard: Flatpak rescan helpers for linux/monitor.rs. They live outside
// `platform`, which test builds replace with a mock, so their tests run.

#[cfg(test)]
#[path = "linux_rescan/keyguard_tests.rs"]
mod keyguard_tests;

use std::collections::HashMap;
use std::env;
use std::ffi::CString;
use std::fs;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant};

use super::HIDError;

pub(super) const RESCAN_INTERVAL: Duration = Duration::from_millis(500);
const CANCEL_INTERVAL: Duration = Duration::from_millis(100);

// Mirrors the Flatpak detection in common's LePlatform.kt.
pub(crate) fn is_flatpak() -> bool {
    env::var("container").as_deref() == Ok("flatpak") || Path::new("/.flatpak-info").exists()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct NodeIdentity {
    device: u64,
    inode: u64,
}

impl NodeIdentity {
    pub(super) fn read(path: &Path) -> Option<Self> {
        fs::metadata(path).ok().map(|metadata| Self {
            device: metadata.dev(),
            inode: metadata.ino(),
        })
    }
}

// None means the path was enumerated but its metadata could not be read.
pub(super) type Scan = HashMap<PathBuf, Option<NodeIdentity>>;

pub(super) fn wait_for_rescan(alive: &dyn Fn() -> bool) -> bool {
    let start = Instant::now();
    while alive() {
        let remaining = RESCAN_INTERVAL.saturating_sub(start.elapsed());
        if remaining.is_zero() {
            return true;
        }
        thread::sleep(remaining.min(CANCEL_INTERVAL));
    }
    false
}

// Retry only discovery, never the callback's authentication/configuration work.
// A worker stays alive until its monitor cancels it or the request times out.
pub(crate) fn retry_initialization<T>(
    mut initialize: impl FnMut() -> Result<T, HIDError>,
    alive: &dyn Fn() -> bool,
) -> Option<T> {
    while alive() {
        match initialize() {
            Ok(device) => return alive().then_some(device),
            Err(HIDError::IO(..)) => {
                if !wait_for_rescan(alive) {
                    break;
                }
            }
            // In particular, don't repeatedly probe a known non-FIDO device.
            Err(_) => break,
        }
    }
    None
}

// The kernel creates a node before udev grants the user access to it, so a
// rescan waits until the node is readable and writable.
pub(super) fn is_accessible(path: &Path) -> bool {
    let path = match CString::new(path.as_os_str().as_bytes()) {
        Ok(path) => path,
        Err(_) => return false,
    };
    // SAFETY: `path` is a NUL-terminated string that outlives the call.
    unsafe { libc::access(path.as_ptr(), libc::R_OK | libc::W_OK) == 0 }
}

// Retain known nodes even after their workers finish. Invalidate that cache
// when the node disappears or is replaced, but not on a metadata read failure.
pub(super) fn diff_scan(
    tracked: &Scan,
    scanned: &Scan,
    ready: impl Fn(&Path) -> bool,
) -> (Vec<PathBuf>, Vec<PathBuf>) {
    let removed = tracked
        .keys()
        .filter(|path| match scanned.get(*path) {
            None => true,
            Some(Some(identity)) => tracked[*path] != Some(*identity),
            Some(None) => false,
        })
        .cloned()
        .collect();
    let added = scanned
        .iter()
        .filter(|(path, identity)| {
            identity.is_some() && tracked.get(*path) != Some(*identity) && ready(path.as_path())
        })
        .map(|(path, _)| path.clone())
        .collect();
    (removed, added)
}
