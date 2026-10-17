use std::io::{Error, Result};
use std::path::PathBuf;

fn main() -> Result<()> {
    let manifest_dir = std::env::var_os("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .ok_or_else(|| Error::other("CARGO_MANIFEST_DIR is not set"))?;
    let proto_file = manifest_dir.join("caller.proto");

    println!("cargo:rerun-if-changed={}", proto_file.display());

    prost_build::compile_protos(&[proto_file], &[manifest_dir])?;
    Ok(())
}
