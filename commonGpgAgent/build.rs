fn main() {
    println!("cargo:rerun-if-changed=proto");
    println!("cargo:rerun-if-changed=../commonAgent/proto/caller.proto");
    prost_build::Config::new()
        // keyguard-agent-proto owns the generated caller types.
        .extern_path(".keyguard.agent", "::keyguard_agent_proto::proto")
        .compile_protos(
            &["proto/gpg_agent.proto"],
            &["proto", "../commonAgent/proto"],
        )
        .expect("failed to compile gpg_agent.proto");
}
