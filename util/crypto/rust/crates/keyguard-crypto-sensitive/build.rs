//! Fails the build unless aws-lc-sys compiled its bundled AWS-LC.

use std::env;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    // aws-lc-sys exports its `links` metadata as DEP_AWS_LC_<version>_*. A
    // source build names libcrypto after its symbol prefix,
    // `aws_lc_<version>_crypto`, while an installed AWS-LC keeps its own name,
    // such as `crypto`.
    let (version, libcrypto) = env::vars()
        .find_map(|(name, value)| {
            let version = name
                .strip_prefix("DEP_AWS_LC_")?
                .strip_suffix("_LIBCRYPTO")?;
            Some((version.to_ascii_lowercase(), value))
        })
        .expect("aws-lc-sys did not export its libcrypto name");
    let expected = format!("aws_lc_{version}_crypto");
    if libcrypto != expected {
        panic!(
            "aws-lc-sys linked `{libcrypto}` instead of its bundled `{expected}`. Run cargo \
             inside the repository so .cargo/config.toml sets AWS_LC_SYS_USE_SYSTEM=0, and \
             unset AWS_LC_SYS_SYSTEM_DIR and AWS_LC_SYS_NO_PREFIX."
        );
    }
}
