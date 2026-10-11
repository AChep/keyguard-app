use crate::{Error, protocol::Transport};
use hidapi::{HidApi, HidDevice};
use std::sync::Mutex;

const YUBICO_VENDOR_ID: u16 = 0x1050;

// `HidApi::new` enumerates every HID interface; rescans filter by vendor inside the backend.
static API: Mutex<Option<HidApi>> = Mutex::new(None);

pub(crate) fn open() -> Result<impl Transport, Error> {
    // The caller holds its OperationGuard through discovery and protocol execution.
    with_cached_api(&API, |cached| {
        let api: &HidApi = match cached {
            Some(api) => {
                api.reset_devices().map_err(|_| Error::Io)?;
                api.add_devices(YUBICO_VENDOR_ID, 0)
                    .map_err(|_| Error::Io)?;
                api
            }
            empty => empty.insert(HidApi::new().map_err(|_| Error::Io)?),
        };
        open_device(api).map(HidTransport)
    })
}

// Move the context out so no HID calls run under the mutex, then put it back.
// A panic discards it instead of poisoning the mutex.
fn with_cached_api<A, T>(
    cache: &Mutex<Option<A>>,
    open: impl FnOnce(&mut Option<A>) -> Result<T, Error>,
) -> Result<T, Error> {
    let mut api = cache.lock().map_err(|_| Error::Internal)?.take();
    let result = open(&mut api);
    *cache.lock().map_err(|_| Error::Internal)? = api;
    result
}

fn open_device(api: &HidApi) -> Result<HidDevice, Error> {
    let mut devices = api.device_list().filter(|device| {
        device.vendor_id() == YUBICO_VENDOR_ID && device.usage_page() == 1 && device.usage() == 6
    });
    let device = devices.next().ok_or(Error::NoDevice)?;
    // Never select an arbitrary key for a destructive configuration write.
    if devices.next().is_some() {
        return Err(Error::MultipleDevices);
    }
    device.open_device(api).map_err(|_| Error::Io)
}
struct HidTransport(HidDevice);
impl Transport for HidTransport {
    fn read(&mut self) -> Result<[u8; 8], Error> {
        // HIDAPI includes a report-ID byte, even for unnumbered feature reports.
        let mut report = zeroize::Zeroizing::new([0; 9]);
        let size = self
            .0
            .get_feature_report(report.as_mut())
            .map_err(|_| Error::Io)?;
        if size != report.len() {
            return Err(Error::Protocol);
        }
        let mut data = [0; 8];
        data.copy_from_slice(&report[1..]);
        Ok(data)
    }
    fn write(&mut self, data: &[u8; 8]) -> Result<(), Error> {
        let mut report = zeroize::Zeroizing::new([0; 9]);
        report[1..].copy_from_slice(data);
        self.0
            .send_feature_report(report.as_ref())
            .map_err(|_| Error::Io)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use keyguard_ffi::{PanicHook, contained};

    #[test]
    fn successful_open_reuses_context_without_holding_the_cache_lock() {
        let cache = Mutex::new(None);
        for expected in 1..=2 {
            let device = with_cached_api(&cache, |api| {
                assert!(cache.try_lock().unwrap().is_none());
                let generation = api.get_or_insert(0);
                *generation += 1;
                assert_eq!(*generation, expected);
                Ok("device")
            });
            assert_eq!(device, Ok("device"));
            assert_eq!(*cache.lock().unwrap(), Some(expected));
        }
    }

    #[test]
    fn failed_open_keeps_the_context() {
        let cache = Mutex::new(None);
        let result = with_cached_api(&cache, |api| -> Result<(), Error> {
            *api = Some(1);
            Err(Error::NoDevice)
        });
        assert_eq!(result, Err(Error::NoDevice));
        assert_eq!(*cache.lock().unwrap(), Some(1));
    }

    #[test]
    fn panicking_open_discards_the_context() {
        let cache = Mutex::new(Some(1));
        let result = contained(PanicHook::Keep, || {
            with_cached_api(&cache, |_| -> Result<(), Error> {
                panic!("discovery or opening failed");
            })
        });
        assert!(result.is_err());
        assert!(!cache.is_poisoned());
        assert!(cache.lock().unwrap().is_none());
    }
}
