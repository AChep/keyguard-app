use std::io::{Error, Result};
use std::path::PathBuf;

fn main() -> Result<()> {
    let manifest_dir = std::env::var_os("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .ok_or_else(|| Error::other("CARGO_MANIFEST_DIR is not set"))?;
    let proto_dir = manifest_dir.join("proto");
    let proto_file = proto_dir.join("ssh_agent.proto");
    let caller_proto_dir = manifest_dir.join("../commonAgent/proto");

    println!("cargo:rerun-if-changed={}", proto_file.display());
    println!("cargo:rerun-if-changed={}", proto_dir.display());
    for shared in ["caller.proto", "agent.proto"] {
        println!(
            "cargo:rerun-if-changed={}",
            caller_proto_dir.join(shared).display()
        );
    }

    prost_build::Config::new()
        // keyguard-agent-proto owns the generated shared types.
        .extern_path(".keyguard.agent", "::keyguard_agent_proto::proto")
        .compile_protos(&[proto_file], &[proto_dir, caller_proto_dir])?;
    Ok(())
}
