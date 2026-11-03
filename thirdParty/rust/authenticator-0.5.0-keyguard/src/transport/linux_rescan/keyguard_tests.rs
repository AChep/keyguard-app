/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

use super::*;
use std::cell::Cell;
use std::io;

fn paths(names: &[&str]) -> Vec<PathBuf> {
    names.iter().map(PathBuf::from).collect()
}

fn scan(nodes: &[(&str, u64)]) -> Scan {
    nodes
        .iter()
        .map(|(path, inode)| {
            (
                PathBuf::from(path),
                Some(NodeIdentity {
                    device: 1,
                    inode: *inode,
                }),
            )
        })
        .collect()
}

#[test]
fn rescan_adds_new_nodes_and_removes_missing_ones() {
    let tracked = scan(&[("/dev/hidraw0", 1), ("/dev/hidraw1", 2)]);
    let scanned = scan(&[("/dev/hidraw1", 2), ("/dev/hidraw2", 3)]);
    let (removed, added) = diff_scan(&tracked, &scanned, |_| true);
    assert_eq!(removed, paths(&["/dev/hidraw0"]));
    assert_eq!(added, paths(&["/dev/hidraw2"]));
}

#[test]
fn rescan_retries_a_node_until_udev_grants_access() {
    let scanned = scan(&[("/dev/hidraw3", 1)]);
    let (removed, added) = diff_scan(&Scan::new(), &scanned, |_| false);
    assert!(removed.is_empty());
    assert!(added.is_empty());

    let (_, added) = diff_scan(&Scan::new(), &scanned, |_| true);
    assert_eq!(added, paths(&["/dev/hidraw3"]));
}

#[test]
fn rescan_keeps_tracked_nodes_without_reopening_them() {
    let tracked = scan(&[("/dev/hidraw4", 1)]);
    let (removed, added) = diff_scan(&tracked, &tracked, |path| {
        panic!("tracked node {} was checked again", path.display())
    });
    assert!(removed.is_empty());
    assert!(added.is_empty());
}

#[test]
fn rescan_replaces_a_node_at_the_same_path() {
    let tracked = scan(&[("/dev/hidraw4", 1)]);
    let scanned = scan(&[("/dev/hidraw4", 2)]);
    let (removed, added) = diff_scan(&tracked, &scanned, |_| true);
    assert_eq!(removed, paths(&["/dev/hidraw4"]));
    assert_eq!(added, removed);
}

#[test]
fn rescan_removes_replaced_node_while_waiting_for_access() {
    let tracked = scan(&[("/dev/hidraw4", 1)]);
    let scanned = scan(&[("/dev/hidraw4", 2)]);
    let (removed, added) = diff_scan(&tracked, &scanned, |_| false);
    assert_eq!(removed, paths(&["/dev/hidraw4"]));
    assert!(added.is_empty());
}

#[test]
fn metadata_failure_does_not_remove_or_add_a_node() {
    let tracked = scan(&[("/dev/hidraw0", 1)]);
    let scanned = paths(&["/dev/hidraw0", "/dev/hidraw1"])
        .into_iter()
        .map(|path| (path, None))
        .collect();
    let (removed, added) = diff_scan(&tracked, &scanned, |_| true);
    assert!(removed.is_empty());
    assert!(added.is_empty());
}

#[test]
fn initialization_retries_io_failure_then_stops_on_success() {
    let mut attempts = 0;
    let result = retry_initialization(
        || {
            attempts += 1;
            if attempts == 1 {
                Err(io::Error::from(io::ErrorKind::PermissionDenied).into())
            } else {
                Ok("initialized")
            }
        },
        &|| true,
    );
    assert_eq!(result, Some("initialized"));
    assert_eq!(attempts, 2);
}

#[test]
fn initialization_does_not_retry_known_non_fido_devices() {
    let mut attempts = 0;
    let result: Option<()> = retry_initialization(
        || {
            attempts += 1;
            Err(HIDError::DeviceNotSupported)
        },
        &|| true,
    );
    assert!(result.is_none());
    assert_eq!(attempts, 1);
}

#[test]
fn cancellation_stops_initialization_retries() {
    let alive = Cell::new(true);
    let mut attempts = 0;
    let result: Option<()> = retry_initialization(
        || {
            attempts += 1;
            alive.set(false);
            Err(io::Error::from(io::ErrorKind::PermissionDenied).into())
        },
        &|| alive.get(),
    );
    assert!(result.is_none());
    assert_eq!(attempts, 1);
}

#[test]
fn canceled_request_does_not_initialize_a_device() {
    let result: Option<()> =
        retry_initialization(|| panic!("initialized after cancellation"), &|| false);
    assert!(result.is_none());
}

#[test]
fn cancellation_during_initialization_discards_the_device() {
    let alive = Cell::new(true);
    let result = retry_initialization(
        || {
            alive.set(false);
            Ok(())
        },
        &|| alive.get(),
    );
    assert!(result.is_none());
}

#[test]
fn access_check_requires_an_existing_node() {
    let dir = env::temp_dir().join(format!("keyguard-access-{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let node = dir.join("hidraw");
    fs::write(&node, b"").unwrap();

    assert!(is_accessible(&node));
    assert!(!is_accessible(&dir.join("missing")));

    fs::remove_dir_all(&dir).unwrap();
}
