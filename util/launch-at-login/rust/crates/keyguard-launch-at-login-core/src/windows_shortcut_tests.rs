//! Uses real COM persistence in a temporary directory, never FOLDERID_Startup,
//! and a fake StartupApproved value instead of the account's registry.
use super::*;
use Operation::{Disable, Enable, Status as Query};
use std::cell::RefCell;

fn approved(_: &str) -> Result<Option<Vec<u8>>, Failure> {
    Ok(None)
}

fn run(directory: &Path, operation: Operation, target: &Target<'_>) -> Result<Status, Failure> {
    shortcut_in(directory, operation, target, approved)
}

#[test]
fn com_shortcut_round_trip_stale_repair_and_cleanup() {
    let _apartment = Apartment::enter().unwrap();
    let root = tempfile::tempdir().unwrap();
    let launcher = root.path().join("应用 with spaces.exe");
    std::fs::write(&launcher, "fixture").unwrap();
    let mut target = Target {
        kind: "windows",
        id: "com.example.fixture",
        name: "Fixture",
        executable: launcher.to_str().unwrap(),
        task_id: "FixtureStartup",
        arguments: vec!["--launch-at-login", "", "literal \"quoted\" value\\"],
    };
    assert_eq!(run(root.path(), Query, &target).unwrap(), Status::Disabled);
    assert_eq!(run(root.path(), Enable, &target).unwrap(), Status::Enabled);
    let path = root.path().join("com.example.fixture-autostart.lnk");
    let initial = std::fs::read(&path).unwrap();
    assert_eq!(run(root.path(), Query, &target).unwrap(), Status::Enabled);
    assert_eq!(std::fs::read(&path).unwrap(), initial);
    target.arguments.push("upgraded argument");
    assert_eq!(run(root.path(), Query, &target).unwrap(), Status::Stale);
    assert_eq!(run(root.path(), Enable, &target).unwrap(), Status::Enabled);
    assert_ne!(std::fs::read(&path).unwrap(), initial);
    std::fs::remove_file(&launcher).unwrap();
    assert_eq!(run(root.path(), Query, &target).unwrap(), Status::Stale);
    assert_eq!(
        run(root.path(), Enable, &target).unwrap_err().code,
        Error::InvalidArgument
    );
    assert_eq!(
        run(root.path(), Disable, &target).unwrap(),
        Status::Disabled
    );
    assert!(!path.exists());
    assert_eq!(
        run(root.path(), Disable, &target).unwrap(),
        Status::Disabled
    );
}

#[test]
fn another_existing_install_keeps_its_entry() {
    let _apartment = Apartment::enter().unwrap();
    let root = tempfile::tempdir().unwrap();
    let other = root.path().join("other.exe");
    let this = root.path().join("this.exe");
    std::fs::write(&other, "fixture").unwrap();
    std::fs::write(&this, "fixture").unwrap();
    let mut target = Target {
        kind: "windows",
        id: "com.example.fixture",
        name: "Fixture",
        executable: other.to_str().unwrap(),
        task_id: "FixtureStartup",
        arguments: vec!["--launch-at-login"],
    };
    run(root.path(), Enable, &target).unwrap();
    let path = root.path().join("com.example.fixture-autostart.lnk");
    let registered = std::fs::read(&path).unwrap();
    target.executable = this.to_str().unwrap();
    target.arguments = vec![];
    assert_eq!(run(root.path(), Query, &target).unwrap(), Status::Enabled);
    assert_eq!(std::fs::read(&path).unwrap(), registered);
    std::fs::remove_file(&other).unwrap();
    assert_eq!(run(root.path(), Query, &target).unwrap(), Status::Stale);
}

#[test]
fn task_manager_disable_is_read_first_and_outlives_app_disable_and_enable() {
    let _apartment = Apartment::enter().unwrap();
    let root = tempfile::tempdir().unwrap();
    let launcher = root.path().join("fixture.exe");
    std::fs::write(&launcher, "fixture").unwrap();
    let target = Target {
        kind: "windows",
        id: "com.example.fixture",
        name: "Fixture",
        executable: launcher.to_str().unwrap(),
        task_id: "FixtureStartup",
        arguments: vec![],
    };
    // Only Task Manager writes this fake key; the shortcut code can only read it.
    let registry = RefCell::new(None::<Vec<u8>>);
    let names = RefCell::new(vec![]);
    let operate = |operation| {
        shortcut_in(root.path(), operation, &target, |name: &str| {
            names.borrow_mut().push(name.to_owned());
            Ok(registry.borrow().clone())
        })
    };
    assert_eq!(operate(Enable).unwrap(), Status::Enabled);
    *registry.borrow_mut() = Some([&[0x03, 0, 0, 0][..], &[0xff; 8]].concat());
    assert_eq!(operate(Query).unwrap(), Status::DisabledByUser);
    // A user-disabled entry is reported before it could look stale.
    std::fs::remove_file(&launcher).unwrap();
    assert_eq!(operate(Query).unwrap(), Status::DisabledByUser);
    std::fs::write(&launcher, "fixture").unwrap();
    assert_eq!(operate(Disable).unwrap(), Status::Disabled);
    assert_eq!(operate(Enable).unwrap(), Status::DisabledByUser);
    *registry.borrow_mut() = Some(vec![0x02, 0, 0, 0]);
    assert_eq!(operate(Query).unwrap(), Status::Enabled);
    *registry.borrow_mut() = Some(vec![]);
    assert_eq!(operate(Query).unwrap(), Status::Enabled);
    assert!(
        names
            .borrow()
            .iter()
            .all(|name| name == "com.example.fixture-autostart.lnk")
    );
    let failing = shortcut_in(root.path(), Query, &target, |_: &str| {
        Err(Failure::new(Error::System, "registry"))
    });
    assert_eq!(failing.unwrap_err().code, Error::System);
}

#[test]
fn foreign_and_invalid_shortcuts_are_never_overwritten_or_deleted() {
    let _apartment = Apartment::enter().unwrap();
    let root = tempfile::tempdir().unwrap();
    let launcher = root.path().join("fixture.exe");
    std::fs::write(&launcher, "fixture").unwrap();
    let mut target = Target {
        kind: "windows",
        id: "another.owner",
        name: "Other",
        executable: launcher.to_str().unwrap(),
        task_id: "FixtureStartup",
        arguments: vec![],
    };
    run(root.path(), Enable, &target).unwrap();
    let path = root.path().join("com.example.fixture-autostart.lnk");
    std::fs::rename(root.path().join("another.owner-autostart.lnk"), &path).unwrap();
    target.id = "com.example.fixture";
    let foreign = std::fs::read(&path).unwrap();
    for operation in [Query, Enable, Disable] {
        assert_eq!(
            run(root.path(), operation, &target).unwrap_err().code,
            Error::Conflict
        );
        assert_eq!(std::fs::read(&path).unwrap(), foreign);
    }
    std::fs::write(&path, "invalid shortcut sentinel").unwrap();
    for operation in [Query, Enable, Disable] {
        assert!(run(root.path(), operation, &target).is_err());
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            "invalid shortcut sentinel"
        );
    }
    std::fs::remove_file(&path).unwrap();
    std::fs::create_dir(&path).unwrap();
    assert_eq!(
        run(root.path(), Enable, &target).unwrap_err().code,
        Error::Conflict
    );
}

#[test]
fn failed_persistence_does_not_claim_registration_and_retry_succeeds() {
    let _apartment = Apartment::enter().unwrap();
    let root = tempfile::tempdir().unwrap();
    let launcher = root.path().join("fixture.exe");
    std::fs::write(&launcher, "fixture").unwrap();
    let target = Target {
        kind: "windows",
        id: "com.example.fixture",
        name: "Fixture",
        executable: launcher.to_str().unwrap(),
        task_id: "FixtureStartup",
        arguments: vec![],
    };
    let missing = root.path().join("missing-directory");
    assert_eq!(
        run(&missing, Enable, &target).unwrap_err().code,
        Error::System
    );
    assert!(!missing.exists());
    std::fs::create_dir(&missing).unwrap();
    assert_eq!(run(&missing, Enable, &target).unwrap(), Status::Enabled);
    run(&missing, Disable, &target).unwrap();
    assert_eq!(std::fs::read_dir(&missing).unwrap().count(), 0);
}
