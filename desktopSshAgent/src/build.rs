use std::io::Result;

fn main() -> Result<()> {
    prost_build::compile_protos(&["proto/ssh_agent.proto"], &["proto/"])?;
    Ok(())
}
