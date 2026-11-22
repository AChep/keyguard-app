// A content script that an update or removal of the extension orphaned: it
// runs on, but its extension APIs are gone, and Chromium throws at once on a
// call ("Extension context invalidated.") instead of rejecting. WXT's
// ContentScriptContext tears such a script down (its `onInvalidated`) when a
// newer copy starts in the frame, or when a read of `isInvalid` finds the
// extension gone; reads come where the script would talk to the extension or
// show its UI.
import type { ContentScriptContext } from "wxt/utils/content-script-context";

/** The part of WXT's ContentScriptContext the reads need: a read of `isInvalid` that finds the script orphaned tears it down. */
export type ScriptContext = Pick<ContentScriptContext, "isInvalid">;

/**
 * [send] for a script the extension may orphan: it never throws, and resolves
 * `undefined` when the call fails or the extension is gone.
 */
export function orphanSafe<M, R>(
  ctx: ScriptContext,
  send: (message: M) => Promise<R>,
): (message: M) => Promise<R | undefined> {
  return async (message) => {
    if (ctx.isInvalid) return undefined;
    try {
      return await send(message);
    } catch {
      // The extension may have gone meanwhile.
      void ctx.isInvalid;
      return undefined;
    }
  };
}
