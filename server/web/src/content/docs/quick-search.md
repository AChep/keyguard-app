---
title: Quick search
description: "Find an item from any desktop app with a global shortcut, then copy its values or auto-type a login."
section: autofill
type: feature
order: 60
platforms: [linux, macos, windows]
related: [search, shortcuts, appearance]
---

Quick search finds an item from any app on desktop. It copies the item's
values, or types a login into the app you were using. It takes the same
queries as [Search](/docs/search/).

### Before you begin

Keyguard must be running. To keep it running after you close its window, turn
on [Close to system tray](/docs/appearance/#close-to-system-tray).

## Open Quick search

Press Ctrl+Shift+Space (Cmd+Shift+Space on macOS) in any app. If the vault is
locked, Quick search asks you to unlock it.

Quick search closes when it loses focus, or when you press Esc with an empty
search field.

## Copy a value

1. Open Quick search and enter a query.
2. Select a result with Up or Down. The item opens on the right.
3. Select an action, such as **Copy password**.

Selecting an action keeps Quick search open. Its keyboard shortcut runs it and
closes Quick search. See [Keyboard shortcuts](/docs/shortcuts/).

## Auto-type a login

1. In the other app, place the cursor in the username field.
2. Press Ctrl+Shift+Space (Cmd+Shift+Space on macOS).
3. Find the login, then press Ctrl+T (Cmd+T on macOS).

Keyguard types the username, Tab, then the password. It never presses Enter.

To type one value, press Ctrl+Shift+T (Cmd+Shift+T on macOS), then 1 for the
username, 2 for the password or 3 for the one-time password.

Keyguard types into the app that was in front when you pressed
Ctrl+Shift+Space. It does this once: for the next auto-type, press the shortcut
again. Typing stops if you press a key or a mouse button, or switch windows.

### macOS

Auto-type needs Accessibility access. The first auto-type opens the system
prompt.

1. In **System Settings > Privacy & Security > Accessibility**, turn on
   Keyguard.
2. Switch to the other app and press Cmd+Shift+Space again.

## Results

Results include every item type, except items in the trash or the archive and
items of hidden accounts. Favorites come first.

## Actions

Each result shows the actions that fit its item.

| Action | What it does |
| --- | --- |
| **Copy username** | Copies the main field. A card copies its number, an SSH key its public key. |
| **Copy password** | Copies the secret. A card copies its security code, an SSH key its private key. |
| **Auto-type** | Types the username and password. Logins only. |
| **Copy one-time password** | Copies the current code |
| **Launch browser** | Opens the item's first web URI |

For an item with **Auth re-prompt**, Quick search copies only a username,
email, phone number or public key.

## Auto-type speed

**Settings > Other > Auto-type speed** sets the pauses between typed
characters. Slow apps can drop characters at **Fast**.

| Speed | Before the first character | Between characters | After Tab |
| --- | --- | --- | --- |
| **Fast** (default) | 200 ms | 20 ms | 100 ms |
| **Normal** | 400 ms | 40 ms | 200 ms |
| **Slow** | 600 ms | 80 ms | 400 ms |

## Limitations

| Platform | Limitation |
| --- | --- |
| Linux | No auto-type. The global shortcut needs an X11 session. |

Auto-type ignores KeePass auto-type sequences and window associations.
Keyguard keeps them in the database.

## Troubleshooting

### "Accessibility access required"

macOS hasn't given Keyguard Accessibility access. Turn it on as in
[macOS](#macos).

### "Could not focus the destination"

Keyguard has no app to type into. This happens when Quick search opens without
the global shortcut, or when the other app's window closes. Switch to the app
and press the shortcut again.

### "Cannot auto-type this value"

The value is empty, holds a line break or a tab, or is longer than 4,096
characters. Copy it instead.

### "Release all keys and mouse buttons, then try again"

A key or mouse button was still down 2 seconds after you started auto-type.
Release it, then try again.
