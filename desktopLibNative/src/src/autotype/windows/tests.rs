use super::*;
use std::collections::VecDeque;

/// Sends one character. Each batch accepts the next queued count, or every event.
fn send(ch: char, results: &[usize]) -> (bool, Vec<Vec<KeyEvent>>) {
    let mut results = VecDeque::from(results.to_vec());
    let mut batches = Vec::new();
    let sent = send_character_with(ch, |events| {
        batches.push(events.to_vec());
        results.pop_front().unwrap_or(events.len())
    });
    (sent, batches)
}

fn pair(key: Key) -> Vec<KeyEvent> {
    vec![
        KeyEvent { key, key_up: false },
        KeyEvent { key, key_up: true },
    ]
}

fn surrogate_batch() -> Vec<KeyEvent> {
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
        let mut batches = Vec::new();
        for ch in text.chars() {
            let (sent, sent_batches) = send(ch, &[]);
            assert!(sent);
            batches.extend(sent_batches);
        }
        let expected: Vec<_> = text
            .encode_utf16()
            .map(|unit| pair(Key::Unicode(unit)))
            .collect();
        assert_eq!(batches, expected);
    }
}

#[test]
fn sends_surrogate_key_downs_together_before_their_releases() {
    assert_eq!(send('😀', &[]), (true, vec![surrogate_batch()]));
}

#[test]
fn tab_uses_the_virtual_key() {
    assert_eq!(send('\t', &[]), (true, vec![pair(Key::Virtual(VK_TAB))]));
}

#[test]
fn native_inputs_preserve_unicode_units_flags_and_tab() {
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
fn partial_delivery_releases_only_unmatched_accepted_downs() {
    for (ch, batch, unmatched) in [
        (
            '😀',
            surrogate_batch(),
            vec![
                vec![],
                vec![Key::Unicode(0xd83d)],
                vec![Key::Unicode(0xd83d), Key::Unicode(0xde00)],
                vec![Key::Unicode(0xde00)],
            ],
        ),
        (
            '\t',
            pair(Key::Virtual(VK_TAB)),
            vec![vec![], vec![Key::Virtual(VK_TAB)]],
        ),
    ] {
        assert_eq!(unmatched.len(), batch.len());
        for (accepted, unmatched_keys) in unmatched.iter().enumerate() {
            let mut expected = vec![batch.clone()];
            if !unmatched_keys.is_empty() {
                expected.push(
                    unmatched_keys
                        .iter()
                        .map(|&key| KeyEvent { key, key_up: true })
                        .collect(),
                );
            }
            assert_eq!(send(ch, &[accepted]), (false, expected));
        }
    }
}

#[test]
fn cleanup_failure_does_not_retry_or_mask_failure() {
    let (sent, batches) = send('a', &[1, 0]);
    assert!(!sent);
    assert_eq!(
        batches,
        vec![
            pair(Key::Unicode('a' as u16)),
            vec![KeyEvent {
                key: Key::Unicode('a' as u16),
                key_up: true
            }],
        ]
    );
}

#[test]
fn ignores_only_injected_input_and_keys_without_a_reliable_key_up() {
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        VK_CAPITAL, VK_CONTROL, VK_DBE_HIRAGANA, VK_DBE_KATAKANA, VK_HANGUL, VK_KANJI, VK_LBUTTON,
        VK_LWIN, VK_MENU, VK_OEM_AUTO, VK_OEM_ENLW, VK_RETURN, VK_SHIFT, VK_XBUTTON2,
    };
    for key in [
        VK_PACKET,
        VK_HANGUL,
        VK_KANJI,
        VK_DBE_ALPHANUMERIC,
        VK_DBE_KATAKANA,
        VK_DBE_HIRAGANA,
        VK_OEM_AUTO,
        VK_OEM_ENLW,
        VK_DBE_NOROMAN,
    ] {
        assert!(ignored(key), "{key:?}");
    }
    for key in [
        VK_LBUTTON,
        VK_XBUTTON2,
        VK_TAB,
        VK_RETURN,
        VK_SHIFT,
        VK_CONTROL,
        VK_MENU,
        VK_CAPITAL,
        VK_LWIN,
        VIRTUAL_KEY(u16::from(b'V')),
    ] {
        assert!(!ignored(key), "{key:?}");
    }
}
