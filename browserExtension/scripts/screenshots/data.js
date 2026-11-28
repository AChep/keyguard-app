// The sample vault of the store screenshots: fictional sites and people.
// window.EXTENSION_STUBS has what the background tells each extension page, by
// the name in its frame's #stub=<name>, see ./stub-api.js.
(() => {
  const svg = (body) =>
    "data:image/svg+xml," +
    encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">${body}</svg>`);

  const ICONS = {
    pinewick: svg(
      `<rect width="64" height="64" fill="#2E7D5B"/><path d="M32 11 47 33h-8l10 16H15l10-16h-8z" fill="#fff"/><rect x="29" y="49" width="6" height="7" fill="#fff"/>`,
    ),
    driftmail: svg(
      `<rect width="64" height="64" fill="#6A4BD8"/><path d="M14 20h36a2 2 0 0 1 2 2v20a2 2 0 0 1-2 2H14a2 2 0 0 1-2-2V22a2 2 0 0 1 2-2zm2 5v1.5l16 10 16-10V25L32 35z" fill="#fff"/>`,
    ),
    fernway: svg(
      `<rect width="64" height="64" fill="#E8613C"/><path d="M14 44c8-2 14-8 18-18 4 10 10 16 18 18z" fill="#fff"/><circle cx="44" cy="20" r="5" fill="#fff"/>`,
    ),
  };

  // MenuItem of src/lib/page/messages.ts.
  const PINEWICK = [
    {
      handle: "pw-a",
      name: "Pinewick",
      username: "alex@morgan.studio",
      account: "Personal",
      has_totp: true,
      reprompt: false,
      matched: true,
    },
    {
      handle: "pw-b",
      name: "Pinewick",
      username: "alex.morgan@northpeak.co",
      account: "Work",
      has_totp: false,
      reprompt: false,
      matched: true,
    },
    {
      handle: "pw-c",
      name: "Pinewick Studio",
      username: "alexm",
      account: "Personal",
      has_totp: false,
      reprompt: false,
      matched: false,
    },
  ];

  // SuggestItem of src/lib/protocol/messages.ts.
  const DRIFTMAIL = [
    {
      handle: "dm-a",
      name: "Driftmail",
      username: "alex@driftmail.io",
      account: "Personal",
      has_password: true,
      has_totp: true,
      reprompt: false,
      passkeys: [{ handle: "pk-a", user_name: "alex@driftmail.io", user_display_name: "Alex Morgan" }],
      last_used: true,
      matched: true,
      uris: ["https://driftmail.io", "https://app.driftmail.io"],
      folder: "Email",
      revision_date: "2026-10-14T09:26:53Z",
    },
    {
      handle: "dm-b",
      name: "Driftmail",
      username: "alex.morgan@northpeak.co",
      account: "Work",
      has_password: true,
      has_totp: true,
      reprompt: false,
      matched: true,
      uris: ["https://driftmail.io"],
      folder: "Work",
      revision_date: "2026-08-02T15:10:00Z",
    },
    {
      handle: "dm-c",
      name: "Driftmail Admin",
      username: "admin@northpeak.co",
      account: "Work",
      has_password: true,
      has_totp: false,
      reprompt: true,
      matched: true,
      uris: ["https://admin.driftmail.io"],
      folder: "Work",
      revision_date: "2026-06-21T11:00:00Z",
    },
    {
      handle: "dm-d",
      name: "Drift Notes",
      username: "alexm",
      account: "Personal",
      has_password: true,
      has_totp: false,
      reprompt: false,
      matched: false,
      uris: ["https://notes.drift.example"],
      revision_date: "2026-03-09T08:00:00Z",
    },
  ];

  window.SITE_ICONS = ICONS;

  window.EXTENSION_STUBS = {
    // MenuEvent
    menu: {
      events: [
        { kind: "bound" },
        { kind: "shown" },
        {
          kind: "view",
          view: {
            kind: "items",
            role: "username",
            host: "pinewick.app",
            items: PINEWICK,
            last: null,
            truncated: false,
            totp: null,
            passkeys: [],
            icon: ICONS.pinewick,
            create: true,
          },
        },
      ],
    },
    // MenuDetailsEvent
    card: {
      events: [
        { kind: "bound" },
        {
          kind: "view",
          seq: 1,
          handle: "pw-a",
          item: {
            name: "Pinewick",
            username: "alex@morgan.studio",
            account: "Personal",
            copies: ["copy_username", "copy_password", "copy_totp"],
            reprompt: false,
            matched: true,
            last: false,
            icon: ICONS.pinewick,
            passkeys: [],
            details: { uris: ["https://pinewick.app"], more: 0, folder: "Web", revision_date: "2026-10-02T09:26:53Z" },
          },
        },
        { kind: "shown", side: "right" },
      ],
    },
    // BackgroundMessage, and the results of the popup's calls.
    popup: {
      tabUrl: "https://driftmail.io/signin",
      events: [{ kind: "status", status: { kind: "unlocked" } }],
      calls: {
        suggest: {
          kind: "items",
          origin: "https://driftmail.io",
          host: "driftmail.io",
          truncated: false,
          items: DRIFTMAIL,
          last: "dm-a",
          icon: ICONS.driftmail,
          create: true,
        },
      },
    },
    // SaveBarEvent
    save: {
      events: [
        { kind: "bound" },
        { kind: "view", offer: 1, host: "fernway.app", username: "alex@morgan.studio", icon: ICONS.fernway },
      ],
    },
  };
})();
