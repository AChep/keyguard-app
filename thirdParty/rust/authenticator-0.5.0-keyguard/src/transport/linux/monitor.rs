/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#[cfg(not(test))]
use crate::transport::device_selector::DeviceSelectorEvent;
use crate::transport::linux_rescan::{
    diff_scan, is_accessible, is_flatpak, wait_for_rescan, NodeIdentity, Scan,
};
#[cfg(test)]
use keyguard_tests::DeviceSelectorEvent;
use libc::{c_int, c_short, c_ulong};
use libudev::EventType;
use runloop::RunLoop;
use std::collections::HashMap;
use std::error::Error;
use std::io;
use std::os::unix::io::AsRawFd;
use std::path::PathBuf;
use std::sync::{mpsc::Sender, Arc};

#[cfg(test)]
#[path = "monitor/keyguard_tests.rs"]
mod keyguard_tests;

const UDEV_SUBSYSTEM: &str = "hidraw";
const POLLIN: c_short = 0x0001;
const POLL_TIMEOUT: c_int = 100;

struct TrackedDevice {
    identity: Option<NodeIdentity>,
    runloop: RunLoop,
}

fn poll(fds: &mut Vec<::libc::pollfd>) -> io::Result<()> {
    let nfds = fds.len() as c_ulong;

    let rv = unsafe { ::libc::poll((fds[..]).as_mut_ptr(), nfds, POLL_TIMEOUT) };

    if rv < 0 {
        Err(io::Error::from_raw_os_error(rv))
    } else {
        Ok(())
    }
}

pub struct Monitor<F>
where
    F: Fn(PathBuf, Sender<DeviceSelectorEvent>, Sender<crate::StatusUpdate>, &dyn Fn() -> bool)
        + Sync,
{
    runloops: HashMap<PathBuf, TrackedDevice>,
    new_device_cb: Arc<F>,
    selector_sender: Sender<DeviceSelectorEvent>,
    status_sender: Sender<crate::StatusUpdate>,
}

impl<F> Monitor<F>
where
    F: Fn(PathBuf, Sender<DeviceSelectorEvent>, Sender<crate::StatusUpdate>, &dyn Fn() -> bool)
        + Send
        + Sync
        + 'static,
{
    pub fn new(
        new_device_cb: F,
        selector_sender: Sender<DeviceSelectorEvent>,
        status_sender: Sender<crate::StatusUpdate>,
    ) -> Self {
        Self {
            runloops: HashMap::new(),
            new_device_cb: Arc::new(new_device_cb),
            selector_sender,
            status_sender,
        }
    }

    pub fn run(&mut self, alive: &dyn Fn() -> bool) -> Result<(), Box<dyn Error>> {
        let result = self.run_inner(alive);
        self.remove_all_devices();
        result
    }

    fn run_inner(&mut self, alive: &dyn Fn() -> bool) -> Result<(), Box<dyn Error>> {
        let ctx = libudev::Context::new()?;
        // Keyguard: sandbox udev events are not reliable across runtimes. Use
        // polling alone there, so an event cannot duplicate a scanned device.
        if is_flatpak() {
            self.run_polling(alive, || scan(&ctx));
            return Ok(());
        }

        self.add_devices(scan(&ctx)?);

        let mut monitor = libudev::Monitor::new(&ctx)?;
        monitor.match_subsystem(UDEV_SUBSYSTEM)?;

        // Start listening for new devices.
        let mut socket = monitor.listen()?;
        let mut fds = vec![::libc::pollfd {
            fd: socket.as_raw_fd(),
            events: POLLIN,
            revents: 0,
        }];

        while alive() {
            // Wait for new events, break on failure.
            poll(&mut fds)?;

            if let Some(event) = socket.receive_event() {
                self.process_event(&event);
            }
        }

        Ok(())
    }

    fn process_event(&mut self, event: &libudev::Event) {
        let path = event.device().devnode().map(|dn| dn.to_owned());

        match (event.event_type(), path) {
            (EventType::Add, Some(path)) => {
                let identity = NodeIdentity::read(&path);
                self.add_devices(std::iter::once((path, identity)).collect());
            }
            (EventType::Remove, Some(path)) => {
                self.remove_device(&path);
            }
            _ => { /* ignore other types and failures */ }
        }
    }

    fn run_polling(
        &mut self,
        alive: &dyn Fn() -> bool,
        mut scan: impl FnMut() -> Result<Scan, Box<dyn Error>>,
    ) {
        while alive() {
            match scan() {
                Ok(scanned) => self.rescan(scanned),
                // Preserve the current snapshot on a transient enumeration failure.
                Err(error) => warn!("Could not rescan security keys: {}", error),
            }
            if !wait_for_rescan(alive) {
                break;
            }
        }
    }

    fn rescan(&mut self, scanned: Scan) {
        let tracked = self
            .runloops
            .iter()
            .map(|(path, device)| (path.clone(), device.identity))
            .collect();
        let (removed, added) = diff_scan(&tracked, &scanned, is_accessible);

        for path in removed {
            self.remove_device(&path);
        }
        self.add_devices(
            added
                .into_iter()
                .map(|path| {
                    let identity = scanned[&path];
                    (path, identity)
                })
                .collect(),
        );
    }

    fn add_devices(&mut self, mut devices: Scan) {
        // Deduplicate before notifying the selector as well as before spawning.
        devices.retain(|path, _| !self.runloops.contains_key(path));
        if devices.is_empty() {
            return;
        }
        // Announce the whole batch before any worker can finish initialization.
        if self
            .selector_sender
            .send(DeviceSelectorEvent::DevicesAdded(
                devices.keys().cloned().collect(),
            ))
            .is_err()
        {
            // The selector closes once it chooses a key. Keep that worker alive.
            return;
        }
        for (path, identity) in devices {
            if let Err(error) = self.add_device(path.clone(), identity) {
                warn!("Could not start security key worker: {}", error);
                self.remove_device(&path);
            }
        }
    }

    fn add_device(&mut self, path: PathBuf, identity: Option<NodeIdentity>) -> io::Result<()> {
        let f = self.new_device_cb.clone();
        let key = path.clone();
        let selector_sender = self.selector_sender.clone();
        let status_sender = self.status_sender.clone();
        debug!("Adding device {}", path.to_string_lossy());

        let runloop = RunLoop::new(move |alive| {
            // A retry must not initialize a replacement under the old worker's
            // identity while the monitor is waiting for its next scan.
            let keep_alive = || {
                alive()
                    && !matches!((identity, NodeIdentity::read(&path)),
                        (Some(expected), Some(current)) if expected != current)
            };
            if keep_alive() {
                f(path.clone(), selector_sender, status_sender, &keep_alive);
            }
        })?;

        self.runloops
            .insert(key, TrackedDevice { identity, runloop });
        Ok(())
    }
}

impl<F> Monitor<F>
where
    F: Fn(PathBuf, Sender<DeviceSelectorEvent>, Sender<crate::StatusUpdate>, &dyn Fn() -> bool)
        + Sync,
{
    fn remove_device(&mut self, path: &PathBuf) {
        let _ = self
            .selector_sender
            .send(DeviceSelectorEvent::DeviceRemoved(path.clone()));

        debug!("Removing device {}", path.to_string_lossy());
        if let Some(device) = self.runloops.remove(path) {
            device.runloop.cancel();
        }
    }

    fn remove_all_devices(&mut self) {
        while !self.runloops.is_empty() {
            let path = self.runloops.keys().next().unwrap().clone();
            self.remove_device(&path);
        }
    }
}

impl<F> Drop for Monitor<F>
where
    F: Fn(PathBuf, Sender<DeviceSelectorEvent>, Sender<crate::StatusUpdate>, &dyn Fn() -> bool)
        + Sync,
{
    fn drop(&mut self) {
        self.remove_all_devices();
    }
}

fn scan(ctx: &libudev::Context) -> Result<Scan, Box<dyn Error>> {
    let mut enumerator = libudev::Enumerator::new(ctx)?;
    enumerator.match_subsystem(UDEV_SUBSYSTEM)?;
    Ok(enumerator
        .scan_devices()?
        .filter_map(|dev| {
            dev.devnode()
                .map(|path| (path.to_owned(), NodeIdentity::read(path)))
        })
        .collect())
}

pub fn get_property_linux(path: &PathBuf, prop_name: &str) -> io::Result<String> {
    let ctx = libudev::Context::new()?;

    let mut enumerator = libudev::Enumerator::new(&ctx)?;
    enumerator.match_subsystem(UDEV_SUBSYSTEM)?;

    // Iterate all existing devices, since we don't have a syspath
    // and libudev-rs doesn't implement opening by devnode.
    for dev in enumerator.scan_devices()? {
        if dev.devnode().is_some() && dev.devnode().unwrap() == path {
            debug!(
                "get_property_linux Querying property {} from {}",
                prop_name,
                dev.syspath().display()
            );

            let value = dev
                .attribute_value(prop_name)
                .ok_or(io::ErrorKind::Other)?
                .to_string_lossy();

            debug!("get_property_linux Fetched Result, {}={}", prop_name, value);
            return Ok(value.to_string());
        }
    }

    Err(io::Error::new(
        io::ErrorKind::Other,
        "Unable to find device",
    ))
}
