//! The app shows its own text for each code, so every user-facing failure
//! must keep its code and the path it names.
use keyguard_agent_setup::{
    Agent, AgentKind, Conflict, Environment, Request, SetupError, Shell, apply, error_json, plan,
    undo,
};
use serde_json::json;
use std::fs;

mod support;

fn fixture() -> (tempfile::TempDir, Environment, Request) {
    let dir = tempfile::tempdir().unwrap();
    let env = support::environment(dir.path(), "PowerShell/profile.ps1");
    let request = Request {
        agent: Agent::Gpg {
            home: env.home.join("Keyguard/gnupg"),
        },
        shells: vec![Shell::Zsh],
        launcher: None,
    };
    (dir, env, request)
}

fn code(error: anyhow::Error) -> SetupError {
    error.downcast_ref::<SetupError>().unwrap().clone()
}

#[test]
fn changed_files_require_a_fresh_review() {
    let (_dir, env, request) = fixture();
    let reviewed = plan(&env, request).unwrap();
    fs::write(env.home.join(".zshrc"), "export EDITOR=vim\n").unwrap();
    assert_eq!(
        code(apply(&env, &reviewed).unwrap_err()),
        SetupError::Changed
    );
}

#[test]
fn a_running_setup_reports_busy() {
    let (_dir, env, request) = fixture();
    let reviewed = plan(&env, request).unwrap();
    let state = env.home.join(".keyguard/agent-setup");
    fs::create_dir_all(&state).unwrap();
    let lock = fs::File::create(state.join("lock")).unwrap();
    lock.try_lock().unwrap();
    assert_eq!(code(apply(&env, &reviewed).unwrap_err()), SetupError::Busy);
}

#[test]
fn unsafe_files_and_inconsistent_markers_name_the_file() {
    let (_dir, env, request) = fixture();
    let path = env.home.join(".zshrc");
    fs::write(&path, b"\xff\xfe not text\n").unwrap();
    assert_eq!(
        code(plan(&env, request.clone()).unwrap_err()),
        SetupError::UnsafeFile { path: path.clone() }
    );
    let begin = "# Begin Keyguard gpg agent setup\n";
    fs::write(&path, begin.repeat(2)).unwrap();
    assert_eq!(
        code(plan(&env, request).unwrap_err()),
        SetupError::ManagedBlock { path }
    );
}

#[cfg(unix)]
#[test]
fn symlinked_files_are_unsafe() {
    let (_dir, env, request) = fixture();
    fs::write(env.home.join("zshrc"), "").unwrap();
    std::os::unix::fs::symlink(env.home.join("zshrc"), env.home.join(".zshrc")).unwrap();
    assert_eq!(
        code(plan(&env, request).unwrap_err()),
        SetupError::UnsafeFile {
            path: env.home.join(".zshrc")
        }
    );
}

#[test]
fn conflicts_carry_a_code_and_path() {
    let (_dir, env, request) = fixture();
    fs::write(env.home.join(".zshenv"), "ZDOTDIR=$HOME/zsh\n").unwrap();
    let reviewed = plan(&env, request.clone()).unwrap();
    assert_eq!(
        reviewed.conflicts,
        [Conflict::Zdotdir {
            path: env.home.join(".zshenv")
        }]
    );
    assert_eq!(
        serde_json::to_value(&reviewed.conflicts[0]).unwrap(),
        json!({ "code": "zdotdir", "path": env.home.join(".zshenv") })
    );

    fs::remove_file(env.home.join(".zshenv")).unwrap();
    apply(&env, &plan(&env, request).unwrap()).unwrap();
    let path = env.home.join(".zshrc");
    let text = fs::read_to_string(&path).unwrap().replace("gnupg", "other");
    fs::write(&path, text).unwrap();
    assert_eq!(
        undo(&env, AgentKind::Gpg).unwrap().conflicts,
        [Conflict::ChangedBlock { path }]
    );
}

#[test]
fn json_errors_keep_the_code_path_and_english_detail() {
    let path = std::path::PathBuf::from("/home/user/.zshrc");
    let error =
        anyhow::anyhow!("refusing a link").context(SetupError::UnsafeFile { path: path.clone() });
    assert_eq!(
        error_json(&error),
        json!({
            "code": "unsafe_file",
            "path": path,
            "detail": format!("cannot safely edit {}: refusing a link", path.display()),
        })
    );
    assert_eq!(
        error_json(&anyhow::anyhow!("no recorded ssh setup to undo")),
        json!({ "code": "failed", "detail": "no recorded ssh setup to undo" })
    );
}
