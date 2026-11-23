use super::*;
use crate::Operation::{Disable, Enable, Status as Query};
use std::path::{Path, PathBuf};

struct Fixture {
    root: tempfile::TempDir,
    launcher: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let launcher = root.path().join("应用 fixture with spaces");
        fs::write(&launcher, "fixture executable").unwrap();
        Self { root, launcher }
    }

    fn path(&self) -> PathBuf {
        self.root
            .path()
            .join("autostart/com.example.test-autostart.desktop")
    }

    fn operate(&self, operation: Operation) -> Result<Status, Failure> {
        let target = Target {
            kind: "xdg",
            id: "com.example.test",
            name: "Fixture",
            executable: self.launcher.to_str().unwrap(),
            task_id: "",
            arguments: vec!["--launch-at-login", "", "a $ ` %f"],
        };
        operate(self.root.path(), operation, &target)
    }

    fn enable(&self) -> String {
        assert_eq!(self.operate(Enable).unwrap(), Status::Enabled);
        fs::read_to_string(self.path()).unwrap()
    }
}

#[test]
fn refresh_observes_external_changes_and_enable_repairs_them() {
    let fixture = Fixture::new();
    let enabled = fixture.enable();
    for (suffix, expected) in [
        ("Hidden=true\n", Status::Disabled),
        ("X-GNOME-Autostart-enabled=false\n", Status::Disabled),
        ("OnlyShowIn=GNOME;\n", Status::Unknown),
        ("NotShowIn=KDE;\n", Status::Unknown),
        // A duplicate key has no single value to launch.
        ("Exec=/old/launcher\n", Status::Stale),
    ] {
        let changed = format!("{enabled}{suffix}");
        fs::write(fixture.path(), &changed).unwrap();
        assert_eq!(fixture.operate(Query).unwrap(), expected);
        assert_eq!(fs::read_to_string(fixture.path()).unwrap(), changed);
        assert_eq!(fixture.operate(Enable).unwrap(), Status::Enabled);
        assert_eq!(fs::read_to_string(fixture.path()).unwrap(), enabled);
    }
}

#[test]
fn stale_only_when_the_entry_launches_a_missing_or_reconfigured_executable() {
    let fixture = Fixture::new();
    let enabled = fixture.enable();
    let exec = value(&enabled, "Exec").unwrap().to_owned();
    let program = |path: &Path| escape_value(&quote_argument(path.to_str().unwrap()));
    let other = fixture.root.path().join("other install");
    fs::write(&other, "other executable").unwrap();
    let missing = fixture.root.path().join("missing install");
    for (replacement, expected) in [
        (program(&missing), Status::Stale),
        (
            format!("{} \"--other\"", program(&fixture.launcher)),
            Status::Stale,
        ),
        // Another install that still exists owns the entry.
        (format!("{} \"--other\"", program(&other)), Status::Enabled),
    ] {
        let changed = enabled.replace(&exec, &replacement);
        fs::write(fixture.path(), &changed).unwrap();
        assert_eq!(fixture.operate(Query).unwrap(), expected, "{replacement}");
        assert_eq!(fs::read_to_string(fixture.path()).unwrap(), changed);
        // A disabled entry stays disabled, whatever it launches.
        fs::write(fixture.path(), format!("{changed}Hidden=true\n")).unwrap();
        assert_eq!(fixture.operate(Query).unwrap(), Status::Disabled);
    }
    fs::write(fixture.path(), enabled.replace(&exec, &program(&other))).unwrap();
    fs::remove_file(&other).unwrap();
    assert_eq!(fixture.operate(Query).unwrap(), Status::Stale);
}

#[test]
fn moved_or_removed_launcher_becomes_stale_until_explicit_repair() {
    let mut fixture = Fixture::new();
    let old = fixture.enable();
    let next = fixture.root.path().join("upgraded launcher");
    fs::rename(&fixture.launcher, &next).unwrap();
    assert_eq!(fixture.operate(Query).unwrap(), Status::Stale);
    assert_eq!(
        fixture.operate(Enable).unwrap_err().code,
        Error::InvalidArgument
    );
    assert_eq!(fs::read_to_string(fixture.path()).unwrap(), old);
    fixture.launcher = next;
    assert_eq!(fixture.operate(Query).unwrap(), Status::Stale);
    assert_ne!(fixture.enable(), old);
    fs::remove_file(&fixture.launcher).unwrap();
    assert_eq!(fixture.operate(Query).unwrap(), Status::Stale);
    assert_eq!(fixture.operate(Disable).unwrap(), Status::Disabled);
    assert!(!fixture.path().exists());
    assert_eq!(fixture.operate(Disable).unwrap(), Status::Disabled);
}

#[test]
fn launcher_path_with_percent_is_refused_without_writing_an_entry() {
    let mut fixture = Fixture::new();
    let launcher = fixture.root.path().join("100% launcher");
    fs::write(&launcher, "fixture executable").unwrap();
    fixture.launcher = launcher;
    assert_eq!(
        fixture.operate(Enable).unwrap_err().code,
        Error::InvalidArgument
    );
    assert!(!fixture.path().exists());
}

#[test]
fn read_only_queries_do_not_create_directories_and_foreign_entries_survive() {
    let fixture = Fixture::new();
    assert_eq!(fixture.operate(Query).unwrap(), Status::Disabled);
    assert!(!fixture.path().parent().unwrap().exists());
    let owned = fixture.enable();
    for foreign in [
        "[Desktop Entry]\nName=Other\n".to_owned(),
        format!("{owned}X-Keyguard-LaunchAtLogin=other\n"),
    ] {
        fs::write(fixture.path(), &foreign).unwrap();
        assert_eq!(fixture.operate(Query).unwrap(), Status::Unknown);
        for operation in [Enable, Disable] {
            assert_eq!(
                fixture.operate(operation).unwrap_err().code,
                Error::Conflict
            );
            assert_eq!(fs::read_to_string(fixture.path()).unwrap(), foreign);
        }
    }
}

#[test]
fn failed_atomic_commit_preserves_old_registration_and_removes_owned_temporary() {
    let fixture = Fixture::new();
    let old = fixture.enable();
    let failure = replace_entry(&fixture.path(), b"replacement", |temporary, _| {
        assert_eq!(fs::read(temporary.path()).unwrap(), b"replacement");
        Err(std::io::Error::from(std::io::ErrorKind::PermissionDenied))
    })
    .unwrap_err();
    assert_eq!(failure.code, Error::System);
    assert_eq!(fs::read_to_string(fixture.path()).unwrap(), old);
    assert_eq!(
        fs::read_dir(fixture.path().parent().unwrap())
            .unwrap()
            .count(),
        1
    );
    assert_eq!(fixture.operate(Enable).unwrap(), Status::Enabled);
}

#[test]
fn leftover_staging_file_never_blocks_enabling_and_is_preserved() {
    let fixture = Fixture::new();
    // What an earlier process that crashed before its commit left behind, under
    // the name this process would have used.
    let leftover = fixture
        .path()
        .with_extension(format!("desktop.{}.tmp", std::process::id()));
    fs::create_dir_all(leftover.parent().unwrap()).unwrap();
    fs::write(&leftover, "unrelated sentinel").unwrap();
    fixture.enable();
    assert_eq!(fs::read_to_string(&leftover).unwrap(), "unrelated sentinel");
    // Only the entry and the leftover remain; the staged file was committed.
    assert_eq!(
        fs::read_dir(fixture.path().parent().unwrap())
            .unwrap()
            .count(),
        2
    );
}

#[test]
fn oversized_nonregular_and_invalid_utf8_entries_fail_without_replacement() {
    let fixture = Fixture::new();
    fixture.enable();
    for contents in [vec![b'x'; 65_537], vec![0xff]] {
        fs::write(fixture.path(), &contents).unwrap();
        for operation in [Query, Enable, Disable] {
            assert!(fixture.operate(operation).is_err());
            assert_eq!(fs::read(fixture.path()).unwrap(), contents);
        }
    }
    fs::remove_file(fixture.path()).unwrap();
    fs::create_dir(fixture.path()).unwrap();
    for operation in [Query, Enable, Disable] {
        assert_eq!(
            fixture.operate(operation).unwrap_err().code,
            Error::Conflict
        );
        assert!(fixture.path().is_dir());
    }
}

#[cfg(unix)]
#[test]
fn symlink_entry_never_modifies_its_target() {
    let fixture = Fixture::new();
    let old = fixture.enable();
    let target = fixture.root.path().join("unrelated.desktop");
    fs::rename(fixture.path(), &target).unwrap();
    std::os::unix::fs::symlink(&target, fixture.path()).unwrap();
    for operation in [Query, Enable, Disable] {
        assert_eq!(
            fixture.operate(operation).unwrap_err().code,
            Error::Conflict
        );
        assert_eq!(fs::read_to_string(&target).unwrap(), old);
        assert!(
            fs::symlink_metadata(fixture.path())
                .unwrap()
                .file_type()
                .is_symlink()
        );
    }
}

#[cfg(unix)]
#[test]
fn read_only_autostart_directory_reports_failures_and_preserves_registration() {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    let fixture = Fixture::new();
    // Running as root would bypass the permission being tested. CI and developer
    // runs must use an unprivileged account; do not silently count that as coverage.
    assert_ne!(
        fs::metadata(fixture.root.path()).unwrap().uid(),
        0,
        "permission fixtures require an unprivileged account"
    );
    let directory = fixture.path().parent().unwrap().to_owned();
    fs::create_dir(&directory).unwrap();
    struct Restore(PathBuf);
    impl Drop for Restore {
        fn drop(&mut self) {
            fs::set_permissions(&self.0, fs::Permissions::from_mode(0o700)).unwrap();
        }
    }
    {
        let _restore = Restore(directory.clone());
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o500)).unwrap();
        assert_eq!(fixture.operate(Enable).unwrap_err().code, Error::System);
        assert!(!fixture.path().exists());
    }
    let existing = fixture.enable();
    {
        let _restore = Restore(directory.clone());
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o500)).unwrap();
        assert_eq!(fixture.operate(Query).unwrap(), Status::Enabled);
        assert_eq!(fixture.operate(Disable).unwrap_err().code, Error::System);
        assert_eq!(fs::read_to_string(fixture.path()).unwrap(), existing);
    }
    assert_eq!(fixture.operate(Disable).unwrap(), Status::Disabled);
}
