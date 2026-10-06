use crate::{Error, protocol::Transport};
use hidapi::{HidApi, HidDevice};
use std::sync::Mutex;

const YUBICO_VENDOR_ID: u16 = 0x1050;

// `HidApi::new` enumerates every HID interface; rescans filter by vendor inside the backend.
static API: Mutex<Option<HidApi>> = Mutex::new(None);

pub(crate) fn open() -> Result<impl Transport, Error> {
    let mut api = API.lock().map_err(|_| Error::Internal)?;
    let api: &HidApi = match &mut *api {
        Some(api) => {
            api.reset_devices().map_err(|_| Error::Io)?;
            api.add_devices(YUBICO_VENDOR_ID, 0)
                .map_err(|_| Error::Io)?;
            api
        }
        empty => empty.insert(HidApi::new().map_err(|_| Error::Io)?),
    };
    let mut devices = api.device_list().filter(|device| {
        device.vendor_id() == YUBICO_VENDOR_ID && device.usage_page() == 1 && device.usage() == 6
    });
    let device = devices.next().ok_or(Error::NoDevice)?;
    // Never select an arbitrary key for a destructive configuration write.
    if devices.next().is_some() {
        return Err(Error::MultipleDevices);
    }
    device
        .open_device(api)
        .map(HidTransport)
        .map_err(|_| Error::Io)
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
