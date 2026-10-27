use std::process::{Command, Output};

fn keyguard(args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_keyguard"))
        .args(args)
        .env("NO_COLOR", "1")
        .output()
        .expect("run keyguard")
}

fn help(args: &[&str]) -> String {
    let output = keyguard(args);
    assert!(output.status.success(), "{args:?}: {output:?}");
    String::from_utf8(output.stdout).expect("UTF-8")
}

#[test]
fn help_command_prints_the_same_help_as_the_flag() {
    assert_eq!(help(&["help"]), help(&["--help"]));
    assert_eq!(help(&["help", "run"]), help(&["run", "--help"]));
    assert_eq!(help(&["help", "mcp"]), help(&["mcp", "-h"]));
}

#[test]
fn help_has_commands_flags_and_footer() {
    let root = help(&["help"]);
    for expected in [
        "Usage:  keyguard [command] [flags]",
        "Commands:",
        "  help ",
        "  run ",
        "Flags:",
        "-h, --help",
        "-V, --version",
        "Run 'keyguard [command] --help' for more information on a command.",
    ] {
        assert!(root.contains(expected), "{expected:?} in:\n{root}");
    }
    assert!(!root.contains("Options:"), "{root}");

    let run = help(&["help", "run"]);
    for expected in [
        "Usage:  keyguard run --environment <id> [flags] -- <command>...",
        "Flags:",
        "-e, --environment <id>",
        "--socket <path>",
        "Examples:",
        "keyguard run --environment <id> -- npm start",
    ] {
        assert!(run.contains(expected), "{expected:?} in:\n{run}");
    }
}

#[test]
fn help_rejects_an_unknown_command() {
    let output = keyguard(&["help", "nope"]);
    assert_eq!(output.status.code(), Some(2), "{output:?}");
    assert!(String::from_utf8_lossy(&output.stderr).contains("nope"));
}
