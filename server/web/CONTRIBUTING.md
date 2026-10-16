# Writing Keyguard docs

Rules for the docs in `src/content/docs/` and the marketing pages in `src/content/features/`.
Each rule names its check: **schema** fails the build, **lint** fails the PR, **review** is a checklist item.

```sh
pnpm install
pnpm check        # types and frontmatter schema
pnpm lint:docs    # page structure and Markdown
pnpm lint:prose   # Vale, needs the vale binary
pnpm build        # site and search index
pnpm check:links  # URLs the app and README link to, after build
```

## Sections

The sidebar sorts pages by product area. A page goes in the section of the feature it documents. Its type never
decides the section.

| Section | `section` | Contents |
| --- | --- | --- |
| Get started | `get-started` | Closed list: the tutorial, Premium, License keys |
| Accounts and sync | `accounts` | Bitwarden, KeePass, multiple accounts, sync |
| Vault | `vault` | Items and what you do with them |
| Autofill and passkeys | `autofill` | Autofill, passkeys, URL matching |
| Security | `security` | Locking, Watchtower, the security model |
| Developer tools | `developer` | SSH agent, GPG agent, GPG keys |
| Backup and transfer | `backup` | Import, export, Credential Exchange, backups |
| Platforms | `platforms` | Platforms with a limited or separate feature set, and the support matrix |
| Reference | `reference` | Closed list: lookups used across features (syntaxes, URL overrides, shortcuts, field conventions, settings) |
| Help | `help` | Closed list: the troubleshooting index, Send feedback, Report a vulnerability, Architecture |

- Adding to a closed list means changing this file. (review)
- A reference about one feature stays with that feature, as `gpg-keys` does. (review)
- Feature pages own their own platform steps. Platforms holds only whole-platform pages. (review)
- `order` steps by 10 and is unique within a section. A tutorial comes first, troubleshooting pages last. (schema)
- A subfolder becomes a sidebar subgroup, as `bitwarden/` does. Add one only for 2 or more pages. (review)
- At most 10 pages per section. An eleventh page means the section needs a split. (review)

Planned pages: the Environments feature lands in Developer tools as `environments` (feature) and `cli` (reference,
the CLI flags, exit codes and MCP tools).

## Frontmatter

```yaml
---
title: SSH agent
description: Sign SSH requests with keys from your vault, from desktop tools, Termux and Android apps.
section: developer
type: feature
order: 10
platforms: [android, linux, macos, windows]   # or `all`
premium: false                                # optional
stage: experimental                           # optional: beta | experimental
related: [agent-approvals, git-signing]       # optional, at most 5
---
```

- `platforms` lists ids in this order: `android`, `wear-os`, `ios`, `linux`, `macos`, `windows`. `all` means Android,
  Linux, macOS and Windows. List `wear-os` explicitly. Don't list a platform before it ships. (schema)
- The layout renders `platforms`, `premium` and `stage` as badges under the title, and `related` as a list at the
  end ("Next steps" on tutorials). Don't repeat them in the body. The Premium badge links to Premium. (review)
- `description`: one sentence of 50–160 characters, ending in a period, with no counts that go stale. (schema)

## Page types

Every page declares one `type`. The type sets the title pattern and the order of H2 headings.

| `type` | Use it for | Title pattern | Example |
| --- | --- | --- | --- |
| `tutorial` | The one guided first run. Get started only. | Get started with … | Get started with Keyguard |
| `feature` | Everything about one feature: what it is, how to use it, its options, its problems | Feature noun | SSH agent |
| `how-to` | One goal that spans features, or a task too long for its feature page | Verb + object, platform last | Set up autofill on Android |
| `concept` | How something works and why. No steps. | Noun phrase that reads after "About" | Agent approvals |
| `reference` | Lookups: syntax, options, lists | Noun | Placeholders |
| `troubleshooting` | Symptoms and fixes for one area | Troubleshoot … | Troubleshoot autofill |

Titles: sentence case, at most 60 characters, no `&`, `+`, `→` or `:`, and no "Keyguard on …", "Overview" or
"Introduction". (schema)

### Skeletons

Headings in quotes are fixed text. Brackets mark optional headings. "Before you begin" is an H3 (`###`) right
after the lead, before the first H2. (lint)

- **tutorial**: a lead saying what you will have at the end, in 1–2 sentences. "Before you begin". One H2 per stage,
  verb + object, with numbered steps; each stage ends in a visible result.
- **feature**: a lead saying what the feature does, in 1–3 sentences. ["Before you begin"]: requirements such as an
  item type, a setting or a build. Task H2s as verb + object, with numbered steps. ["Verify"]. [Reference H2s]: nouns,
  tables preferred. ["Limitations"]: build and platform gaps. ["Troubleshooting"]: at most 4 entries; a fifth moves
  them all to a troubleshooting page.
- **how-to**: a 1-sentence lead starting with the goal ("To sign commits with a key in your vault, …").
  ["Before you begin"]: bullets that link to setup pages instead of repeating them. One or more verb + object H2s
  with numbered steps. ["Verify"].
- **concept**: a lead that defines the subject in 1–2 sentences. Noun H2s. No numbered lists, no instructions.
- **reference**: a lead saying what the page lists, in 1–2 sentences. Noun H2s, each with a table or a list. Order
  follows the UI or the alphabet.
- **troubleshooting**: a 1-sentence lead naming the area. One H2 per symptom, in the user's words or the exact app
  message in double quotes, without its final period. Under each: the cause in 1 sentence, then the fix.

The Help > Troubleshooting page is generated: it lists every troubleshooting page's symptom H2s and the H3 entries
under each feature page's "Troubleshooting" H2. Keep each of those headings a symptom the reader would recognize.

A `feature` page that mixes topics keeps its H2s in this order: concept, tasks, reference, troubleshooting.

## One owner per fact

Each fact lives on one page. Other pages link to it and never restate it. (review)

| Fact | Owner |
| --- | --- |
| What needs premium | `premium`, plus the `premium` frontmatter flag |
| Which platforms support a feature | `platforms` frontmatter, rendered on Platform support |
| Approval windows and scopes for the SSH and GPG agents | `agent-approvals` |
| Install channels and commands | the Download page (`PLATFORMS` in `src/consts.ts`) |
| Custom-field formats | `item-extras` |
| Merge rules | `sync-and-conflicts` |
| Unlock methods | `lock-and-unlock` |

## Writing rules

### Words

- Second person, present tense. Never "we", "I" or "please". No emoji. (lint)
- Banned: simply, just, easily, seamlessly, powerful, "Welcome", "That's it", "Note that", via, e.g. (lint)
- "Set up" is the verb and "setup" the noun. Write "sign in", not "log in". (lint)
- Use select for buttons and items, turn on and turn off for switches, and enter for typing. Never click or tap. (lint)
- Spell names this way: macOS, iOS, Wear OS, Flatpak, AppImage, KeePass, KDBX, Bitwarden, Vaultwarden, YubiKey,
  GnuPG, OpenSSH, Termux, Watchtower, passkey (lowercase in prose). (lint)
- Use the UI label for a feature's name. (review)
- Terms: "app password" (Keyguard's own) and "master password" (Bitwarden's or a KeePass database's);
  "two-factor authentication", not "two-step login"; "Wi-Fi" in prose; "send" in lowercase; "URI" for the item
  field the editor labels **Website (URI)**. (review)
- Never mention source files, class names or string resource names. They're evidence for the writer, not content.
  (review)

### UI, keys and commands

- Menu paths are bold, use `>`, and start at a root the reader can see: `**Settings > Developer > Connected apps**`.
  (lint)
- Outside Keyguard, use the other app's labels. If the path varies by device, name only the setting. (review)
- Labels are bold and match the UI's case. Error messages go in quotes. (review)
- A button with no label is named by its icon: "select the edit (pencil) icon". (review)
- Keys: Ctrl+Shift+Space (Cmd+Shift+Space on macOS). No code formatting and no `/` between modifiers. (review)
- Commands go in fenced blocks with a language (`sh`, `powershell`, `sshconfig`). Values to replace look like
  `<KEY_FINGERPRINT>`. (lint)
- An input and its result, such as a placeholder and what it resolves to, go in one `text` block: the input on a
  line starting with `> `, the result on the next line. (review)

### Procedures

- Two or more steps make a numbered list. A single step is a sentence. (review)
- One action per step. Location before action, result after it: "In **Settings > Developer**, turn on
  **SSH agent**. Keyguard starts listening." (review)
- No explanation inside steps. Link to the concept instead. (review)

### Headings

- H2 and H3 only, in sentence case. No numbers, no links, no `&`. (lint)
- Never two headings without text between them, and never a heading that repeats the page title. (review)
- A heading the app links to is frozen: renaming it breaks the anchor. Today that's
  `credential-exchange#importing-from-another-app` and `generator#custom-wordlists`. (lint: `check:links`)

### Callouts

- Two kinds, in the Starlight syntax: `:::note` and `:::caution`. (lint)
- At most 2 per page, never adjacent, and never holding steps or requirements. Blockquotes are for quotations only.
  (lint)

```md
:::caution
Editing these fields in another client can make the item invalid in Keyguard.
:::
```

### Collapsed blocks

- `:::details[Summary]` hides a long list the reader rarely needs, such as every supported language. The summary
  label is required. (lint)
- Never for steps, requirements or anything the reader must see. Collapsed blocks don't count toward the callout
  limit. (review)

```md
:::details[Supported languages]
- Afrikaans
- Arabic
:::
```

### Links

- Absolute paths with a trailing slash: `/docs/ssh-agent/`. The link text is the target's title or heading. Never
  "here". (lint)
- Docs never link to `/features/` pages. (lint)
- Related pages go in the `related` frontmatter, never in a hand-written "See also" section. (lint)

### Length

- At most 1,200 words for feature pages, 600 for how-tos and 800 for concepts. Reference and troubleshooting pages
  have no limit. (lint)
- Sentences under 25 words, paragraphs of 3 sentences or fewer. (review)

### Images

- Plain Markdown images, only where a UI is hard to describe. Alt text is required. Screenshots live in
  `src/assets/screenshots/` and are shared with the marketing pages. (lint)

## Platforms and builds

Platforms always appear in this order: Android, Wear OS, iOS, Linux, macOS, Windows.

| What differs | How to write it | Example |
| --- | --- | --- |
| Only values, such as paths and sockets | One procedure, plus a table with a Platform column | SSH agent endpoints |
| Which platforms support an option | A table with a Platforms column, never "(Android, macOS, Windows)" in prose | Unlock methods |
| Some steps | `###` per platform under the task H2 | Linux setup in Lock and unlock |
| The whole flow | A separate task H2, or a separate how-to page above 600 words | Set up the agent in Termux |
| A platform's whole feature set | A page in Platforms | Wear OS |

Builds use these names: Google Play, F-Droid, APK, Flatpak, AppImage, AUR, DMG, Homebrew, Mac App Store, MSI, MSIX,
Microsoft Store, WinGet, Scoop. A build that lacks part of a feature goes in that page's "Limitations" table
(Build | Limitation), or Platform | Limitation for a platform gap.

## Marketing pages (`src/content/features/`)

- A pitch only: 1–2 paragraphs and a screenshot. No numbers, defaults, limits, platform lists or steps. (review)
- The required `doc` field names the owner doc. The page shows that doc's badges and a **Learn more** link, so the
  two can't disagree. (schema)
