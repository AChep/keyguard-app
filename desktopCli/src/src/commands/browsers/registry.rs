//! The Windows registry keys that point browsers at Keyguard's manifests,
//! behind a trait so the installer runs in tests on any OS.
use anyhow::Result;

/// Default values of keys under `HKEY_CURRENT_USER`.
pub(crate) trait Registry {
    /// The default value of `key`, or `None` if the key or the value is
    /// missing, or not a string.
    fn read(&self, key: &str) -> Result<Option<String>>;
    /// Creates `key` if needed and sets its default value.
    fn write(&mut self, key: &str, value: &str) -> Result<()>;
    /// Deletes `key`, which must have no subkeys. A missing key is not an error.
    fn delete(&mut self, key: &str) -> Result<()>;
}

/// The registry of the current user.
#[cfg(windows)]
pub(crate) fn current_user() -> impl Registry {
    windows::CurrentUser
}

/// Only Windows has a registry; elsewhere no location names a key.
#[cfg(not(windows))]
pub(crate) fn current_user() -> impl Registry {
    Unavailable
}

#[cfg(not(windows))]
struct Unavailable;

#[cfg(not(windows))]
impl Registry for Unavailable {
    fn read(&self, _key: &str) -> Result<Option<String>> {
        Ok(None)
    }

    fn write(&mut self, _key: &str, _value: &str) -> Result<()> {
        anyhow::bail!("only Windows has a registry")
    }

    fn delete(&mut self, _key: &str) -> Result<()> {
        Ok(())
    }
}

#[cfg(windows)]
mod windows {
    use super::Registry;
    use anyhow::{Result, ensure};
    use std::ptr::{null, null_mut};
    use windows_sys::Win32::{
        Foundation::{
            ERROR_FILE_NOT_FOUND, ERROR_MORE_DATA, ERROR_SUCCESS, ERROR_UNSUPPORTED_TYPE,
            WIN32_ERROR,
        },
        System::Registry::{
            HKEY_CURRENT_USER, REG_SZ, RRF_RT_REG_SZ, RegDeleteKeyW, RegGetValueW, RegSetKeyValueW,
        },
    };

    pub(super) struct CurrentUser;

    fn wide(value: &str) -> Vec<u16> {
        value.encode_utf16().chain(Some(0)).collect()
    }

    fn check(status: WIN32_ERROR, action: &str, key: &str) -> Result<()> {
        ensure!(
            status == ERROR_SUCCESS,
            "could not {action} HKEY_CURRENT_USER\\{key}: {}",
            std::io::Error::from_raw_os_error(status as i32)
        );
        Ok(())
    }

    impl Registry for CurrentUser {
        fn read(&self, key: &str) -> Result<Option<String>> {
            let name = wide(key);
            let mut buffer: Vec<u16> = Vec::new();
            // The value may grow between the two calls.
            for _ in 0..4 {
                let mut size = u32::try_from(buffer.len() * 2)?;
                let data = if buffer.is_empty() {
                    null_mut()
                } else {
                    buffer.as_mut_ptr().cast()
                };
                // SAFETY: `name` is NUL-terminated; `data` is null, to ask for
                // the size, or `buffer`, which holds `size` bytes.
                let status = unsafe {
                    RegGetValueW(
                        HKEY_CURRENT_USER,
                        name.as_ptr(),
                        null(),
                        RRF_RT_REG_SZ,
                        null_mut(),
                        data,
                        &mut size,
                    )
                };
                match status {
                    // Unsupported: the value is not a string, such as a DWORD.
                    ERROR_FILE_NOT_FOUND | ERROR_UNSUPPORTED_TYPE => return Ok(None),
                    ERROR_MORE_DATA => buffer = vec![0; (size as usize).div_ceil(2)],
                    ERROR_SUCCESS if buffer.is_empty() => {
                        buffer = vec![0; (size as usize).div_ceil(2).max(1)];
                    }
                    ERROR_SUCCESS => {
                        let value = &buffer[..(size as usize / 2).min(buffer.len())];
                        let value = value.split(|&unit| unit == 0).next().unwrap_or_default();
                        return Ok(Some(String::from_utf16(value)?));
                    }
                    status => check(status, "read", key)?,
                }
            }
            anyhow::bail!("could not read HKEY_CURRENT_USER\\{key}: the value keeps changing")
        }

        fn write(&mut self, key: &str, value: &str) -> Result<()> {
            let name = wide(key);
            let data = wide(value);
            let size = u32::try_from(data.len() * 2)?;
            // SAFETY: `name` and `data` are NUL-terminated, and `data` holds
            // `size` bytes. RegSetKeyValueW creates the key if needed.
            let status = unsafe {
                RegSetKeyValueW(
                    HKEY_CURRENT_USER,
                    name.as_ptr(),
                    null(),
                    REG_SZ,
                    data.as_ptr().cast(),
                    size,
                )
            };
            check(status, "write", key)
        }

        fn delete(&mut self, key: &str) -> Result<()> {
            let name = wide(key);
            // SAFETY: `name` is NUL-terminated.
            let status = unsafe { RegDeleteKeyW(HKEY_CURRENT_USER, name.as_ptr()) };
            if status == ERROR_FILE_NOT_FOUND {
                return Ok(());
            }
            check(status, "delete", key)
        }
    }

    #[cfg(test)]
    mod tests {
        use super::{super::current_user, *};
        use windows_sys::Win32::System::Registry::REG_DWORD;

        /// The parent of the test's keys, which the first write creates.
        const TESTS_KEY: &str = "Software\\Keyguard\\Tests";

        /// Deletes the test's key, then its parent unless another test's key
        /// is in it, even if the test fails.
        struct Cleanup(String);

        impl Drop for Cleanup {
            fn drop(&mut self) {
                let mut registry = current_user();
                let _ = registry.delete(&self.0);
                // Fails while the key has subkeys.
                let _ = registry.delete(TESTS_KEY);
            }
        }

        #[test]
        fn the_current_user_registry_reads_writes_and_deletes_default_values() {
            let nanos = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let key = format!("{TESTS_KEY}\\{}-{nanos}", std::process::id());
            let _cleanup = Cleanup(key.clone());
            let mut registry = current_user();
            assert_eq!(registry.read(&key).unwrap(), None);

            let path = "C:\\Users\\Zoë\\manifest.json";
            registry.write(&key, path).unwrap();
            assert_eq!(registry.read(&key).unwrap().as_deref(), Some(path));
            let path = "C:\\Users\\Zoë\\AppData\\Local\\manifest.json";
            registry.write(&key, path).unwrap();
            assert_eq!(registry.read(&key).unwrap().as_deref(), Some(path));

            // A value that isn't a string reads as missing, and a write
            // replaces it.
            let name = wide(&key);
            let dword = 1u32.to_ne_bytes();
            // SAFETY: `name` is NUL-terminated, and `dword` holds 4 bytes.
            let status = unsafe {
                RegSetKeyValueW(
                    HKEY_CURRENT_USER,
                    name.as_ptr(),
                    null(),
                    REG_DWORD,
                    dword.as_ptr().cast(),
                    4,
                )
            };
            assert_eq!(status, ERROR_SUCCESS);
            assert_eq!(registry.read(&key).unwrap(), None);
            registry.write(&key, path).unwrap();
            assert_eq!(registry.read(&key).unwrap().as_deref(), Some(path));

            registry.delete(&key).unwrap();
            assert_eq!(registry.read(&key).unwrap(), None);
            registry.delete(&key).unwrap();
            // A key under a missing key is missing too.
            registry.delete(&format!("{key}\\Missing")).unwrap();
        }
    }
}

/// A registry in memory, for tests.
#[cfg(test)]
#[derive(Debug, Default)]
pub(crate) struct Memory(pub std::collections::BTreeMap<String, String>);

#[cfg(test)]
impl Registry for Memory {
    fn read(&self, key: &str) -> Result<Option<String>> {
        Ok(self.0.get(key).cloned())
    }

    fn write(&mut self, key: &str, value: &str) -> Result<()> {
        self.0.insert(key.to_owned(), value.to_owned());
        Ok(())
    }

    fn delete(&mut self, key: &str) -> Result<()> {
        self.0.remove(key);
        Ok(())
    }
}
