use crate::{Error, Failure, Operation, Status, Target, registered_status};
use std::fs;
use std::io::{Read, Write};
use std::path::Path;
#[cfg(target_os = "linux")]
use std::path::PathBuf;
use tempfile::NamedTempFile;

#[cfg(target_os = "linux")]
pub(crate) fn execute(operation: Operation, target: &Target<'_>) -> Result<Status, Failure> {
    if std::env::var_os("FLATPAK_ID").is_some() {
        return Err(Failure::new(
            Error::Unavailable,
            "Flatpak must use the background portal",
        ));
    }
    operate(&config_home()?, operation, target)
}

/// `$XDG_CONFIG_HOME`; inside Flatpak, the sandbox's own configuration directory.
#[cfg(target_os = "linux")]
pub(crate) fn config_home() -> Result<PathBuf, Failure> {
    std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".config")))
        .filter(|path| path.is_absolute())
        .ok_or_else(|| {
            Failure::new(
                Error::Unavailable,
                "no absolute user configuration directory",
            )
        })
}

fn operate(root: &Path, operation: Operation, target: &Target<'_>) -> Result<Status, Failure> {
    if operation == Operation::Settings {
        return Err(Failure::new(
            Error::Unavailable,
            "this desktop has no standard startup settings action",
        ));
    }
    let path = root
        .join("autostart")
        .join(format!("{}-autostart.desktop", target.id));
    let current = read_entry(&path)?;
    let expected = entry(target)?;
    let owned = current
        .as_ref()
        .is_none_or(|text| value(text, "X-Keyguard-LaunchAtLogin") == Some(target.id));
    if !owned {
        return if operation == Operation::Status {
            Ok(Status::Unknown)
        } else {
            Err(Failure::new(
                Error::Conflict,
                "an unrelated autostart entry already uses this identifier",
            ))
        };
    }
    match operation {
        Operation::Enable => {
            // GLib and systemd's xdg-autostart-generator look up the program without
            // undoing the `%%` escape, so such an entry would never launch.
            if target.executable.contains('%') {
                return Err(Failure::new(
                    Error::InvalidArgument,
                    "autostart cannot launch a path that contains '%'",
                ));
            }
            if !Path::new(target.executable).is_file() {
                return Err(Failure::new(Error::InvalidArgument, "launcher is missing"));
            }
            if current.as_deref() != Some(expected.as_str()) {
                replace_entry(&path, expected.as_bytes(), persist)?;
            }
            Ok(Status::Enabled)
        }
        Operation::Disable => {
            if current.is_some() {
                fs::remove_file(path)?;
            }
            Ok(Status::Disabled)
        }
        // Settings returned above.
        Operation::Status | Operation::Settings => Ok(match current {
            None => Status::Disabled,
            // Enabling rewrites the entry without these flags, so the app may still change it.
            Some(text)
                if value(&text, "Hidden") == Some("true")
                    || value(&text, "X-GNOME-Autostart-enabled") == Some("false") =>
            {
                Status::Disabled
            }
            Some(text) => {
                let exec = value(&text, "Exec");
                let program = exec.and_then(exec_program).unwrap_or_default();
                match registered_status(
                    Path::new(&program),
                    program == target.executable,
                    exec == value(&expected, "Exec"),
                ) {
                    Status::Enabled
                        if value(&text, "OnlyShowIn").is_some()
                            || value(&text, "NotShowIn").is_some() =>
                    {
                        Status::Unknown
                    }
                    status => status,
                }
            }
        }),
    }
}

/// Atomically replaces `path` with the staged file.
fn persist(file: NamedTempFile, path: &Path) -> std::io::Result<()> {
    file.persist(path).map(drop).map_err(|error| error.error)
}

// `commit` is injectable so tests can fail the atomic replace; an uncommitted staged file
// is deleted on drop.
fn replace_entry(
    path: &Path,
    contents: &[u8],
    commit: impl FnOnce(NamedTempFile, &Path) -> std::io::Result<()>,
) -> Result<(), Failure> {
    let parent = path
        .parent()
        .ok_or_else(|| Failure::new(Error::Internal, "invalid entry path"))?;
    fs::create_dir_all(parent)?;
    // A unique random name with an exclusive create, readable only by the owner.
    let mut file = NamedTempFile::new_in(parent)?;
    file.write_all(contents)?;
    file.as_file().sync_all()?;
    commit(file, path)?;
    Ok(())
}

fn read_entry(path: &Path) -> Result<Option<String>, Failure> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
        Ok(metadata) if !metadata.is_file() || metadata.len() > 65_536 => {
            return Err(Failure::new(
                Error::Conflict,
                "autostart entry is not a regular bounded file",
            ));
        }
        Ok(_) => {}
    }
    let mut text = String::new();
    fs::File::open(path)?
        .take(65_537)
        .read_to_string(&mut text)?;
    if text.len() > 65_536 {
        return Err(Failure::new(
            Error::Conflict,
            "autostart entry is too large",
        ));
    }
    Ok(Some(text))
}

fn value<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    let mut in_entry = false;
    let mut found = None;
    for line in text.lines().map(str::trim) {
        if line.starts_with('[') {
            in_entry = line == "[Desktop Entry]";
        }
        if in_entry
            && let Some((name, value)) = line.split_once('=')
            && name == key
        {
            if found.is_some() {
                return None;
            }
            found = Some(value);
        }
    }
    found
}

fn entry(target: &Target<'_>) -> Result<String, Failure> {
    if !Path::new(target.executable).is_absolute() {
        return Err(Failure::new(
            Error::InvalidArgument,
            "launcher must be an absolute installed path",
        ));
    }
    let command = std::iter::once(target.executable)
        .chain(target.arguments.iter().copied())
        .map(quote_argument)
        .collect::<Vec<_>>()
        .join(" ");
    // Desktop-entry value escaping is applied after Exec token quoting.
    let name = escape_value(target.name);
    let command = escape_value(&command);
    Ok(format!(
        "[Desktop Entry]\nType=Application\nName={name}\nExec={command}\nTerminal=false\nX-Keyguard-LaunchAtLogin={}\n",
        target.id
    ))
}
fn quote_argument(argument: &str) -> String {
    let mut result = String::from("\"");
    for ch in argument.chars() {
        match ch {
            '\\' | '"' | '`' | '$' => {
                result.push('\\');
                result.push(ch);
            }
            '%' => result.push_str("%%"),
            _ => result.push(ch),
        }
    }
    result.push('"');
    result
}
/// The program of an `Exec` value: the first token, after value unescaping and
/// Exec quoting are undone.
fn exec_program(exec: &str) -> Option<String> {
    let mut unescaped = String::new();
    let mut chars = exec.chars();
    while let Some(ch) = chars.next() {
        unescaped.push(if ch == '\\' {
            match chars.next()? {
                's' => ' ',
                'n' => '\n',
                't' => '\t',
                'r' => '\r',
                other => other,
            }
        } else {
            ch
        });
    }
    let command = unescaped.trim_start();
    let program = if let Some(quoted) = command.strip_prefix('"') {
        let mut program = String::new();
        let mut chars = quoted.chars();
        loop {
            match chars.next()? {
                '"' => break,
                '\\' => program.push(chars.next()?),
                ch => program.push(ch),
            }
        }
        program
    } else {
        command.split([' ', '\t']).next()?.to_owned()
    };
    Some(program.replace("%%", "%"))
}
fn escape_value(value: &str) -> String {
    value
        .replace('\\', "\\\\")
        .replace('\n', "\\n")
        .replace('\r', "\\r")
        .replace('\t', "\\t")
}

#[cfg(test)]
#[path = "xdg_tests.rs"]
mod lifecycle_tests;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn quotes_exec_without_shell_expansion_or_field_codes() {
        assert_eq!(quote_argument("a b$`\\\"%f"), "\"a b\\$\\`\\\\\\\"%%f\"");
        assert_eq!(quote_argument(""), "\"\"");
        assert_eq!(escape_value("a\nb\\c"), "a\\nb\\\\c");
    }
    #[test]
    fn reads_back_the_program_it_wrote() {
        for program in ["/opt/应用 with spaces/app", "/a b$`\\\"%f", "/new\nline"] {
            let exec = escape_value(&format!("{} \"--x\"", quote_argument(program)));
            assert_eq!(exec_program(&exec).as_deref(), Some(program));
        }
        assert_eq!(
            exec_program("/usr/bin/app --x").as_deref(),
            Some("/usr/bin/app")
        );
        assert_eq!(exec_program("\"/unterminated"), None);
    }
    #[test]
    fn manages_only_owned_entry_and_observes_external_disable() {
        let temp = tempfile::tempdir().unwrap();
        let launcher = temp.path().join("应用 with spaces");
        fs::write(&launcher, "fixture").unwrap();
        let request = format!(
            "xdg\0com.example.test\0Example\0{}\0\0--launch-at-login",
            launcher.display()
        );
        let target = Target::parse(&request).unwrap();
        assert_eq!(
            operate(temp.path(), Operation::Status, &target).unwrap(),
            Status::Disabled
        );
        assert_eq!(
            operate(temp.path(), Operation::Enable, &target).unwrap(),
            Status::Enabled
        );
        let path = temp
            .path()
            .join("autostart/com.example.test-autostart.desktop");
        let content = fs::read_to_string(&path).unwrap();
        fs::write(&path, format!("{content}Hidden=true\n")).unwrap();
        assert_eq!(
            operate(temp.path(), Operation::Status, &target).unwrap(),
            Status::Disabled
        );
        assert_eq!(
            operate(temp.path(), Operation::Disable, &target).unwrap(),
            Status::Disabled
        );
        fs::write(&path, "[Desktop Entry]\nName=Other\n").unwrap();
        assert_eq!(
            operate(temp.path(), Operation::Enable, &target)
                .unwrap_err()
                .code,
            Error::Conflict
        );
        assert_eq!(
            operate(temp.path(), Operation::Disable, &target)
                .unwrap_err()
                .code,
            Error::Conflict
        );
        assert!(path.is_file());
    }
}
