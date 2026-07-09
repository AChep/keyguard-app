fn main() {
    prost_build::compile_protos(&["proto/gpg_agent.proto"], &["proto"])
        .expect("failed to compile gpg_agent.proto");
}
