//! Subprocess fixture for process and cross-language interoperability tests.
use keyguard_instance_core::bridge;
use std::io::{self, BufRead, Write};

fn line(text: &str) {
    println!("{text}");
    let _ = io::stdout().flush();
}

fn main() {
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    if arguments.len() != 4 {
        line("ERROR -1");
        return;
    }
    let timeout = arguments[3].parse().unwrap_or(0);
    let handle = bridge::acquire_or_activate(&arguments[0], &arguments[1], &arguments[2], timeout);
    if handle <= 0 {
        if handle == 0 {
            line("ACTIVATED");
        } else {
            line(&format!("ERROR {handle}"));
        }
        return;
    }
    line("PRIMARY");
    let handle = handle as u64;
    let input = std::thread::spawn(move || {
        for command in io::stdin().lock().lines() {
            match command.as_deref() {
                Ok("stop") => {
                    bridge::stop(handle);
                }
                Ok("close") | Err(_) => break,
                _ => {}
            }
        }
        bridge::close(handle);
    });
    loop {
        match bridge::wait_event(handle) {
            1 => line("ACTIVATION"),
            0 | -5 => {
                line("STOPPED");
                break;
            }
            failure => {
                line(&format!("ERROR {failure}"));
                break;
            }
        }
    }
    let _ = input.join();
}
