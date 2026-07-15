fn main() {
    if let Err(error) = native_crypto_policy::run(std::env::args_os().skip(1)) {
        eprintln!("ERROR: {error:#}");
        std::process::exit(1);
    }
}
