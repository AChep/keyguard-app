use crate::platform;
use std::thread;
use std::time::Duration;

const KEY_PRESS_DELAY_MS: Duration = Duration::from_millis(10);
type KeyEventFlags = u64;
const KEY_EVENT_FLAG_NONE: KeyEventFlags = 0;
const KEY_EVENT_FLAG_SHIFT: KeyEventFlags = 1 << 17;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct KeyEvent {
    key_code: u16,
    key_down: bool,
    flags: KeyEventFlags,
}

impl KeyEvent {
    fn down(key_code: u16) -> Self {
        Self {
            key_code,
            key_down: true,
            flags: KEY_EVENT_FLAG_NONE,
        }
    }

    fn up(key_code: u16) -> Self {
        Self {
            key_code,
            key_down: false,
            flags: KEY_EVENT_FLAG_NONE,
        }
    }

    fn down_with_flags(key_code: u16, flags: KeyEventFlags) -> Self {
        Self {
            key_code,
            key_down: true,
            flags,
        }
    }

    fn up_with_flags(key_code: u16, flags: KeyEventFlags) -> Self {
        Self {
            key_code,
            key_down: false,
            flags,
        }
    }
}

pub(crate) fn execute(payload: &str) -> Result<(), String> {
    let events = compile_events(payload)?;
    emit_events(&events)
}

fn compile_events(payload: &str) -> Result<Vec<KeyEvent>, String> {
    let mut events = Vec::with_capacity(payload.len() * 2);
    for ch in payload.chars() {
        let key_code = key_code_for_char(ch)
            .ok_or_else(|| "Payload contains an unsupported character.".to_owned())?;
        let flags = if requires_shift_press(ch) {
            KEY_EVENT_FLAG_SHIFT
        } else {
            KEY_EVENT_FLAG_NONE
        };
        events.push(KeyEvent::down_with_flags(key_code, flags));
        events.push(KeyEvent::up_with_flags(key_code, flags));
    }
    Ok(events)
}

fn emit_events(events: &[KeyEvent]) -> Result<(), String> {
    emit_events_with(events, platform::autotype::post_keyboard_event, || {
        thread::sleep(KEY_PRESS_DELAY_MS)
    })
}

fn emit_events_with(
    events: &[KeyEvent],
    mut post_keyboard_event: impl FnMut(u16, bool, KeyEventFlags) -> bool,
    mut sleep: impl FnMut(),
) -> Result<(), String> {
    let mut pressed_keys: Vec<(u16, KeyEventFlags)> = Vec::new();
    for event in events {
        if !post_keyboard_event(event.key_code, event.key_down, event.flags) {
            release_pressed_keys_with(&mut pressed_keys, &mut post_keyboard_event, &mut sleep);
            return Err(format!(
                "Failed to emit key event for code {}",
                event.key_code
            ));
        }

        if event.key_down {
            pressed_keys.push((event.key_code, event.flags));
        } else if let Some(index) = pressed_keys
            .iter()
            .rposition(|(key_code, flags)| *key_code == event.key_code && *flags == event.flags)
        {
            pressed_keys.remove(index);
        }

        sleep();
    }

    Ok(())
}

fn release_pressed_keys_with(
    pressed_keys: &mut Vec<(u16, KeyEventFlags)>,
    mut post_keyboard_event: impl FnMut(u16, bool, KeyEventFlags) -> bool,
    mut sleep: impl FnMut(),
) {
    for (key_code, flags) in pressed_keys.iter().rev().copied() {
        let _ = post_keyboard_event(key_code, false, flags);
        sleep();
    }
    pressed_keys.clear();
}

fn requires_shift_press(ch: char) -> bool {
    ch.is_uppercase()
        || matches!(
            ch,
            '~' | '!'
                | '@'
                | '#'
                | '$'
                | '%'
                | '^'
                | '&'
                | '*'
                | '('
                | ')'
                | '_'
                | '+'
                | '{'
                | '}'
                | '|'
                | ':'
                | '"'
                | '<'
                | '>'
                | '?'
        )
}

fn key_code_for_char(ch: char) -> Option<u16> {
    let normalized = if ch.is_ascii_alphabetic() {
        ch.to_ascii_lowercase()
    } else {
        ch
    };

    match normalized {
        'a' => Some(0x00),
        's' => Some(0x01),
        'd' => Some(0x02),
        'f' => Some(0x03),
        'h' => Some(0x04),
        'g' => Some(0x05),
        'z' => Some(0x06),
        'x' => Some(0x07),
        'c' => Some(0x08),
        'v' => Some(0x09),
        'b' => Some(0x0b),
        'q' => Some(0x0c),
        'w' => Some(0x0d),
        'e' => Some(0x0e),
        'r' => Some(0x0f),
        'y' => Some(0x10),
        't' => Some(0x11),
        '1' | '!' => Some(0x12),
        '2' | '@' => Some(0x13),
        '3' | '#' => Some(0x14),
        '4' | '$' => Some(0x15),
        '6' | '^' => Some(0x16),
        '5' | '%' => Some(0x17),
        '=' | '+' => Some(0x18),
        '9' | '(' => Some(0x19),
        '7' | '&' => Some(0x1a),
        '-' | '_' => Some(0x1b),
        '8' | '*' => Some(0x1c),
        '0' | ')' => Some(0x1d),
        ']' | '}' => Some(0x1e),
        'o' => Some(0x1f),
        'u' => Some(0x20),
        '[' | '{' => Some(0x21),
        'i' => Some(0x22),
        'p' => Some(0x23),
        '\r' | '\n' => Some(0x24),
        'l' => Some(0x25),
        'j' => Some(0x26),
        '\'' | '"' => Some(0x27),
        'k' => Some(0x28),
        ';' | ':' => Some(0x29),
        '\\' | '|' => Some(0x2a),
        ',' | '<' => Some(0x2b),
        '/' | '?' => Some(0x2c),
        'n' => Some(0x2d),
        'm' => Some(0x2e),
        '.' | '>' => Some(0x2f),
        '\t' => Some(0x30),
        ' ' => Some(0x31),
        '`' | '~' => Some(0x32),
        '\u{0008}' => Some(0x33),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::{
        compile_events, emit_events_with, key_code_for_char, requires_shift_press, KeyEvent,
        KeyEventFlags, KEY_EVENT_FLAG_NONE, KEY_EVENT_FLAG_SHIFT,
    };
    use crate::autoType;
    use std::ffi::CString;
    use std::sync::{Arc, Mutex};

    const LETTER_KEY_CODES: &[(char, u16)] = &[
        ('a', 0x00),
        ('s', 0x01),
        ('d', 0x02),
        ('f', 0x03),
        ('h', 0x04),
        ('g', 0x05),
        ('z', 0x06),
        ('x', 0x07),
        ('c', 0x08),
        ('v', 0x09),
        ('b', 0x0b),
        ('q', 0x0c),
        ('w', 0x0d),
        ('e', 0x0e),
        ('r', 0x0f),
        ('y', 0x10),
        ('t', 0x11),
        ('o', 0x1f),
        ('u', 0x20),
        ('i', 0x22),
        ('p', 0x23),
        ('l', 0x25),
        ('j', 0x26),
        ('k', 0x28),
        ('n', 0x2d),
        ('m', 0x2e),
    ];

    const DIGIT_KEY_CODES: &[(char, char, u16)] = &[
        ('1', '!', 0x12),
        ('2', '@', 0x13),
        ('3', '#', 0x14),
        ('4', '$', 0x15),
        ('5', '%', 0x17),
        ('6', '^', 0x16),
        ('7', '&', 0x1a),
        ('8', '*', 0x1c),
        ('9', '(', 0x19),
        ('0', ')', 0x1d),
    ];

    const PUNCTUATION_KEY_CODES: &[(char, char, u16)] = &[
        ('=', '+', 0x18),
        ('-', '_', 0x1b),
        (']', '}', 0x1e),
        ('[', '{', 0x21),
        ('\'', '"', 0x27),
        (';', ':', 0x29),
        ('\\', '|', 0x2a),
        (',', '<', 0x2b),
        ('/', '?', 0x2c),
        ('.', '>', 0x2f),
        ('`', '~', 0x32),
    ];

    const CONTROL_KEY_CODES: &[(char, u16)] = &[
        ('\r', 0x24),
        ('\n', 0x24),
        ('\t', 0x30),
        (' ', 0x31),
        ('\u{0008}', 0x33),
    ];

    fn shifted_key_events(key_code: u16) -> Vec<KeyEvent> {
        vec![
            KeyEvent::down_with_flags(key_code, KEY_EVENT_FLAG_SHIFT),
            KeyEvent::up_with_flags(key_code, KEY_EVENT_FLAG_SHIFT),
        ]
    }

    #[test]
    fn maps_every_supported_us_keyboard_character() {
        for &(letter, key_code) in LETTER_KEY_CODES {
            assert_eq!(key_code_for_char(letter), Some(key_code));
            assert_eq!(
                key_code_for_char(letter.to_ascii_uppercase()),
                Some(key_code)
            );
        }

        for &(plain, shifted, key_code) in DIGIT_KEY_CODES {
            assert_eq!(key_code_for_char(plain), Some(key_code));
            assert_eq!(key_code_for_char(shifted), Some(key_code));
        }

        for &(plain, shifted, key_code) in PUNCTUATION_KEY_CODES {
            assert_eq!(key_code_for_char(plain), Some(key_code));
            assert_eq!(key_code_for_char(shifted), Some(key_code));
        }

        for &(ch, key_code) in CONTROL_KEY_CODES {
            assert_eq!(key_code_for_char(ch), Some(key_code));
        }
    }

    #[test]
    fn applies_shift_only_to_shifted_us_characters() {
        for &(letter, _) in LETTER_KEY_CODES {
            assert!(!requires_shift_press(letter));
            assert!(requires_shift_press(letter.to_ascii_uppercase()));
        }

        for &(plain, shifted, _) in DIGIT_KEY_CODES {
            assert!(!requires_shift_press(plain));
            assert!(requires_shift_press(shifted));
        }

        for &(plain, shifted, _) in PUNCTUATION_KEY_CODES {
            assert!(!requires_shift_press(plain));
            assert!(requires_shift_press(shifted));
        }

        for &(ch, _) in CONTROL_KEY_CODES {
            assert!(!requires_shift_press(ch));
        }
    }

    #[test]
    fn compile_events_rejects_unsupported_characters() {
        let error = compile_events("aÄ").unwrap_err();
        assert_eq!(error, "Payload contains an unsupported character.");
        assert!(!error.contains('Ä'));
    }

    #[test]
    fn compile_events_emits_unshifted_characters_as_single_key_presses() {
        assert_eq!(
            compile_events("a1 ").unwrap(),
            vec![
                KeyEvent::down(0x00),
                KeyEvent::up(0x00),
                KeyEvent::down(0x12),
                KeyEvent::up(0x12),
                KeyEvent::down(0x31),
                KeyEvent::up(0x31),
            ]
        );
    }

    #[test]
    fn compile_events_marks_shifted_characters_with_flags() {
        assert_eq!(
            compile_events("A!").unwrap(),
            vec![
                KeyEvent::down_with_flags(0x00, KEY_EVENT_FLAG_SHIFT),
                KeyEvent::up_with_flags(0x00, KEY_EVENT_FLAG_SHIFT),
                KeyEvent::down_with_flags(0x12, KEY_EVENT_FLAG_SHIFT),
                KeyEvent::up_with_flags(0x12, KEY_EVENT_FLAG_SHIFT),
            ]
        );
    }

    #[test]
    fn compile_events_preserves_mixed_us_keyboard_sequence() {
        let expected = [
            vec![KeyEvent::down(0x00), KeyEvent::up(0x00)],
            shifted_key_events(0x12),
            vec![KeyEvent::down(0x2a), KeyEvent::up(0x2a)],
            shifted_key_events(0x1b),
            vec![KeyEvent::down(0x30), KeyEvent::up(0x30)],
            vec![KeyEvent::down(0x24), KeyEvent::up(0x24)],
        ]
        .concat();

        assert_eq!(compile_events("a!\\_\t\n").unwrap(), expected);
    }

    #[test]
    fn compile_events_marks_only_shifted_characters_in_hello_keyguard() {
        let events = compile_events("Hello, Keyguard!").unwrap();
        let expected_flags: Vec<KeyEventFlags> = "Hello, Keyguard!"
            .chars()
            .flat_map(|ch| {
                let flags = if matches!(ch, 'H' | 'K' | '!') {
                    KEY_EVENT_FLAG_SHIFT
                } else {
                    KEY_EVENT_FLAG_NONE
                };
                [flags, flags]
            })
            .collect();

        assert_eq!(
            events.iter().map(|event| event.flags).collect::<Vec<_>>(),
            expected_flags
        );
    }

    #[test]
    fn emit_events_posts_compiled_events_in_order() {
        let events = compile_events("aA").unwrap();
        let posted = Arc::new(Mutex::new(Vec::new()));
        let posted_ref = Arc::clone(&posted);

        emit_events_with(
            &events,
            move |key_code, key_down, flags| {
                posted_ref.lock().unwrap().push((key_code, key_down, flags));
                true
            },
            || {},
        )
        .unwrap();

        let expected: Vec<(u16, bool, KeyEventFlags)> = events
            .iter()
            .map(|event| (event.key_code, event.key_down, event.flags))
            .collect();
        assert_eq!(*posted.lock().unwrap(), expected);
    }

    #[test]
    fn emit_events_releases_pressed_keys_in_reverse_after_key_down_failure() {
        let events = vec![
            KeyEvent::down(0x00),
            KeyEvent::down_with_flags(0x12, KEY_EVENT_FLAG_SHIFT),
            KeyEvent::down(0x18),
        ];
        let posted = Arc::new(Mutex::new(Vec::new()));
        let posted_ref = Arc::clone(&posted);

        let result = emit_events_with(
            &events,
            move |key_code, key_down, flags| {
                posted_ref.lock().unwrap().push((key_code, key_down, flags));
                key_code != 0x18
            },
            || {},
        );

        assert_eq!(result.unwrap_err(), "Failed to emit key event for code 24");
        assert_eq!(
            *posted.lock().unwrap(),
            vec![
                (0x00, true, KEY_EVENT_FLAG_NONE),
                (0x12, true, KEY_EVENT_FLAG_SHIFT),
                (0x18, true, KEY_EVENT_FLAG_NONE),
                (0x12, false, KEY_EVENT_FLAG_SHIFT),
                (0x00, false, KEY_EVENT_FLAG_NONE),
            ]
        );
    }

    #[test]
    fn emit_events_releases_pressed_keys_after_key_up_failure() {
        let events = vec![
            KeyEvent::down(0x00),
            KeyEvent::down_with_flags(0x12, KEY_EVENT_FLAG_SHIFT),
            KeyEvent::up_with_flags(0x12, KEY_EVENT_FLAG_SHIFT),
        ];
        let posted = Arc::new(Mutex::new(Vec::new()));
        let posted_ref = Arc::clone(&posted);

        let result = emit_events_with(
            &events,
            move |key_code, key_down, flags| {
                posted_ref.lock().unwrap().push((key_code, key_down, flags));
                !(key_code == 0x12 && !key_down)
            },
            || {},
        );

        assert_eq!(result.unwrap_err(), "Failed to emit key event for code 18");
        assert_eq!(
            *posted.lock().unwrap(),
            vec![
                (0x00, true, KEY_EVENT_FLAG_NONE),
                (0x12, true, KEY_EVENT_FLAG_SHIFT),
                (0x12, false, KEY_EVENT_FLAG_SHIFT),
                (0x12, false, KEY_EVENT_FLAG_SHIFT),
                (0x00, false, KEY_EVENT_FLAG_NONE),
            ]
        );
    }

    #[test]
    fn exported_autotype_returns_false_for_invalid_payload() {
        let payload = CString::new("Ä").unwrap();
        assert!(!autoType(payload.as_ptr()));
    }
}
