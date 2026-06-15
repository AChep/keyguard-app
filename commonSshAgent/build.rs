use std::io::{Error, Result};
use std::path::PathBuf;

fn main() -> Result<()> {
    let manifest_dir = std::env::var_os("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .ok_or_else(|| Error::other("CARGO_MANIFEST_DIR is not set"))?;
    let proto_dir = manifest_dir.join("proto");
    let proto_file = proto_dir.join("ssh_agent.proto");

    println!("cargo:rerun-if-changed={}", proto_file.display());
    println!("cargo:rerun-if-changed={}", proto_dir.display());

    prost_build::compile_protos(&[proto_file], &[proto_dir])?;
    Ok(())
}
