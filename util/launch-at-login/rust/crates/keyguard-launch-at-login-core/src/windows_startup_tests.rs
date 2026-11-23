use super::*;
use crate::Error;
use crate::Operation::{Disable, Enable, Status as Query};
use std::cell::{Cell, RefCell};

struct Task {
    current: Cell<Status>,
    calls: RefCell<Vec<&'static str>>,
    fail: Option<&'static str>,
}
impl Task {
    fn new(status: Status, fail: Option<&'static str>) -> Self {
        Self {
            current: Cell::new(status),
            calls: RefCell::new(vec![]),
            fail,
        }
    }
    fn call(&self, stage: &'static str) -> Result<(), Failure> {
        self.calls.borrow_mut().push(stage);
        if self.fail == Some(stage) {
            Err(failure(stage))
        } else {
            Ok(())
        }
    }
}
impl StartupTask for &Task {
    fn status(&self) -> Result<Status, Failure> {
        self.call(if self.calls.borrow().is_empty() {
            "initial status"
        } else {
            "final status"
        })?;
        Ok(self.current.get())
    }
    fn enable(&self) -> Result<Status, Failure> {
        self.call("enable")?;
        self.current.set(Status::Enabled);
        Ok(self.current.get())
    }
    fn disable(&self) -> Result<(), Failure> {
        self.call("disable")?;
        self.current.set(Status::Disabled);
        Ok(())
    }
}
fn failure(stage: &str) -> Failure {
    Failure::new(Error::System, stage)
}
fn version() -> Result<String, Failure> {
    Ok(((10u64 << 48) | (19_041u64 << 16)).to_string())
}
fn run(operation: Operation, task: &Task) -> Result<Status, Failure> {
    execute_package(operation, version, || Ok(task))
}

#[test]
fn preflight_errors_never_open_or_modify_a_task() {
    let task = Task::new(Status::Disabled, None);
    assert_eq!(
        execute_package(Enable, || Err(failure("version")), || Ok(&task))
            .unwrap_err()
            .message,
        "version"
    );
    assert_eq!(
        execute_package(Enable, version, || Err::<&Task, _>(failure("get task")))
            .unwrap_err()
            .message,
        "get task"
    );
    assert!(task.calls.borrow().is_empty());
    assert_eq!(task.current.get(), Status::Disabled);
}

#[test]
fn unsupported_os_never_contacts_task_but_disable_skips_version() {
    let task = Task::new(Status::Enabled, None);
    for operation in [Query, Enable] {
        assert_eq!(
            execute_package(
                operation,
                || Ok(((10u64 << 48) | (18_362u64 << 16)).to_string()),
                || Ok(&task)
            )
            .unwrap(),
            Status::Unavailable
        );
    }
    assert!(task.calls.borrow().is_empty());
    assert_eq!(
        execute_package(
            Disable,
            || panic!("disable queried OS version"),
            || Ok(&task)
        )
        .unwrap(),
        Status::Disabled
    );
    assert_eq!(
        *task.calls.borrow(),
        ["initial status", "disable", "final status"]
    );
}

#[test]
fn failed_calls_propagate_without_claiming_success_and_allow_retry() {
    for (operation, initial, stage, expected_calls) in [
        (
            Enable,
            Status::Disabled,
            "initial status",
            vec!["initial status"],
        ),
        (
            Enable,
            Status::Disabled,
            "enable",
            vec!["initial status", "enable"],
        ),
        (
            Disable,
            Status::Enabled,
            "disable",
            vec!["initial status", "disable"],
        ),
        (
            Disable,
            Status::Enabled,
            "final status",
            vec!["initial status", "disable", "final status"],
        ),
    ] {
        let mut task = Task::new(initial, Some(stage));
        assert_eq!(run(operation, &task).unwrap_err().message, stage);
        assert_eq!(*task.calls.borrow(), expected_calls);
        task.fail = None;
        task.calls.borrow_mut().clear();
        assert_eq!(
            run(operation, &task).unwrap(),
            if operation == Enable {
                Status::Enabled
            } else {
                Status::Disabled
            }
        );
    }
}

#[test]
fn revoked_policy_and_future_states_are_read_only_through_the_actual_dispatcher() {
    for status in [
        Status::DisabledByUser,
        Status::EnabledByPolicy,
        Status::DisabledByPolicy,
        Status::Unknown,
    ] {
        for operation in [Query, Enable, Disable] {
            let task = Task::new(status, None);
            assert_eq!(run(operation, &task).unwrap(), status);
            assert_eq!(*task.calls.borrow(), ["initial status"]);
        }
    }
    let task = Task::new(Status::Disabled, None);
    assert_eq!(run(Enable, &task).unwrap(), Status::Enabled);
    task.current.set(Status::DisabledByUser);
    task.calls.borrow_mut().clear();
    assert_eq!(run(Query, &task).unwrap(), Status::DisabledByUser);
    assert_eq!(*task.calls.borrow(), ["initial status"]);
}
