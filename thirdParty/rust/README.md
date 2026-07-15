This directory is the single authoritative home for patched third-party Rust
crates shared by Keyguard modules. A fork lives here instead of under a
consumer module so its ownership, provenance, and review requirements remain
independent of the current dependency graph.

Each `*-keyguard` directory must retain its upstream licenses,
`.cargo_vcs_info.json`, byte-exact `Cargo.toml.orig`, and
`KEYGUARD-PROVENANCE.md`. The effective `Cargo.toml` and local source delta are
reviewed code; do not replace them with a registry extraction or duplicate the
fork under a consumer module.

Consumer workspaces select features and patches in their own manifests. A
change to any fork must run every consumer's relevant Cargo, security-policy,
and cross-target checks described by that fork's provenance record.

The workspace in this directory exists only to lint and test the reviewed
forks together. Application feature selection remains owned by each consumer
workspace and is separately locked by its `Cargo.lock` and dependency policy.
The forks retain their upstream lint attributes; consumer-owned code continues
to use the repository's stricter warnings-as-errors policy.
