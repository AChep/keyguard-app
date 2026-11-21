//! The MSIX package's execution alias of its connector. Windows makes it
//! as an AppExecLink reparse point in `%LOCALAPPDATA%\Microsoft\WindowsApps`
//! when it installs the package. Only one installed package can own the
//! alias, such as the Store or the sideloaded build.
use std::path::Path;

/// `IO_REPARSE_TAG_APPEXECLINK`, [MS-FSCC] 2.1.2.1.
const APPEXECLINK: u32 = 0x8000_001B;

/// The only layout of the alias's data that [`parse`] knows.
const VERSION: u32 = 3;

/// What an alias starts.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct AppExecLink {
    /// The family name of the package that owns the alias.
    pub package_family: String,
    pub target: String,
}

/// Whether the alias whose reparse data is `data` starts `bundled`, the
/// connector of the package whose family is `package_family`. An alias of
/// an older version of the package starts a file in that version's folder,
/// which may be gone.
pub(crate) fn is_owned(data: &[u8], package_family: Option<&str>, bundled: &Path) -> bool {
    let Some(family) = package_family else {
        return false;
    };
    parse(data).is_some_and(|link| {
        link.package_family.eq_ignore_ascii_case(family)
            && is_same_path(Path::new(&link.target), bundled)
    })
}

/// Whether `a` and `b` name the same file, as Windows compares paths:
/// without case, and with either separator. On Windows, also once both are
/// resolved, as `Packaging::detect` compares the package's folder.
fn is_same_path(a: &Path, b: &Path) -> bool {
    fn key(path: &Path) -> Option<String> {
        let path = path.to_str()?.replace('/', "\\").to_lowercase();
        // A resolved path has the prefix of verbatim paths.
        Some(match path.strip_prefix(r"\\?\unc\") {
            Some(share) => format!(r"\\{share}"),
            None => path.strip_prefix(r"\\?\").unwrap_or(&path).to_owned(),
        })
    }
    let same = |a: &Path, b: &Path| key(a).is_some_and(|a| Some(a) == key(b));
    same(a, b)
        || (cfg!(windows)
            && std::fs::canonicalize(a)
                .and_then(|a| Ok((a, std::fs::canonicalize(b)?)))
                .is_ok_and(|(a, b)| same(&a, &b)))
}

/// Reads the reparse data of an AppExecLink, as `FSCTL_GET_REPARSE_POINT`
/// returns it. Microsoft documents the tag and the header of the buffer
/// (the tag, the length of the data, and two reserved bytes; [MS-FSCC]
/// 2.1.2.2), but not the data. Its layout here is the one that Windows
/// writes, as James Forshaw describes it in "Overview of Windows Execution
/// Aliases" (2019): a 32-bit version, 3, then NUL-terminated UTF-16LE
/// strings: the package ID, which is the package family name, the entry
/// point, which is the app user model ID, the executable, and the type of
/// the app. Data of another version may be laid out otherwise.
pub(crate) fn parse(data: &[u8]) -> Option<AppExecLink> {
    let u32_at = |bytes: &[u8], at: usize| {
        let bytes: [u8; 4] = bytes.get(at..at + 4)?.try_into().ok()?;
        Some(u32::from_le_bytes(bytes))
    };
    if u32_at(data, 0)? != APPEXECLINK {
        return None;
    }
    let length: [u8; 2] = data.get(4..6)?.try_into().ok()?;
    let body = data.get(8..8 + usize::from(u16::from_le_bytes(length)))?;
    if u32_at(body, 0)? != VERSION {
        return None;
    }
    let (units, rest) = body.get(4..)?.as_chunks::<2>();
    if !rest.is_empty() {
        return None;
    }
    let units: Vec<u16> = units.iter().map(|&unit| u16::from_le_bytes(unit)).collect();
    // The first three strings, each with its NUL.
    let mut strings = units.split(|&unit| unit == 0);
    let mut next = || {
        String::from_utf16(strings.next()?)
            .ok()
            .filter(|it| !it.is_empty())
    };
    let package_family = next()?;
    // The app user model ID.
    next()?;
    let link = AppExecLink {
        package_family,
        target: next()?,
    };
    (units.iter().filter(|&&unit| unit == 0).count() >= 3).then_some(link)
}

/// The reparse data of the alias at `path`, not of what it starts.
#[cfg(windows)]
pub(crate) fn reparse_data(path: &Path) -> std::io::Result<Vec<u8>> {
    use std::os::windows::{fs::OpenOptionsExt, io::AsRawHandle};
    use windows_sys::Win32::{
        Storage::FileSystem::{FILE_FLAG_BACKUP_SEMANTICS, FILE_FLAG_OPEN_REPARSE_POINT},
        System::{IO::DeviceIoControl, Ioctl::FSCTL_GET_REPARSE_POINT},
    };
    let file = std::fs::OpenOptions::new()
        .access_mode(0)
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS)
        .open(path)?;
    // MAXIMUM_REPARSE_DATA_BUFFER_SIZE.
    let mut buffer = vec![0u8; 16 * 1024];
    let mut returned = 0u32;
    // SAFETY: the handle is open, and `buffer` is writable for the length
    // that the call is given. Without an OVERLAPPED, the call completes
    // before it returns.
    let succeeded = unsafe {
        DeviceIoControl(
            file.as_raw_handle(),
            FSCTL_GET_REPARSE_POINT,
            std::ptr::null(),
            0,
            buffer.as_mut_ptr().cast(),
            buffer.len() as u32,
            &mut returned,
            std::ptr::null_mut(),
        )
    } != 0;
    if !succeeded {
        return Err(std::io::Error::last_os_error());
    }
    buffer.truncate(returned as usize);
    Ok(buffer)
}

/// Only Windows has aliases.
#[cfg(not(windows))]
pub(crate) fn reparse_data(_: &Path) -> std::io::Result<Vec<u8>> {
    Err(std::io::ErrorKind::Unsupported.into())
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    const FAMILY: &str = "Keyguard_abcdefghjkmnp";

    /// The reparse data of an alias, built by hand from the layout that
    /// [`parse`] describes.
    pub(crate) fn app_exec_link(strings: &[&str]) -> Vec<u8> {
        let mut body = 3u32.to_le_bytes().to_vec();
        for string in strings {
            for unit in string.encode_utf16().chain([0]) {
                body.extend(unit.to_le_bytes());
            }
        }
        let mut data = APPEXECLINK.to_le_bytes().to_vec();
        data.extend(u16::try_from(body.len()).unwrap().to_le_bytes());
        data.extend([0, 0]);
        data.extend(body);
        data
    }

    /// The alias of the package of `family` that starts `target`.
    pub(crate) fn alias_of(family: &str, target: &Path) -> Vec<u8> {
        app_exec_link(&[
            family,
            &format!("{family}!Keyguard"),
            target.to_str().unwrap(),
            "0",
        ])
    }

    /// The connector in the folder of `version` of the package of `family`.
    fn package_host(family: &str, version: &str) -> String {
        let (name, publisher) = family.split_once('_').unwrap();
        format!(
            "C:\\Program Files\\WindowsApps\\{name}_{version}_x64__{publisher}\\app\\resources\\keyguard-browser-host.exe"
        )
    }

    /// The alias of the connector of version 2 of the package of `family`.
    fn alias_of_version_2(family: &str) -> Vec<u8> {
        alias_of(family, Path::new(&package_host(family, "2.0.0.0")))
    }

    #[test]
    fn reads_what_an_alias_starts() {
        // "A_b", "A_b!App", "C:\x.exe", "0", byte by byte.
        let data = [
            0x1b, 0x00, 0x00, 0x80, // IO_REPARSE_TAG_APPEXECLINK
            0x32, 0x00, // 50 bytes of data
            0x00, 0x00, // reserved
            0x03, 0x00, 0x00, 0x00, // version 3
            b'A', 0, b'_', 0, b'b', 0, 0, 0, // package family name
            b'A', 0, b'_', 0, b'b', 0, b'!', 0, b'A', 0, b'p', 0, b'p', 0, 0, 0, // AUMID
            b'C', 0, b':', 0, b'\\', 0, b'x', 0, // target
            b'.', 0, b'e', 0, b'x', 0, b'e', 0, 0, 0, // of the alias
            b'0', 0, 0, 0, // type of the app
        ];
        assert_eq!(
            data[..],
            app_exec_link(&["A_b", "A_b!App", "C:\\x.exe", "0"])
        );
        assert_eq!(
            parse(&data),
            Some(AppExecLink {
                package_family: "A_b".into(),
                target: "C:\\x.exe".into(),
            })
        );
        // Without the type of the app, too.
        assert!(parse(&app_exec_link(&["A_b", "A_b!App", "C:\\x.exe"])).is_some());
    }

    #[test]
    fn other_reparse_points_are_not_aliases() {
        let alias = alias_of_version_2(FAMILY);
        assert!(parse(&alias).is_some());
        // A symbolic link: IO_REPARSE_TAG_SYMLINK.
        let mut link = alias.clone();
        link[..4].copy_from_slice(&0xA000_000Cu32.to_le_bytes());
        // An older or a newer layout, data cut short, a string without its
        // NUL, and an empty file.
        let mut version = alias.clone();
        version[8..12].copy_from_slice(&2u32.to_le_bytes());
        let mut newer = alias.clone();
        newer[8..12].copy_from_slice(&4u32.to_le_bytes());
        let short = alias[..alias.len() - 1].to_vec();
        let unterminated = {
            let mut data = app_exec_link(&["A_b", "A_b!App"]);
            data.extend("C:".encode_utf16().flat_map(u16::to_le_bytes));
            let length = u16::try_from(data.len() - 8).unwrap();
            data[4..6].copy_from_slice(&length.to_le_bytes());
            data
        };
        for data in [link, version, newer, short, unterminated, Vec::new()] {
            assert_eq!(parse(&data), None, "{data:?}");
        }
        assert_eq!(parse(&app_exec_link(&["", "A_b!App", "C:\\x.exe"])), None);
    }

    #[test]
    fn an_alias_belongs_to_one_package_family() {
        let host = package_host(FAMILY, "2.0.0.0");
        let bundled = Path::new(&host);
        let alias = alias_of_version_2(FAMILY);
        assert!(is_owned(&alias, Some(FAMILY), bundled));
        // Windows compares family names and paths without case, and takes
        // either separator. A resolved path has the verbatim prefix.
        assert!(is_owned(&alias, Some(&FAMILY.to_uppercase()), bundled));
        for host in [
            host.to_uppercase(),
            host.replace('\\', "/"),
            format!("\\\\?\\{host}"),
        ] {
            assert!(is_owned(&alias, Some(FAMILY), Path::new(&host)), "{host}");
        }
        // The other build's package, or no package identity.
        assert!(!is_owned(
            &alias,
            Some("KeyguardSideload_abcdefghjkmnp"),
            bundled
        ));
        assert!(!is_owned(&alias, None, bundled));
        // The alias of the version of the package before an update, whose
        // folder Windows removes.
        let stale = package_host(FAMILY, "1.0.0.0");
        let alias = alias_of(FAMILY, Path::new(&stale));
        assert!(!is_owned(&alias, Some(FAMILY), bundled));
        // A layout that this build doesn't know.
        let mut newer = alias_of_version_2(FAMILY);
        newer[8..12].copy_from_slice(&4u32.to_le_bytes());
        assert!(!is_owned(&newer, Some(FAMILY), bundled));
        // An ordinary file.
        assert!(!is_owned(b"MZ", Some(FAMILY), bundled));
    }
}
