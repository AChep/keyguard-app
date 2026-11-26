// The background's side of the card beside the inline menu: a page of its
// own that shows the details of the login whose row the menu selects.
//
// Rules, as for the menu (./pages.ts):
// - The card's page gets metadata only, never a secret: its copies go to the
//   clipboard from here ([MenuDetailsHost.copyText]), after the checks of a
//   menu fill for the card ([MenuDetailsHost.guardMenu]). Neither the card's
//   page nor the content script gets the value.
// - Focus moves into the card, or back to the menu, only through the content
//   script's checks ([MenuDetailsHost.focusPopup]).
// - A size or a copy names the view of the card it's for ([MenuDetailsLink.seq]):
//   one of an older view does nothing.
//
// [PageController] binds the card's page with its token and hands its
// requests here, with those of the menu and its content script about the card.
import { copiesPassword, type FramePage, type SuggestItem } from "../protocol/messages";
import {
  itemDetailsOf,
  itemViewOf,
  type DocumentCommand,
  type ItemView,
  type MenuDetailsEvent,
  type MenuDetailsRequest,
  type MenuEvent,
  type MenuRequest,
  type MenuView,
  type PopupShown,
} from "../page/messages";
import type { UriCopy } from "../ui/itemDetails";
import { popupActions, type CopyAction } from "../ui/popupKeys";
import { CallFailure, releaseCopy } from "./controller";
import type { FrameRef } from "./frames";
import type { AppAccess, EmbeddedSession, MenuSession, PagePort } from "./pages";

/**
 * A menu and the card beside it, a page of its own that shows the details of
 * the login whose row is selected: by Space on the row, see
 * ../ui/menuKeyboard.ts. The menu's tokens share it. The details go to the
 * card's page only; its content script gets the card's place and size. The
 * card shows while the menu selects the login, and hides once it selects none.
 */
export interface MenuDetailsLink {
  /** The menu, once bound. */
  menu: MenuSession | null;
  /** The card's page, once bound. */
  card: MenuDetailsSession | null;
  /** Counts the views of the card: a size is for the view of its [seq] only. */
  seq: number;
  /** The login whose details the card shows, or `null` when it's hidden. */
  handle: string | null;
  /** The box of [handle]'s row in the menu's frame. */
  row: { top: number; height: number } | null;
  /** The height of the card's content for view [seq], once its page told it. */
  height: number | null;
  /** The view the card got for [handle]: a refresh that changes nothing doesn't send it again. */
  view: ItemView | null;
  /** The login whose row the menu selected last, and the row's box; `null` after it selected none. */
  selected: Extract<MenuRequest, { kind: "select" }> | null;
}

/**
 * The card beside a menu, see [MenuDetailsLink]: a child page of the menu,
 * which [PageController] closes with it.
 */
export interface MenuDetailsSession extends EmbeddedSession {
  kind: "menu_details";
  link: MenuDetailsLink;
}

/** What the card needs of [PageController]. */
export interface MenuDetailsHost {
  /** Posts [event] to an embedded page; one that closed misses it. */
  post(port: PagePort, event: MenuEvent | MenuDetailsEvent): void;
  /** Sends a command about the card to the content script of [frame], ignoring a document that's gone. */
  notify(
    frame: FrameRef,
    command: Extract<DocumentCommand, { kind: "menu_details" | "menu_details_hide" }>,
  ): Promise<void>;
  /** Whether the document of [frame] still has focus, as its content script tells; false when it's gone. */
  hasFocus(frame: FrameRef): Promise<boolean>;
  /** Has the content script of [frame] move focus into the frame of [popup], the menu or its card; whether it did. */
  focusPopup(frame: FrameRef, popup: "menu" | "menu_details"): Promise<boolean>;
  /** The checks before a copy of the card of [menu], see [PageController.guardMenu]. */
  guardMenu(menu: MenuSession, popup: "menu_details"): Promise<boolean>;
  /** Runs one action of the card at a time, see [PageController.act]. */
  act(card: MenuDetailsSession, action: (signal: AbortSignal) => Promise<void>): Promise<void>;
  /** Closes [menu] and its frame, with the card. */
  closeMenu(menu: MenuSession): void;
  /** The page of a release for [frame], see [PageController.pageOf]. */
  pageOf(frame: FrameRef): Promise<FramePage>;
  /** The app session, which releases a password or a one-time password to copy. */
  app: Pick<AppAccess, "request">;
  /** Writes [text] to the clipboard, see [PageDeps.copyText]. */
  copyText(text: string): Promise<void>;
}

/** What the card beside [menu] shows of [item], an item of its `items` view: metadata only, never a secret. */
function cardView(menu: MenuSession, item: SuggestItem): ItemView {
  const shown = menu.shown.kind === "items" ? menu.shown : null;
  return itemViewOf(item, shown?.icon ?? null, item.handle === shown?.last);
}

/** Linked URI [copy] of [item], as the card lists them (see [itemDetailsOf]); `undefined` for one it doesn't. */
function detailsUri(item: SuggestItem, copy: UriCopy): string | undefined {
  return itemDetailsOf(item).uris[copy.uri];
}

/** The cards beside the inline menus, see [MenuDetailsLink]. */
export class MenuDetailsController {
  constructor(private readonly host: MenuDetailsHost) {}

  /** The link of a menu that opens, which its tokens and the card's share. */
  newLink(): MenuDetailsLink {
    return {
      menu: null,
      card: null,
      seq: 0,
      handle: null,
      row: null,
      height: null,
      view: null,
      selected: null,
    };
  }

  /** The menu of [menu]'s link bound. */
  menuBound(menu: MenuSession) {
    menu.details.menu = menu;
  }

  bindDetails(port: PagePort, link: MenuDetailsLink): MenuDetailsSession {
    const card: MenuDetailsSession = { kind: "menu_details", port, link, busy: false, abort: new AbortController() };
    link.card = card;
    this.host.post(port, { kind: "bound" });
    // A row selected while the page loaded.
    if (link.handle !== null && link.view !== null)
      this.host.post(port, { kind: "view", seq: link.seq, handle: link.handle, item: link.view });
    return card;
  }

  /**
   * The card's page closed, or its menu did: [PageController] aborts its
   * requests in flight, as a copy, and disconnects it.
   */
  detailsEnded(card: MenuDetailsSession) {
    // Its frame went, as on a navigation: the card's content script hides nothing it shows.
    if (card.link.card === card) card.link.card = null;
  }

  /** [menu] closed: [PageController] closes its card, a child page; nothing shows it any more. */
  menuEnded(menu: MenuSession) {
    menu.details.handle = null;
  }

  /**
   * [menu] shows [view]. The card beside it shows the selected login's
   * details anew, of the app's latest answer; it hides when that login is
   * gone, or the view lists no logins.
   */
  viewShown(menu: MenuSession, view: MenuView) {
    const handle = menu.details.handle;
    if (handle === null) return;
    const item = view.kind === "items" ? menu.items.get(handle) : undefined;
    if (item === undefined) this.hideDetails(menu);
    else this.showDetails(menu, item);
  }

  /**
   * An action of [menu] ended, and the menu stays: the card of the row the
   * menu still selects shows again, unless the menu no longer lists its
   * login, as after a move to another URL.
   */
  actionEnded(menu: MenuSession) {
    const selected = menu.details.selected;
    if (selected === null) return;
    if (menu.items.has(selected.handle)) this.selectDetails(menu, selected);
    else this.dropSelection(menu);
  }

  /**
   * The content script of [menu]'s document showed or hid the card's frame,
   * see [PopupShown]: the card's click gate starts, or waits again.
   * Only a bound card shows. Both pages hear its side: their arrow keys point
   * toward it, or back. A card without room beside the menu drops the
   * selection, as `menu_blur`: the menu's page then holds none that shows
   * nothing, and Escape closes it.
   */
  detailsShown(menu: MenuSession, request: Extract<PopupShown, { popup: "menu_details" }>) {
    const card = menu.details.card;
    if (card === null) return;
    const { side } = request;
    this.host.post(
      card.port,
      request.shown ? { kind: "shown", ...(side !== undefined ? { side } : {}) } : { kind: "hidden" },
    );
    if (request.shown && side !== undefined) this.host.post(menu.port, { kind: "details_side", side });
    if (request.room === false) this.dropSelection(menu);
  }

  /**
   * A login's row is selected in [menu]: the card shows its details, once its
   * page told their height. The same login's row only moves the card. Hidden
   * while an action runs, or for a handle the menu doesn't list.
   */
  selectDetails(menu: MenuSession, request: Extract<MenuRequest, { kind: "select" }>) {
    const link = menu.details;
    link.selected = request;
    const item = menu.items.get(request.handle);
    if (menu.abort.signal.aborted || menu.busy || menu.shown.kind !== "items" || item === undefined) {
      this.hideDetails(menu);
      return;
    }
    link.row = { top: request.top, height: request.height };
    if (link.handle === request.handle) void this.placeDetails(menu);
    else this.showDetails(menu, item);
  }

  /** The menu selects no login: the card hides. */
  deselectDetails(menu: MenuSession) {
    menu.details.selected = null;
    this.hideDetails(menu);
  }

  /**
   * Focus left the menu and the card: the card hides, and the menu's page
   * drops its selection, so Space shows the card again. The menu stays, and
   * a copy of the card in flight still ends.
   */
  dropSelection(menu: MenuSession) {
    this.deselectDetails(menu);
    this.host.post(menu.port, { kind: "deselect" });
  }

  /**
   * The frame of [menu] or of its card lost focus, which it can't tell from
   * focus moving into the other. The content script of the menu's document
   * tells whether that document still has focus, as while it's in either
   * frame or on the page. It hasn't when the window lost focus, as to the
   * app's prompt: the selection drops, as for `menu_blur`. Focus on the page
   * is the content script's to tell, see `menu_blur`.
   */
  async blurred(menu: MenuSession) {
    if (menu.details.selected === null) return;
    // A document that went has no focus: its menu goes too.
    const focused = await this.host.hasFocus(menu.frame);
    if (!focused && !menu.abort.signal.aborted) this.dropSelection(menu);
  }

  /**
   * Sends [item]'s details to the card's page as a new view, which it renders
   * at once. Its frame keeps its place and height till the page tells the
   * new view's: for that round trip a visible card may show the new view at
   * the old height, and its click gate, restarted, takes no click. The same
   * view again, as after a refresh of the menu, only moves the card.
   */
  private showDetails(menu: MenuSession, item: SuggestItem) {
    const link = menu.details;
    const view = cardView(menu, item);
    if (link.handle === item.handle && link.view !== null && JSON.stringify(link.view) === JSON.stringify(view)) {
      void this.placeDetails(menu);
      return;
    }
    link.handle = item.handle;
    link.view = view;
    link.seq++;
    link.height = null;
    const card = link.card;
    if (card !== null) this.host.post(card.port, { kind: "view", seq: link.seq, handle: item.handle, item: view });
  }

  /** Has the content script show the card at the selected row, once its page told the height of its view. */
  private async placeDetails(menu: MenuSession) {
    const { row, height, handle } = menu.details;
    if (handle === null || row === null || height === null) return;
    await this.host.notify(menu.frame, { kind: "menu_details", top: row.top, row: row.height, height });
  }

  /**
   * Hides the card: no row is selected, an action of the menu runs, or the
   * view lists no logins. Its click gate waits again.
   */
  hideDetails(menu: MenuSession) {
    const link = menu.details;
    if (link.handle === null) return;
    link.handle = null;
    link.view = null;
    link.row = null;
    link.height = null;
    if (link.card !== null) this.host.post(link.card.port, { kind: "hidden" });
    void this.host.notify(menu.frame, { kind: "menu_details_hide" });
  }

  /**
   * A trusted ArrowRight on the row of login [handle] in [menu]: focus moves
   * into the card, when it shows that login at its height, and its page
   * focuses its first field. As for the menu's own `focus`: the content
   * script moves focus into the frame first, then the page takes the
   * background's word. A card that shows another login, or none yet, doesn't.
   */
  async focusDetails(menu: MenuSession, handle: string) {
    const link = menu.details;
    const card = link.card;
    if (!menu.items.has(handle) || card === null || link.handle !== handle || link.height === null) return;
    const seq = link.seq;
    if (!(await this.host.focusPopup(menu.frame, "menu_details"))) return;
    if (link.card === card && link.seq === seq) this.host.post(card.port, { kind: "focus" });
  }

  async onDetailsRequest(card: MenuDetailsSession, request: MenuDetailsRequest) {
    const { link } = card;
    // A card replaced since: its menu closed.
    if (link.card !== card) return;
    const menu = link.menu;
    switch (request.kind) {
      case "bind":
        return;
      case "resize":
        // The height of an older view, which the card no longer shows.
        if (menu === null || link.handle === null || request.seq !== link.seq) return;
        link.height = request.height;
        await this.placeDetails(menu);
        return;
      case "copy":
      case "copy_uri":
        await this.copyDetails(card, request.kind === "copy" ? request.action : { uri: request.index }, request);
        return;
      case "back": {
        // To the login's row: the content script moves focus, then the menu's page takes the background's word.
        if (menu === null) return;
        const handle = link.handle;
        if (!(await this.host.focusPopup(menu.frame, "menu"))) return;
        this.host.post(menu.port, handle === null ? { kind: "focus" } : { kind: "focus_row", handle });
        return;
      }
      case "close":
        if (menu !== null) this.host.closeMenu(menu);
        return;
      case "blurred":
        if (menu !== null) await this.blurred(menu);
        return;
    }
  }

  /**
   * Copies [action]'s field of login [handle], or its linked URI [action]
   * `uri`, which the card shows and the menu still lists, after the checks of
   * a menu fill for the card, see [MenuDetailsHost.guardMenu]. Only for the
   * view [seq] the card shows: a refresh may have replaced its URIs, and an
   * index would then name another. The username and the URIs come from the
   * menu's `suggest`; a URI by its index in the [ItemDetails] the card got,
   * of the background's own item. The password, unless its collections hide
   * it, and the one-time password come from the app, for the page, as for a
   * menu fill: the app prompts for a suggested login. The background writes
   * the clipboard; the card only hears that it did.
   */
  private async copyDetails(
    card: MenuDetailsSession,
    action: CopyAction | UriCopy,
    { handle, seq }: { handle: string; seq: number },
  ) {
    const { link } = card;
    const menu = link.menu;
    const item = menu === null || link.handle !== handle || link.seq !== seq ? undefined : menu.items.get(handle);
    if (menu === null || item === undefined)
      return this.host.post(card.port, { kind: "error", error: "invalid_request" });
    if (action === "copy_password" && !copiesPassword(item))
      return this.host.post(card.port, { kind: "error", error: "not_allowed" });
    if (typeof action === "object" ? detailsUri(item, action) === undefined : !popupActions(item).includes(action))
      return this.host.post(card.port, { kind: "error", error: "invalid_request" });
    await this.host.act(card, async (signal) => {
      if (!(await this.host.guardMenu(menu, "menu_details"))) return;
      const value = await this.detailsValue(menu.frame, item, action, signal);
      // The card or its menu closed while the app asked.
      if (signal.aborted) return;
      await this.host.copyText(value);
      this.host.post(card.port, { kind: "copied" });
    });
  }

  /** The value of [action]'s field or URI of [item], for a copy from the card beside the menu of [frame]. */
  private async detailsValue(
    frame: FrameRef,
    item: SuggestItem,
    action: CopyAction | UriCopy,
    signal: AbortSignal,
  ): Promise<string> {
    if (typeof action === "object") {
      const uri = detailsUri(item, action);
      if (uri === undefined) throw new CallFailure("invalid_request");
      return uri;
    }
    if (action === "copy_username") return item.username ?? "";
    return releaseCopy(this.host.app, action, { handle: item.handle, ...(await this.host.pageOf(frame)) }, signal);
  }
}
