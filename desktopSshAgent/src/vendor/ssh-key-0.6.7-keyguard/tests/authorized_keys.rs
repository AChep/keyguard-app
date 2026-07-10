//! Tests for parsing `authorized_keys` files.

#![cfg(all(feature = "ecdsa", feature = "std"))]

use ssh_key::AuthorizedKeys;

// TODO(tarcieri): test file permissions
#[test]
fn read_example_file() {
    let authorized_keys = AuthorizedKeys::read_file("./tests/examples/authorized_keys").unwrap();
    assert_eq!(authorized_keys.len(), 5);

    assert_eq!(authorized_keys[0].config_opts().to_string(), "");
    assert_eq!(
        authorized_keys[0].public_key().to_string(),
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti"
    );
    assert_eq!(authorized_keys[0].public_key().comment(), "");

    assert_eq!(
        authorized_keys[1].config_opts().to_string(),
        "command=\"/usr/bin/date\""
    );
    assert_eq!(authorized_keys[1].public_key().to_string(), "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHwf2HMM5TRXvo2SQJjsNkiDD5KqiiNjrGVv3UUh+mMT5RHxiRtOnlqvjhQtBq0VpmpCV/PwUdhOig4vkbqAcEc= user2@example.com");
    assert_eq!(
        authorized_keys[1].public_key().comment(),
        "user2@example.com"
    );

    assert_eq!(
        authorized_keys[2].config_opts().to_string(),
        "environment=\"PATH=/bin:/usr/bin\""
    );
    assert_eq!(authorized_keys[2].public_key().to_string(), "ssh-dss AAAAB3NzaC1kc3MAAACBANw9iSUO2UYhFMssjUgW46URqv8bBrDgHeF8HLBOWBvKuXF2Rx2J/XyhgX48SOLMuv0hcPaejlyLarabnF9F2V4dkpPpZSJ+7luHmxEjNxwhsdtg8UteXAWkeCzrQ6MvRJZHcDBjYh56KGvslbFnJsGLXlI4PQCyl6awNImwYGilAAAAFQCJGBU3hZf+QtP9Jh/nbfNlhFu7hwAAAIBHObOQioQVRm3HsVb7mOy3FVKhcLoLO3qoG9gTkd4KeuehtFAC3+rckiX7xSCnE/5BBKdL7VP9WRXac2Nlr9Pwl3e7zPut96wrCHt/TZX6vkfXKkbpUIj5zSqfvyNrWKaYJkfzwAQwrXNS1Hol676Ud/DDEn2oatdEhkS3beWHXAAAAIBgQqaz/YYTRMshzMzYcZ4lqgvgmA55y6v0h39e8HH2A5dwNS6sPUw2jyna+le0dceNRJifFld1J+WYM0vmquSr11DDavgEidOSaXwfMvPPPJqLmbzdtT16N+Gij9U9STQTHPQcQ3xnNNHgQAStzZJbhLOVbDDDo5BO7LMUALDfSA== user3@example.com");
    assert_eq!(
        authorized_keys[2].public_key().comment(),
        "user3@example.com"
    );

    assert_eq!(
        authorized_keys[3].config_opts().to_string(),
        "from=\"10.0.0.?,*.example.com\",no-X11-forwarding"
    );
    assert_eq!(authorized_keys[3].public_key().to_string(), "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQC0WRHtxuxefSJhpIxGq4ibGFgwYnESPm8C3JFM88A1JJLoprenklrd7VJ+VH3Ov/bQwZwLyRU5dRmfR/SWTtIPWs7tToJVayKKDB+/qoXmM5ui/0CU2U4rCdQ6PdaCJdC7yFgpPL8WexjWN06+eSIKYz1AAXbx9rRv1iasslK/KUqtsqzVliagI6jl7FPO2GhRZMcso6LsZGgSxuYf/Lp0D/FcBU8GkeOo1Sx5xEt8H8bJcErtCe4Blb8JxcW6EXO3sReb4z+zcR07gumPgFITZ6hDA8sSNuvo/AlWg0IKTeZSwHHVknWdQqDJ0uczE837caBxyTZllDNIGkBjCIIOFzuTT76HfYc/7CTTGk07uaNkUFXKN79xDiFOX8JQ1ZZMZvGOTwWjuT9CqgdTvQRORbRWwOYv3MH8re9ykw3Ip6lrPifY7s6hOaAKry/nkGPMt40m1TdiW98MTIpooE7W+WXu96ax2l2OJvxX8QR7l+LFlKnkIEEJd/ItF1G22UmOjkVwNASTwza/hlY+8DoVvEmwum/nMgH2TwQT3bTQzF9s9DOJkH4d8p4Mw4gEDjNx0EgUFA91ysCAeUMQQyIvuR8HXXa+VcvhOOO5mmBcVhxJ3qUOJTyDBsT0932Zb4mNtkxdigoVxu+iiwk0vwtvKwGVDYdyMP5EAQeEIP1t0w== user4@example.com");
    assert_eq!(
        authorized_keys[3].public_key().comment(),
        "user4@example.com"
    );

    assert_eq!(authorized_keys[4].public_key().to_string(), "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBN76zuqnjypL54/w4763l7q1Sn3IBYHptJ5wcYfEWkzeNTvpexr05Z18m2yPT2SWRd1JJ8Aj5TYidG9MdSS5J78= hello world this is a long comment");
    assert_eq!(
        authorized_keys[4].public_key().comment(),
        "hello world this is a long comment"
    );
}
