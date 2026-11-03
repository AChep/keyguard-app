//! The Include block for Keyguard's SSH bookmarks, which shares
//! `~/.ssh/config` with the agent's own block.
use keyguard_agent_setup::{
    Agent, AgentKind, Conflict, Environment, Plan, Request, SetupError, Shell, apply, plan, undo,
};
use serde_json::json;
use std::{
    fs,
    path::{Path, PathBuf},
};
use support::{fixture, put, socket};

mod support;

const ORIGINAL: &str = "Host work\n  User me\n";

fn request(include: Option<PathBuf>, shells: Vec<Shell>) -> Request {
    Request {
        launcher: None,
        agent: Agent::Ssh {
            socket: socket().into(),
            host: None,
            include,
        },
        shells,
    }
}

fn bookmarks(env: &Environment) -> Option<PathBuf> {
    Some(env.home.join(".ssh/keyguard/config"))
}

/// Applies the agent's setup with the include to [`ORIGINAL`].
fn configured(env: &Environment) -> Request {
    put(env, ".ssh/config", ORIGINAL);
    let request = request(bookmarks(env), vec![]);
    apply(env, &plan(env, request.clone()).unwrap()).unwrap();
    request
}

fn config(env: &Environment) -> PathBuf {
    env.home.join(".ssh/config")
}

fn journal(env: &Environment) -> PathBuf {
    env.home.join(".keyguard/agent-setup/ssh.json")
}

fn include_journal(env: &Environment) -> PathBuf {
    env.home.join(".keyguard/agent-setup/ssh-bookmarks.json")
}

fn read(path: &Path) -> String {
    fs::read_to_string(path).unwrap()
}

/// The bookmarks block as written to `~/.ssh/config`.
fn include_block(path: &str) -> String {
    format!(
        "# Begin Keyguard ssh-bookmarks agent setup\nInclude \"{path}\"\n# End Keyguard ssh-bookmarks agent setup\n"
    )
}

/// The agent's block as written to `~/.ssh/config`.
fn agent_block(socket: &str) -> String {
    let socket = if cfg!(windows) {
        socket.replace('\\', "/")
    } else {
        socket.to_owned()
    };
    format!(
        "# Begin Keyguard ssh agent setup\nHost *\n  IdentityAgent \"{socket}\"\n# End Keyguard ssh agent setup\n"
    )
}

fn after(plan: &Plan, path: &Path) -> String {
    let edits: Vec<_> = plan.edits.iter().filter(|edit| edit.path == path).collect();
    assert_eq!(edits.len(), 1, "{:?}", plan.edits);
    edits[0].after.clone().unwrap()
}

#[test]
fn include_is_first_and_the_agent_block_last_in_one_edit() {
    let (_dir, env) = fixture();
    put(&env, ".ssh/config", ORIGINAL);
    let request = request(bookmarks(&env), vec![Shell::Zsh]);
    let reviewed = plan(&env, request.clone()).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    assert_eq!(reviewed.edits.len(), 2);
    let expected = include_block("~/.ssh/keyguard/config") + ORIGINAL + &agent_block(socket());
    assert_eq!(after(&reviewed, &config(&env)), expected);
    apply(&env, &reviewed).unwrap();
    assert_eq!(read(&config(&env)), expected);
    assert!(plan(&env, request).unwrap().edits.is_empty());
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(read(&config(&env)), ORIGINAL);
    assert!(!env.home.join(".zshrc").exists());
    assert!(!journal(&env).exists());
}

#[test]
fn undo_deletes_a_created_configuration() {
    let (_dir, env) = fixture();
    let request = request(bookmarks(&env), vec![]);
    apply(&env, &plan(&env, request.clone()).unwrap()).unwrap();
    assert!(
        read(&config(&env)).starts_with(&include_block("~/.ssh/keyguard/config")),
        "{}",
        read(&config(&env))
    );
    assert!(plan(&env, request).unwrap().edits.is_empty());
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert!(!config(&env).exists());
    assert!(!journal(&env).exists());
}

#[test]
fn include_paths_in_the_home_start_with_a_tilde() {
    let (_dir, env) = fixture();
    let outside = tempfile::tempdir().unwrap();
    let external = outside.path().join("ssh-bookmarks/config");
    let absolute = external.to_string_lossy().into_owned();
    let absolute = if cfg!(windows) {
        absolute.replace('\\', "/")
    } else {
        absolute
    };
    for (include, rendered) in [
        (
            env.home
                .join(".var/app/com.artemchep.keyguard/data/ssh-bookmarks/config"),
            "~/.var/app/com.artemchep.keyguard/data/ssh-bookmarks/config".to_owned(),
        ),
        (
            env.home.join("Keyguard SSH/config"),
            "~/Keyguard SSH/config".into(),
        ),
        (external, absolute),
    ] {
        let reviewed = plan(&env, request(Some(include), vec![])).unwrap();
        let text = after(&reviewed, &config(&env));
        assert!(text.starts_with(&include_block(&rendered)), "{text}");
    }
}

#[cfg(unix)]
#[test]
fn windows_include_paths_use_forward_slashes() {
    let (_dir, mut env) = fixture();
    let include = env.home.join(r".ssh\keyguard\config");
    assert!(plan(&env, request(Some(include.clone()), vec![])).is_err());
    env.windows = true;
    for (include, rendered) in [
        (include, "~/.ssh/keyguard/config"),
        (r"/keyguard\ssh\config".into(), "/keyguard/ssh/config"),
    ] {
        let reviewed = plan(&env, request(Some(include), vec![])).unwrap();
        let text = after(&reviewed, &config(&env));
        assert!(text.starts_with(&include_block(rendered)), "{text}");
    }
}

#[test]
fn rejects_unsafe_include_paths() {
    let (_dir, env) = fixture();
    let home = &env.home;
    let mut rejected = vec![
        PathBuf::new(),
        "keyguard/config".into(),
        home.join(".ssh/keyguard/../config"),
        home.join("%h/config"),
        home.join("$HOME/config"),
        home.join("bad\"config"),
        home.join("*.conf"),
        home.join("config?"),
        home.join("[ab]/config"),
        home.join("config\nProxyCommand bad"),
        home.join("config\r"),
    ];
    if cfg!(unix) {
        rejected.push(home.join(r"keyguard\config"));
    }
    for include in rejected {
        assert!(
            plan(&env, request(Some(include.clone()), vec![])).is_err(),
            "{include:?}"
        );
    }
    // OpenSSH stops at its include depth limit, so a loop breaks every host.
    for include in [".ssh/config", ".ssh/./config", ".SSH/Config"] {
        let error = plan(&env, request(Some(home.join(include)), vec![])).unwrap_err();
        assert!(error.to_string().contains("itself"), "{include}: {error}");
    }
    assert!(!config(&env).exists());
}

#[test]
fn include_paths_in_a_home_with_metacharacters_start_with_a_tilde() {
    let dir = tempfile::tempdir().unwrap();
    let home = dir.path().join("me [1] $USER %h");
    fs::create_dir(&home).unwrap();
    let env = support::environment(&home, "Documents/PowerShell/profile.ps1");
    let reviewed = plan(&env, request(bookmarks(&env), vec![])).unwrap();
    let text = after(&reviewed, &config(&env));
    assert!(
        text.starts_with(&include_block("~/.ssh/keyguard/config")),
        "{text}"
    );
}

#[test]
fn include_block_moves_back_above_host_and_match_blocks() {
    for prefix in ["Host other\n  User x\n", "match all\n", "  Host=other\n"] {
        let (_dir, env) = fixture();
        let request = configured(&env);
        // Another tool adds its hosts at the top.
        fs::write(config(&env), prefix.to_owned() + &read(&config(&env))).unwrap();

        let reviewed = plan(&env, request.clone()).unwrap();
        assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
        let expected =
            include_block("~/.ssh/keyguard/config") + prefix + ORIGINAL + &agent_block(socket());
        assert_eq!(after(&reviewed, &config(&env)), expected, "{prefix}");
        apply(&env, &reviewed).unwrap();
        assert!(plan(&env, request).unwrap().edits.is_empty(), "{prefix}");
        apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
        assert_eq!(read(&config(&env)), prefix.to_owned() + ORIGINAL);
    }
}

#[test]
fn include_block_stays_below_unconditional_lines() {
    let (_dir, env) = fixture();
    let request = configured(&env);
    let text = "# Hosts\nInclude ~/.orbstack/ssh/config\n".to_owned() + &read(&config(&env));
    fs::write(config(&env), &text).unwrap();
    assert!(plan(&env, request).unwrap().edits.is_empty());
}

#[test]
fn dropping_the_include_removes_only_its_block_and_record() {
    let (_dir, env) = fixture();
    configured(&env);
    let record: serde_json::Value = serde_json::from_str(&read(&journal(&env))).unwrap();
    let files = record["files"].as_array().unwrap();
    assert_eq!(files.len(), 1);
    assert!(
        !files[0]["block"]
            .as_str()
            .unwrap()
            .contains("ssh-bookmarks")
    );
    let record: serde_json::Value = serde_json::from_str(&read(&include_journal(&env))).unwrap();
    let files = record["files"].as_array().unwrap();
    assert_eq!(files.len(), 1);
    assert_eq!(files[0]["block"], include_block("~/.ssh/keyguard/config"));
    assert_eq!(files[0]["created"], false);

    let reviewed = plan(&env, request(None, vec![])).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    assert_eq!(
        after(&reviewed, &config(&env)),
        ORIGINAL.to_owned() + &agent_block(socket())
    );
    apply(&env, &reviewed).unwrap();
    assert!(!include_journal(&env).exists());
    assert!(plan(&env, request(None, vec![])).unwrap().edits.is_empty());
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(read(&config(&env)), ORIGINAL);
}

#[test]
fn version_4_journals_without_block_ids_still_undo() {
    let (_dir, env) = fixture();
    let block = agent_block(socket());
    put(&env, ".ssh/config", &(ORIGINAL.to_owned() + &block));
    let record = json!({
        "request": {
            "agent": { "kind": "ssh", "socket": socket(), "host": null },
            "shells": [],
            "launcher": null,
        },
        "files": [{ "path": config(&env), "created": false, "block": block }],
    });
    let record = serde_json::to_string_pretty(&record).unwrap();
    put(&env, ".keyguard/agent-setup/ssh.json", &record);
    let reviewed = undo(&env, AgentKind::Ssh).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    apply(&env, &reviewed).unwrap();
    assert_eq!(read(&config(&env)), ORIGINAL);

    // The same journal, upgraded by a setup that adds the include.
    put(&env, ".ssh/config", &(ORIGINAL.to_owned() + &block));
    put(&env, ".keyguard/agent-setup/ssh.json", &record);
    let reviewed = plan(&env, request(bookmarks(&env), vec![])).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    apply(&env, &reviewed).unwrap();
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(read(&config(&env)), ORIGINAL);
}

#[test]
fn plans_without_include_keep_the_version_4_shape() {
    let (_dir, env) = fixture();
    let agent: Agent =
        serde_json::from_value(json!({ "kind": "ssh", "socket": socket(), "host": null })).unwrap();
    assert_eq!(agent, request(None, vec![]).agent);

    let reviewed = plan(&env, request(None, vec![])).unwrap();
    let value = serde_json::to_value(&reviewed).unwrap();
    assert!(
        value["request"]["agent"].get("include").is_none(),
        "{value}"
    );
    // The app passes the plan's JSON back unchanged.
    let parsed: Plan = serde_json::from_str(&value.to_string()).unwrap();
    apply(&env, &parsed).unwrap();
    assert!(!read(&journal(&env)).contains("\"id\""));

    let reviewed = plan(&env, request(bookmarks(&env), vec![])).unwrap();
    let value = serde_json::to_value(&reviewed).unwrap();
    assert_eq!(
        value["request"]["agent"]["include"],
        json!(bookmarks(&env).unwrap())
    );
    let parsed: Plan = serde_json::from_str(&value.to_string()).unwrap();
    apply(&env, &parsed).unwrap();
}

#[test]
fn interrupted_apply_with_both_blocks_can_be_recovered_by_undo() {
    let (_dir, env) = fixture();
    let reviewed = plan(&env, request(bookmarks(&env), vec![Shell::Bash])).unwrap();
    assert_eq!(reviewed.edits[0].path, config(&env));
    apply(&env, &reviewed).unwrap();
    // A persisted journal with only the first write is the state after a crash.
    for edit in reviewed.edits.iter().skip(1) {
        fs::remove_file(&edit.path).unwrap();
    }
    let recovery = undo(&env, AgentKind::Ssh).unwrap();
    assert!(recovery.conflicts.is_empty(), "{:?}", recovery.conflicts);
    apply(&env, &recovery).unwrap();
    assert!(!config(&env).exists());
    assert!(!journal(&env).exists());
    assert!(!include_journal(&env).exists());
}

#[test]
fn older_setups_see_only_the_agent_block() {
    let (_dir, env) = fixture();
    let request = configured(&env);
    // The agent's journal keeps the shape that older setups read and rewrite.
    let record = read(&journal(&env));
    assert!(!record.contains("ssh-bookmarks"), "{record}");
    assert!(!record.contains("\"id\""), "{record}");

    // An older setup undoes only its own block and record.
    let include = include_block("~/.ssh/keyguard/config");
    put(&env, ".ssh/config", &(include.clone() + ORIGINAL));
    fs::remove_file(journal(&env)).unwrap();
    let reviewed = plan(&env, request).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    apply(&env, &reviewed).unwrap();
    assert_eq!(
        read(&config(&env)),
        include + ORIGINAL + &agent_block(socket())
    );
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(read(&config(&env)), ORIGINAL);
    assert!(!include_journal(&env).exists());
}

#[cfg(unix)]
#[test]
fn interrupted_apply_keeps_replaced_and_retired_include_blocks_recorded() {
    use std::os::unix::fs::PermissionsExt;
    // The include moves, or is dropped.
    let changes: [fn(&Environment) -> Option<PathBuf>; 2] = [
        |env| Some(env.home.join(".local/share/keyguard/ssh/config")),
        |_| None,
    ];
    for change in changes {
        let (_dir, env) = fixture();
        put(&env, ".ssh/config", ORIGINAL);
        apply(
            &env,
            &plan(&env, request(bookmarks(&env), vec![Shell::Bash])).unwrap(),
        )
        .unwrap();
        let changed = request(change(&env), vec![Shell::Bash]);
        let reviewed = plan(&env, changed.clone()).unwrap();
        assert_eq!(reviewed.edits[0].path, config(&env));
        // The first write fails after the journal is saved, so the
        // configuration keeps the include block that the plan replaces.
        let dir = env.home.join(".ssh");
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o500)).unwrap();
        let result = apply(&env, &reviewed);
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700)).unwrap();
        assert!(result.is_err());

        let recovery = undo(&env, AgentKind::Ssh).unwrap();
        assert!(recovery.conflicts.is_empty(), "{:?}", recovery.conflicts);
        assert_eq!(after(&recovery, &config(&env)), ORIGINAL);
        apply(&env, &plan(&env, changed).unwrap()).unwrap();
        apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
        assert_eq!(read(&config(&env)), ORIGINAL);
        for name in [".bashrc", ".bash_profile"] {
            assert!(!env.home.join(name).exists(), "{name}");
        }
    }
}

#[test]
fn include_blocks_without_an_undo_record_are_conflicts() {
    let (_dir, env) = fixture();
    put(&env, ".ssh/config", ORIGINAL);
    apply(&env, &plan(&env, request(None, vec![])).unwrap()).unwrap();
    // Copied from another computer's dotfiles, without its undo record.
    let text = include_block("~/.ssh/keyguard/config") + &read(&config(&env));
    fs::write(config(&env), &text).unwrap();
    for include in [bookmarks(&env), None] {
        let reviewed = plan(&env, request(include, vec![])).unwrap();
        assert_eq!(
            reviewed.conflicts,
            [Conflict::UnrecordedBlock { path: config(&env) }]
        );
        assert!(apply(&env, &reviewed).is_err());
    }
    // Both blocks without a record are reported once.
    fs::remove_file(journal(&env)).unwrap();
    let reviewed = plan(&env, request(bookmarks(&env), vec![])).unwrap();
    assert_eq!(
        reviewed.conflicts,
        [Conflict::UnrecordedBlock { path: config(&env) }]
    );
    assert_eq!(read(&config(&env)), text);
}

#[test]
fn changed_include_block_requires_manual_resolution() {
    let (_dir, env) = fixture();
    let request = configured(&env);
    let text = read(&config(&env)).replace("~/.ssh/keyguard/config", "~/.ssh/other/config");
    fs::write(config(&env), &text).unwrap();
    let reviewed = undo(&env, AgentKind::Ssh).unwrap();
    assert_eq!(
        reviewed.conflicts,
        [Conflict::ChangedBlock { path: config(&env) }]
    );
    assert!(reviewed.edits.is_empty(), "{:?}", reviewed.edits);
    assert!(apply(&env, &reviewed).is_err());
    assert_eq!(
        plan(&env, request).unwrap().conflicts,
        [Conflict::ChangedBlock { path: config(&env) }]
    );
    assert_eq!(read(&config(&env)), text);
}

#[test]
fn crlf_configuration_keeps_crlf() {
    let (_dir, env) = fixture();
    let original = "Host work\r\n  User me\r\n";
    put(&env, ".ssh/config", original);
    let request = request(bookmarks(&env), vec![]);
    apply(&env, &plan(&env, request.clone()).unwrap()).unwrap();
    let text = read(&config(&env));
    assert!(
        text.starts_with(&include_block("~/.ssh/keyguard/config").replace('\n', "\r\n")),
        "{text:?}"
    );
    assert!(!text.replace("\r\n", "").contains('\n'), "{text:?}");
    assert!(plan(&env, request).unwrap().edits.is_empty());
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(read(&config(&env)), original);
}

#[test]
fn include_block_lines_are_not_reported_as_overrides() {
    let (_dir, env) = fixture();
    put(&env, ".ssh/config", "Host *\n  IdentityAgent /other\n");
    // The include path names the setting, as a word OpenSSH never reads as one.
    let request = request(Some(env.home.join("IdentityAgent/config")), vec![]);
    let reviewed = plan(&env, request.clone()).unwrap();
    let lines: Vec<_> = reviewed.overrides.iter().map(|item| item.line).collect();
    assert_eq!(lines, [2]);
    apply(&env, &reviewed).unwrap();
    let reviewed = plan(&env, request).unwrap();
    assert!(reviewed.edits.is_empty());
    let lines: Vec<_> = reviewed.overrides.iter().map(|item| item.line).collect();
    assert_eq!(lines, [5]);
}

#[test]
fn overlapping_blocks_are_inconsistent() {
    let (_dir, env) = fixture();
    put(
        &env,
        ".ssh/config",
        concat!(
            "# Begin Keyguard ssh-bookmarks agent setup\n",
            "# Begin Keyguard ssh agent setup\n",
            "# End Keyguard ssh-bookmarks agent setup\n",
            "# End Keyguard ssh agent setup\n",
        ),
    );
    let error = plan(&env, request(bookmarks(&env), vec![])).unwrap_err();
    assert_eq!(
        error.downcast_ref::<SetupError>(),
        Some(&SetupError::ManagedBlock { path: config(&env) })
    );
}
