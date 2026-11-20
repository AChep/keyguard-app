// The monogram of an avatar, as the app draws a vault item without an icon:
// its initials, on a color hashed from its name.

/** A zero-width joiner or a variation selector-16, which join the symbols around them. */
function isEmojiControl(char: string | undefined): boolean {
  return char === "‍" || char === "️";
}

function isHighSurrogate(char: string): boolean {
  const code = char.charCodeAt(0);
  return code >= 0xd800 && code <= 0xdbff;
}

/**
 * The symbol of [text] at the UTF-16 [index]: a character, with its low
 * surrogate and the joiners and variation selectors that follow it. Empty past
 * the end. `String.nextSymbol` of common/.../common/util/StringSurrogate.kt.
 */
function nextSymbol(text: string, index = 0): string {
  if (index < 0 || index >= text.length) return "";
  let end = index;
  while (end < text.length) {
    const char = text[end]!;
    if (isHighSurrogate(char) || isEmojiControl(char)) {
      end += 1;
    } else if (isEmojiControl(text[end + 1])) {
      end += 2;
    } else {
      end += 1;
      break;
    }
  }
  return text.substring(index, end);
}

/**
 * The initials of [name], as `VaultItemIcon.TextIcon.short` of
 * common/.../feature/home/vault/model/VaultItemIcon.kt: the first symbols of
 * its first two words, or the first two symbols of a single word. Words are
 * split at spaces only, and the case is kept.
 */
export function initials(name: string): string {
  const words = name.split(" ");
  if (words.length <= 1) {
    const word = words[0] ?? "";
    let result = "";
    for (let i = 0; i < 2; i++) result += nextSymbol(word, result.length);
    return result;
  }
  return words
    .slice(0, 2)
    .map((word) => nextSymbol(word))
    .join("");
}

/**
 * FNV-1, 32-bit, over the UTF-16 code units of [text]: `FNV.fnv1_32(String)`
 * of common/.../common/util/hash/FNV.kt. Unsigned.
 */
export function fnv1(text: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash = Math.imul(hash, 0x01000193) >>> 0;
    hash = (hash ^ text.charCodeAt(i)) >>> 0;
  }
  return hash;
}

/**
 * The hue of the monogram of [text], 0 to 354 in steps of 6:
 * `generateAccentColors(seed)` of common/.../ui/icons/AttachmentIcon.kt. The
 * hash is unsigned there too (a Long modulo 2^32).
 */
export function monogramHue(text: string): number {
  return (fnv1(text) % 60) * 6;
}

/** Compose's `Color.hsv`: [hue] in degrees, [saturation] and [value] 0 to 1; channels 0 to 255. */
export function hsvToRgb(hue: number, saturation: number, value: number): [number, number, number] {
  const channel = (n: number) => {
    const k = (n + hue / 60) % 6;
    const v = value - value * saturation * Math.max(0, Math.min(k, 4 - k, 1));
    return Math.round(v * 255);
  };
  return [channel(5), channel(3), channel(1)];
}

/** The accent of the monogram of [text]: HSV(hue, 0.8, 0.8) as `rgb()`. */
export function monogramAccent(text: string): string {
  const [r, g, b] = hsvToRgb(monogramHue(text), 0.8, 0.8);
  return `rgb(${r} ${g} ${b})`;
}
