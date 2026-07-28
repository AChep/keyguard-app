//! Tests for parsing `known_hosts` files.

#![cfg(all(feature = "ecdsa", feature = "std"))]

use ssh_key::known_hosts::{HostPatterns, KnownHosts, Marker};

// TODO(tarcieri): test file permissions
#[test]
fn read_example_file() {
    let known_hosts = KnownHosts::read_file("./tests/examples/known_hosts").unwrap();
    assert_eq!(known_hosts.len(), 4);

    assert_eq!(known_hosts[0].marker(), None);
    assert_eq!(
        known_hosts[0].host_patterns(),
        &HostPatterns::Patterns(vec!["test.example.com".to_string()])
    );
    assert_eq!(
        known_hosts[0].public_key().to_string(),
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti"
    );
    assert_eq!(known_hosts[0].public_key().comment(), "");

    assert_eq!(known_hosts[1].marker(), None);
    assert_eq!(
        known_hosts[1].host_patterns(),
        &HostPatterns::Patterns(vec![
            "cvs.example.net".to_string(),
            "!test.example.???".to_string(),
            "[*.example.net]:999".to_string(),
        ])
    );
    assert_eq!(known_hosts[1].public_key().to_string(), "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHwf2HMM5TRXvo2SQJjsNkiDD5KqiiNjrGVv3UUh+mMT5RHxiRtOnlqvjhQtBq0VpmpCV/PwUdhOig4vkbqAcEc= example.com");
    assert_eq!(known_hosts[1].public_key().comment(), "example.com");

    assert_eq!(known_hosts[2].marker(), Some(&Marker::Revoked));
    assert_eq!(
        known_hosts[2].host_patterns(),
        &HostPatterns::HashedName {
            salt: vec![
                37, 242, 147, 116, 24, 123, 172, 214, 215, 145, 80, 16, 9, 26, 120, 57, 10, 15,
                126, 98
            ],
            hash: [
                81, 33, 2, 175, 116, 150, 127, 82, 84, 62, 201, 172, 228, 10, 159, 15, 148, 31,
                198, 67
            ],
        }
    );
    assert_eq!(known_hosts[2].public_key().to_string(), "ssh-dss AAAAB3NzaC1kc3MAAACBANw9iSUO2UYhFMssjUgW46URqv8bBrDgHeF8HLBOWBvKuXF2Rx2J/XyhgX48SOLMuv0hcPaejlyLarabnF9F2V4dkpPpZSJ+7luHmxEjNxwhsdtg8UteXAWkeCzrQ6MvRJZHcDBjYh56KGvslbFnJsGLXlI4PQCyl6awNImwYGilAAAAFQCJGBU3hZf+QtP9Jh/nbfNlhFu7hwAAAIBHObOQioQVRm3HsVb7mOy3FVKhcLoLO3qoG9gTkd4KeuehtFAC3+rckiX7xSCnE/5BBKdL7VP9WRXac2Nlr9Pwl3e7zPut96wrCHt/TZX6vkfXKkbpUIj5zSqfvyNrWKaYJkfzwAQwrXNS1Hol676Ud/DDEn2oatdEhkS3beWHXAAAAIBgQqaz/YYTRMshzMzYcZ4lqgvgmA55y6v0h39e8HH2A5dwNS6sPUw2jyna+le0dceNRJifFld1J+WYM0vmquSr11DDavgEidOSaXwfMvPPPJqLmbzdtT16N+Gij9U9STQTHPQcQ3xnNNHgQAStzZJbhLOVbDDDo5BO7LMUALDfSA==");
    assert_eq!(known_hosts[2].public_key().comment(), "");

    assert_eq!(known_hosts[3].marker(), Some(&Marker::CertAuthority));
    assert_eq!(
        known_hosts[3].host_patterns(),
        &HostPatterns::Patterns(vec!["*.example.com".to_string()])
    );
    assert_eq!(known_hosts[3].public_key().to_string(), "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQC0WRHtxuxefSJhpIxGq4ibGFgwYnESPm8C3JFM88A1JJLoprenklrd7VJ+VH3Ov/bQwZwLyRU5dRmfR/SWTtIPWs7tToJVayKKDB+/qoXmM5ui/0CU2U4rCdQ6PdaCJdC7yFgpPL8WexjWN06+eSIKYz1AAXbx9rRv1iasslK/KUqtsqzVliagI6jl7FPO2GhRZMcso6LsZGgSxuYf/Lp0D/FcBU8GkeOo1Sx5xEt8H8bJcErtCe4Blb8JxcW6EXO3sReb4z+zcR07gumPgFITZ6hDA8sSNuvo/AlWg0IKTeZSwHHVknWdQqDJ0uczE837caBxyTZllDNIGkBjCIIOFzuTT76HfYc/7CTTGk07uaNkUFXKN79xDiFOX8JQ1ZZMZvGOTwWjuT9CqgdTvQRORbRWwOYv3MH8re9ykw3Ip6lrPifY7s6hOaAKry/nkGPMt40m1TdiW98MTIpooE7W+WXu96ax2l2OJvxX8QR7l+LFlKnkIEEJd/ItF1G22UmOjkVwNASTwza/hlY+8DoVvEmwum/nMgH2TwQT3bTQzF9s9DOJkH4d8p4Mw4gEDjNx0EgUFA91ysCAeUMQQyIvuR8HXXa+VcvhOOO5mmBcVhxJ3qUOJTyDBsT0932Zb4mNtkxdigoVxu+iiwk0vwtvKwGVDYdyMP5EAQeEIP1t0w== authority@example.com");
    assert_eq!(
        known_hosts[3].public_key().comment(),
        "authority@example.com"
    );
}
