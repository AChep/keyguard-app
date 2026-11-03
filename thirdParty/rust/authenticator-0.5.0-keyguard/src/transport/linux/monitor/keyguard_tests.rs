/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

use super::*;
use std::cell::Cell;
use std::fs;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver};
use std::thread;
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(3);

// The library's mock device uses String IDs. Keep Linux's real PathBuf IDs
// here while observing only the notifications emitted by this monitor.
#[derive(Debug)]
pub enum DeviceSelectorEvent {
    DevicesAdded(Vec<PathBuf>),
    DeviceRemoved(PathBuf),
}
type Callback = Box<
    dyn Fn(PathBuf, Sender<DeviceSelectorEvent>, Sender<crate::StatusUpdate>, &dyn Fn() -> bool)
        + Send
        + Sync,
>;

struct Fixture {
    monitor: Monitor<Callback>,
    events: Receiver<DeviceSelectorEvent>,
    workers: Receiver<(PathBuf, bool)>,
}

impl Fixture {
    fn new(finish_immediately: bool) -> Self {
        let (selector, events) = channel();
        let (status, _) = channel();
        let (worker_tx, workers) = channel();
        let callback: Callback = Box::new(move |path, _, _, alive| {
            let _ = worker_tx.send((path.clone(), true));
            while !finish_immediately && alive() {
                thread::sleep(Duration::from_millis(1));
            }
            let _ = worker_tx.send((path, false));
        });
        Self {
            monitor: Monitor::new(callback, selector, status),
            events,
            workers,
        }
    }

    fn worker(&self, path: &Path, started: bool) {
        assert_eq!(
            self.workers.recv_timeout(TIMEOUT).unwrap(),
            (path.to_owned(), started)
        );
    }
}

struct Node(PathBuf);

impl Node {
    fn new() -> Self {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let dir = std::env::temp_dir().join(format!(
            "keyguard-monitor-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&dir).unwrap();
        let path = dir.join("hidraw");
        fs::write(&path, b"").unwrap();
        Self(path)
    }

    fn scan(&self) -> Scan {
        std::iter::once((self.0.clone(), NodeIdentity::read(&self.0))).collect()
    }

    fn replace(&self) {
        // Keep the old inode allocated so this test cannot reuse it.
        fs::rename(&self.0, self.0.with_extension("old")).unwrap();
        fs::write(&self.0, b"").unwrap();
    }
}

impl Drop for Node {
    fn drop(&mut self) {
        fs::remove_dir_all(self.0.parent().unwrap()).unwrap();
    }
}

#[test]
fn duplicate_discovery_announces_and_starts_only_one_worker() {
    let node = Node::new();
    let mut fixture = Fixture::new(false);
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
    fixture.monitor.add_devices(node.scan());
    fixture.monitor.rescan(node.scan());
    assert!(
        matches!(fixture.events.recv_timeout(TIMEOUT).unwrap(), DeviceSelectorEvent::DevicesAdded(paths) if paths == vec![node.0.clone()])
    );
    assert!(fixture.events.try_recv().is_err());
    assert!(fixture.workers.try_recv().is_err());
    assert_eq!(fixture.monitor.runloops.len(), 1);
}

#[test]
fn dropping_monitor_cancels_and_joins_its_workers() {
    let node = Node::new();
    let mut fixture = Fixture::new(false);
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
    drop(fixture.monitor);
    assert_eq!(fixture.workers.try_recv().unwrap(), (node.0.clone(), false));
    assert!(matches!(
        fixture.events.recv().unwrap(),
        DeviceSelectorEvent::DevicesAdded(_)
    ));
    assert!(
        matches!(fixture.events.recv().unwrap(), DeviceSelectorEvent::DeviceRemoved(path) if path == node.0)
    );
}

#[test]
fn run_cleans_up_workers_when_canceled() {
    let node = Node::new();
    let mut fixture = Fixture::new(false);
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
    let _ = fixture.monitor.run(&|| false);
    assert!(fixture.monitor.runloops.is_empty());
    fixture.worker(&node.0, false);
}

#[test]
fn failed_enumeration_keeps_workers_and_recovers_on_next_scan() {
    let node = Node::new();
    let mut fixture = Fixture::new(false);
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
    let alive = Cell::new(true);
    let mut scans = 0;
    let workers = &fixture.workers;
    fixture.monitor.run_polling(&|| alive.get(), || {
        scans += 1;
        if scans == 1 {
            Err(io::Error::other("temporary enumeration failure").into())
        } else {
            // No cancellation or replacement happened after the failed scan.
            assert!(workers.try_recv().is_err());
            alive.set(false);
            Ok(Scan::new())
        }
    });
    assert_eq!(scans, 2);
    fixture.worker(&node.0, false);
    assert!(fixture.monitor.runloops.is_empty());
}

#[test]
fn replacement_stops_old_worker_before_starting_new_one() {
    let node = Node::new();
    let mut fixture = Fixture::new(false);
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
    node.replace();
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, false);
    fixture.worker(&node.0, true);
    assert_eq!(fixture.monitor.runloops.len(), 1);
}

#[test]
fn completed_worker_stays_cached_until_node_is_replaced() {
    let node = Node::new();
    let mut fixture = Fixture::new(true);
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
    fixture.worker(&node.0, false);
    fixture.monitor.rescan(node.scan());
    assert!(fixture.workers.try_recv().is_err());
    node.replace();
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
    fixture.worker(&node.0, false);
}

#[test]
fn replacement_stops_initialization_retries_before_the_next_scan() {
    let node = Node::new();
    let mut fixture = Fixture::new(false);
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
    node.replace();
    fixture.worker(&node.0, false);
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
}

#[test]
fn closed_selector_does_not_cancel_the_selected_worker() {
    let node = Node::new();
    let other = Node::new();
    let mut fixture = Fixture::new(false);
    fixture.monitor.rescan(node.scan());
    fixture.worker(&node.0, true);
    drop(fixture.events);
    fixture.monitor.add_devices(other.scan());
    assert_eq!(fixture.monitor.runloops.len(), 1);
    assert!(fixture.workers.try_recv().is_err());
}
