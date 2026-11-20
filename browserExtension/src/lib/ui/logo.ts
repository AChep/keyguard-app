// The Keyguard logo of the inline icon, the save bar, the pages and the toolbar
// icons (scripts/render-icons.ts): the lock of
// common/src/androidMain/res/drawable/ic_launcher_foreground.xml, in the
// launcher's colors (common/src/androidMain/res/values/colors.xml), on a
// rounded tile with the composition and the gradient of the website's favicon
// (server/web/public/favicon-32x32.png). A leaf, so the content script and the
// save bar don't bundle any UI with it.

/**
 * The tile, in the units of LOGO_PATHS. The lock takes 51% of its width and
 * 72% of its height, with 15% of the height above it and 12% below, as in the
 * favicon. At 16, 20 and 32 px the lock's sides fall about on whole pixels.
 */
export const LOGO_TILE = { x: 22, y: 21, size: 65 } as const;

export const LOGO_VIEW_BOX = `${LOGO_TILE.x} ${LOGO_TILE.y} ${LOGO_TILE.size} ${LOGO_TILE.size}`;

/** The radius of the tile's corners, as a fraction of its size. */
export const LOGO_TILE_RADIUS = 0.22;

/**
 * Even-odd paths: [0] is the shackle and the keyhole, in LOGO_SHACKLE; [1] is
 * the body, in LOGO_BODY, with the keyhole as a hole.
 */
export const LOGO_PATHS = [
  "M56,31C64.28,31 71,37.72 71,46L71,53L65,53L65,46C65,41.12 61.11,37.14 56.27,37L56,37L53,37C48.12,37 44.14,40.89 44,45.73L44,46L44,53L38,53L38,46C38,37.72 44.72,31 53,31L56,31Z" +
    "M54.5,56C51.46,56 49,58.35 49,61.25C49,63.34 50.28,65.15 52.14,65.99L52.14,69.5C52.14,70.33 52.81,71 53.64,71L55.36,71C56.19,71 56.86,70.33 56.86,69.5L56.86,65.99C58.72,65.15 60,63.34 60,61.25C60,58.35 57.54,56 54.5,56Z",
  "M67,50C69.14,50 70.89,51.68 71,53.8L71,54L71,63.33C71,71.43 64.43,78 56.33,78L52.67,78C44.57,78 38,71.43 38,63.33L38,54C38,51.79 39.79,50 42,50L67,50ZM54.5,56C51.46,56 49,58.35 49,61.25C49,63.34 50.28,65.15 52.14,65.99L52.14,69.5C52.14,70.33 52.81,71 53.64,71L55.36,71C56.19,71 56.86,70.33 56.86,69.5L56.86,65.99C58.72,65.15 60,63.34 60,61.25C60,58.35 57.54,56 54.5,56Z",
] as const;

/** The launcher's blue: the bottom of the tile, and the focus ring of the inline icon. */
export const LOGO_BACKGROUND = "#219BCC";
export const LOGO_SHACKLE = "#191C1E";
export const LOGO_BODY = "#E0F3FF";

/** The colors of LOGO_PATHS, in order. */
export const LOGO_PATH_COLORS = [LOGO_SHACKLE, LOGO_BODY] as const;

/**
 * The tile's linear gradient, straight down from its top edge (0%) to its
 * bottom edge (100%): [offset in percent, color] stops, flat past the ends.
 */
export const LOGO_GRADIENT_STOPS = [
  [5, "#61BCE5"],
  [69, LOGO_BACKGROUND],
] as const;

/** LOGO_GRADIENT_STOPS as a CSS background image. */
export const LOGO_GRADIENT_CSS = `linear-gradient(to bottom, ${LOGO_GRADIENT_STOPS.map(([offset, color]) => `${color} ${offset}%`).join(", ")})`;
