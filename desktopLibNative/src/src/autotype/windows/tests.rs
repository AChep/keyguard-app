use super::*;
use std::collections::VecDeque;

struct FakePlatform {
    window: HWND,
    now: Duration,
    release_modifiers_at: Duration,
    press_modifier_at: Option<Duration>,
    change_window_at: Option<Duration>,
    foreground_reads: usize,
    batches: Vec<Vec<KeyEvent>>,
    results: VecDeque<usize>,
    sleeps: Vec<Duration>,
}

impl Default for FakePlatform {
    fn default() -> Self {
        Self {
            window: HWND(std::ptr::without_provenance_mut(1)),
            now: Duration::ZERO,
            release_modifiers_at: Duration::ZERO,
            press_modifier_at: None,
            change_window_at: None,
            foreground_reads: 0,
            batches: Vec::new(),
            results: VecDeque::new(),
            sleeps: Vec::new(),
        }
    }
}

impl InputPlatform for FakePlatform {
    fn foreground_window(&mut self) -> HWND {
        self.foreground_reads += 1;
        if self.change_window_at.is_some_and(|time| self.now >= time) {
            HWND(std::ptr::without_provenance_mut(2))
        } else {
            self.window
        }
    }

    fn modifiers_pressed(&mut self) -> bool {
        self.now < self.release_modifiers_at
            || self.press_modifier_at.is_some_and(|time| self.now >= time)
    }

    fn send_input(&mut self, events: &[KeyEvent]) -> usize {
        self.batches.push(events.to_vec());
        self.results.pop_front().unwrap_or(events.len())
    }

    fn elapsed(&self) -> Duration {
        self.now
    }

    fn sleep(&mut self, duration: Duration) {
        self.sleeps.push(duration);
        self.now += duration;
    }
}

fn run(payload: &str, platform: &mut FakePlatform) -> Result<(), String> {
    execute_with(payload, platform, &Mutex::new(()))
}

fn pair(key: Key) -> Vec<KeyEvent> {
    vec![
        KeyEvent { key, key_up: false },
        KeyEvent { key, key_up: true },
    ]
}

#[test]
fn types_unicode_and_literal_sequence_syntax_without_layout_mapping() {
    let ascii: String = (' '..='~').collect();
    for text in [
        ascii.as_str(),
        "Привіт, ҐЄІЇ!",
        "Äé",
        "中文",
        "e\u{0301}",
        "{TAB}^+%",
    ] {
        let mut platform = FakePlatform::default();
        run(text, &mut platform).unwrap();
        let expected: Vec<_> = text
            .encode_utf16()
            .map(|unit| pair(Key::Unicode(unit)))
            .collect();
        assert_eq!(platform.batches, expected);
        assert_eq!(
            platform.sleeps,
            vec![CHARACTER_DELAY; text.chars().count() - 1]
        );
    }
}

#[test]
fn sends_surrogate_key_downs_together_before_their_releases() {
    let mut platform = FakePlatform::default();
    run("😀", &mut platform).unwrap();
    assert_eq!(
        platform.batches,
        vec![vec![
            KeyEvent {
                key: Key::Unicode(0xd83d),
                key_up: false
            },
            KeyEvent {
                key: Key::Unicode(0xde00),
                key_up: false
            },
            KeyEvent {
                key: Key::Unicode(0xd83d),
                key_up: true
            },
            KeyEvent {
                key: Key::Unicode(0xde00),
                key_up: true
            },
        ]]
    );
    assert!(platform.sleeps.is_empty());
}

#[test]
fn controls_use_virtual_keys_and_crlf_remains_two_enters() {
    let mut platform = FakePlatform::default();
    run("\t\u{0008}\r\n", &mut platform).unwrap();
    assert_eq!(
        platform.batches,
        [VK_TAB, VK_BACK, VK_RETURN, VK_RETURN].map(|key| pair(Key::Virtual(key)))
    );
}

#[test]
fn native_inputs_preserve_unicode_units_flags_and_control_keys() {
    for (key, vk, scan, flags) in [
        (
            Key::Unicode(0xd83d),
            VIRTUAL_KEY(0),
            0xd83d,
            KEYEVENTF_UNICODE,
        ),
        (
            Key::Unicode(0xde00),
            VIRTUAL_KEY(0),
            0xde00,
            KEYEVENTF_UNICODE,
        ),
        (Key::Virtual(VK_TAB), VK_TAB, 0, Default::default()),
        (Key::Virtual(VK_BACK), VK_BACK, 0, Default::default()),
        (Key::Virtual(VK_RETURN), VK_RETURN, 0, Default::default()),
    ] {
        for event in pair(key) {
            let input = event.input();
            assert_eq!(input.r#type, INPUT_KEYBOARD);
            // SAFETY: KeyEvent::input initializes the keyboard member of the union.
            let keyboard = unsafe { input.Anonymous.ki };
            assert_eq!(keyboard.wVk, vk);
            assert_eq!(keyboard.wScan, scan);
            assert_eq!(
                keyboard.dwFlags,
                if event.key_up {
                    flags | KEYEVENTF_KEYUP
                } else {
                    flags
                }
            );
            assert_eq!(keyboard.time, 0);
            assert_eq!(keyboard.dwExtraInfo, 0);
        }
    }
}

#[test]
fn rejects_controls_anywhere_in_payload_before_accessing_windows() {
    for control in ['\0', '\u{0001}', '\u{001b}', '\u{007f}', '\u{0085}'] {
        let mut platform = FakePlatform::default();
        let secret = format!("secret{control}tail");
        let error = run(&secret, &mut platform).unwrap_err();
        assert!(!error.contains("secret"));
        assert_eq!(platform.foreground_reads, 0);
        assert!(platform.batches.is_empty());
        assert!(platform.sleeps.is_empty());
    }
}

#[test]
fn empty_input_does_not_access_windows_even_if_busy() {
    let gate = Mutex::new(());
    let _held = gate.lock().unwrap();
    let mut platform = FakePlatform::default();
    execute_with("", &mut platform, &gate).unwrap();
    assert_eq!(platform.foreground_reads, 0);
    assert!(platform.batches.is_empty());
    assert!(platform.sleeps.is_empty());
}

#[test]
fn requires_a_foreground_window() {
    let mut platform = FakePlatform {
        window: HWND::default(),
        ..Default::default()
    };
    assert!(run("secret", &mut platform).is_err());
    assert!(platform.batches.is_empty());
}

#[test]
fn waits_for_modifiers_then_types_without_changing_modifier_state() {
    let mut platform = FakePlatform {
        release_modifiers_at: Duration::from_millis(30),
        ..Default::default()
    };
    run("a", &mut platform).unwrap();
    assert_eq!(platform.sleeps, vec![MODIFIER_POLL_INTERVAL; 3]);
    assert_eq!(platform.batches, vec![pair(Key::Unicode('a' as u16))]);
}

#[test]
fn held_modifiers_timeout_without_typing() {
    let mut platform = FakePlatform {
        release_modifiers_at: Duration::from_secs(10),
        ..Default::default()
    };
    assert!(run("secret", &mut platform).is_err());
    assert_eq!(platform.now, MODIFIER_TIMEOUT);
    assert!(platform.batches.is_empty());
}

#[test]
fn focus_changes_while_waiting_abort_without_typing() {
    let mut platform = FakePlatform {
        release_modifiers_at: Duration::from_millis(50),
        change_window_at: Some(Duration::from_millis(10)),
        ..Default::default()
    };
    assert!(run("secret", &mut platform).is_err());
    assert!(platform.batches.is_empty());
    assert_eq!(platform.now, MODIFIER_POLL_INTERVAL);
}

#[test]
fn focus_changes_or_new_modifiers_stop_before_next_character() {
    for modifier in [false, true] {
        let mut platform = FakePlatform::default();
        if modifier {
            platform.press_modifier_at = Some(CHARACTER_DELAY);
        } else {
            platform.change_window_at = Some(CHARACTER_DELAY);
        }
        assert!(run("abc", &mut platform).is_err());
        assert_eq!(platform.batches, vec![pair(Key::Unicode('a' as u16))]);
        assert_eq!(platform.sleeps, vec![CHARACTER_DELAY]);
    }
}

#[test]
fn partial_delivery_releases_only_unmatched_accepted_down_and_never_continues() {
    for (text, batch, unmatched) in [
        (
            "a😀b",
            vec![
                KeyEvent {
                    key: Key::Unicode(0xd83d),
                    key_up: false,
                },
                KeyEvent {
                    key: Key::Unicode(0xde00),
                    key_up: false,
                },
                KeyEvent {
                    key: Key::Unicode(0xd83d),
                    key_up: true,
                },
                KeyEvent {
                    key: Key::Unicode(0xde00),
                    key_up: true,
                },
            ],
            vec![
                vec![],
                vec![Key::Unicode(0xd83d)],
                vec![Key::Unicode(0xd83d), Key::Unicode(0xde00)],
                vec![Key::Unicode(0xde00)],
            ],
        ),
        (
            "a\tb",
            pair(Key::Virtual(VK_TAB)),
            vec![vec![], vec![Key::Virtual(VK_TAB)]],
        ),
    ] {
        assert_eq!(unmatched.len(), batch.len());
        for (accepted, unmatched_keys) in unmatched.iter().enumerate() {
            let mut platform = FakePlatform {
                results: VecDeque::from([2, accepted]),
                ..Default::default()
            };
            let error = run(text, &mut platform).unwrap_err();
            assert_eq!(error, "Failed to send AutoType input.");
            let mut expected = vec![pair(Key::Unicode('a' as u16)), batch.clone()];
            if !unmatched_keys.is_empty() {
                expected.push(
                    unmatched_keys
                        .iter()
                        .map(|&key| KeyEvent { key, key_up: true })
                        .collect(),
                );
            }
            assert_eq!(platform.batches, expected);
            assert_eq!(platform.sleeps, vec![CHARACTER_DELAY]);
        }
    }
}

#[test]
fn cleanup_failure_does_not_retry_or_mask_failure() {
    let mut platform = FakePlatform {
        results: VecDeque::from([1, 0]),
        ..Default::default()
    };
    assert!(run("ab", &mut platform).is_err());
    assert_eq!(platform.batches.len(), 2);
    assert_eq!(
        platform.batches[1],
        vec![KeyEvent {
            key: Key::Unicode('a' as u16),
            key_up: true
        }]
    );
    assert!(platform.sleeps.is_empty());
}

#[test]
fn concurrent_calls_are_rejected_without_waiting_or_typing() {
    let gate = Mutex::new(());
    let held = gate.lock().unwrap();
    thread::scope(|scope| {
        scope
            .spawn(|| {
                let mut platform = FakePlatform::default();
                assert!(execute_with("secret", &mut platform, &gate).is_err());
                assert_eq!(platform.foreground_reads, 0);
                assert!(platform.batches.is_empty());
            })
            .join()
            .unwrap();
    });
    drop(held);
    execute_with("a", &mut FakePlatform::default(), &gate).unwrap();
}

#[test]
fn failed_execution_releases_gate_for_next_call() {
    let gate = Mutex::new(());
    let mut platform = FakePlatform {
        results: VecDeque::from([0]),
        ..Default::default()
    };
    assert!(execute_with("a", &mut platform, &gate).is_err());
    execute_with("b", &mut platform, &gate).unwrap();
    assert_eq!(
        platform.batches.last(),
        Some(&pair(Key::Unicode('b' as u16)))
    );
}
