//! Conventional OpenPGP User ID parsing.
//!
//! OpenPGP preserves User IDs as opaque UTF-8 text.  This module only derives
//! a mailbox when the complete text follows the anchored conventional format.

const ATEXT_PUNCTUATION: &str = "!#$%&'*+-/=?^_`{|}~";

pub(in crate::openpgp) fn conventional_user_id_email(user_id: &str) -> Option<&str> {
    if is_mailbox_address(user_id) {
        return Some(user_id);
    }

    let without_closing = user_id.strip_suffix('>')?;
    let address_start = without_closing.rfind('<')?;
    let (prefix, address) = without_closing.split_at(address_start);
    let address = address.strip_prefix('<')?;
    if !is_mailbox_address(address) || !is_address_prefix(prefix) {
        return None;
    }
    Some(address)
}

fn is_mailbox_address(value: &str) -> bool {
    let mut parts = value.split('@');
    let Some(local) = parts.next() else {
        return false;
    };
    let Some(domain) = parts.next() else {
        return false;
    };
    parts.next().is_none() && is_dot_atom(local) && is_dot_atom(domain)
}

fn is_dot_atom(value: &str) -> bool {
    value
        .split('.')
        .all(|atom| !atom.is_empty() && atom.chars().all(is_atext))
}

fn is_atext(character: char) -> bool {
    character.is_ascii_alphanumeric()
        || ATEXT_PUNCTUATION.contains(character)
        || (!character.is_ascii() && !character.is_control())
}

fn is_address_prefix(value: &str) -> bool {
    let mut value = value.trim_matches(' ');
    if value.is_empty() || value.chars().all(is_name_char) {
        return true;
    }

    if let Some(without_closing) = value.strip_suffix(')')
        && let Some(comment_start) = without_closing.rfind('(')
    {
        let (name, comment) = without_closing.split_at(comment_start);
        let comment = &comment['('.len_utf8()..];
        if !comment.chars().all(is_comment_char) {
            return false;
        }
        value = name.trim_end_matches(' ');
    }

    value.is_empty() || value.chars().all(is_name_char)
}

fn is_name_char(character: char) -> bool {
    !character.is_control() && !matches!(character, '<' | '>')
}

fn is_comment_char(character: char) -> bool {
    !character.is_control() && !matches!(character, '(' | ')')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_mailboxes_from_conventional_user_ids() {
        for (user_id, expected) in [
            ("alice@example.com", "alice@example.com"),
            ("Alice <alice@example.com>", "alice@example.com"),
            ("<alice@example.com>", "alice@example.com"),
            (
                "attacker@example.com <alice@example.com>",
                "alice@example.com",
            ),
            (
                "Acme Industries, Inc. (work) <first.last+tag@example.com>",
                "first.last+tag@example.com",
            ),
            ("MÜLLER@例子.测试", "MÜLLER@例子.测试"),
            ("Emoji <emoji😀@example.com>", "emoji😀@example.com"),
        ] {
            assert_eq!(
                conventional_user_id_email(user_id),
                Some(expected),
                "{user_id}"
            );
        }
    }

    #[test]
    fn rejects_ambiguous_or_malformed_user_ids() {
        for user_id in [
            "",
            "Alice Example",
            "Name < a@b >",
            "Name <first@example.com> <second@example.com>",
            "Name <a@b> trailing@example.com",
            "<bad<good@example.com>",
            "Name <a@b> ",
            "\"first.last\"@example.com",
            " first@example.com ",
            "a@@b",
            "a@\tb",
            ".a@b",
            "a.@b",
            "a..b@c",
            "a@.b",
            "a@b.",
        ] {
            assert_eq!(conventional_user_id_email(user_id), None, "{user_id}");
        }
    }
}
