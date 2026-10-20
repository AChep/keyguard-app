use std::io::{Error, Result};
use std::path::PathBuf;

fn main() -> Result<()> {
    let manifest_dir = std::env::var_os("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .ok_or_else(|| Error::other("CARGO_MANIFEST_DIR is not set"))?;
    let proto_dir = manifest_dir.join("../proto");
    let proto_file = proto_dir.join("cli_agent.proto");
    let caller_proto_dir = manifest_dir.join("../../commonAgent/proto");

    println!("cargo:rerun-if-changed={}", proto_file.display());
    println!(
        "cargo:rerun-if-changed={}",
        caller_proto_dir.join("caller.proto").display()
    );

    prost_build::Config::new()
        // keyguard-agent-proto owns the generated caller types.
        .extern_path(".keyguard.agent", "::keyguard_agent_proto::proto")
        // Sorted maps encode the same bytes every time, as the fixtures pin.
        .btree_map(["."])
        .compile_protos(&[proto_file], &[proto_dir, caller_proto_dir])?;
    Ok(())
}
