// The browser window of a store screenshot: its tab strip and toolbar, and the
// inline icons of the page's fields. window.LOGO comes from src/lib/ui/logo.ts,
// see ../render-screenshots.ts.
(() => {
  // The window alone, for the website, see frame.css: ?plain, square; ?window, rounded on a transparent page.
  const query = new URLSearchParams(location.search);
  if (query.has("plain")) document.documentElement.classList.add("plain");
  if (query.has("window")) document.documentElement.classList.add("plain", "rounded");

  // Material icons.
  const PATHS = {
    back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
    forward: "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z",
    reload:
      "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
    tune: "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
    star: "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
    puzzle:
      "M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 1.21-2.7 2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V22H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 2.5-2.5S21.88 11 20.5 11z",
    more: "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
    close: "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    add: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  };

  const icon = (name, className = "") =>
    `<svg class="${className}" viewBox="0 0 24 24"><path d="${PATHS[name]}"/></svg>`;

  /**
   * The tab strip and the toolbar, with Keyguard's button among the
   * extensions: [pressed] while its popup shows.
   */
  window.browserChrome = ({ tabs, host, path, pressed = false }) => {
    const tabHtml = tabs
      .map(
        (tab) =>
          `<div class="tab${tab.active ? " active" : ""}"><img src="${tab.icon}" alt=""><span class="tab-title">${tab.title}</span>${icon("close")}</div>`,
      )
      .join("");
    return `
      <div class="tabs"><div class="lights"><i></i><i></i><i></i></div>${tabHtml}${icon("add", "tab-add")}</div>
      <div class="toolbar">
        <span class="tb">${icon("back")}</span>
        <span class="tb dim">${icon("forward")}</span>
        <span class="tb">${icon("reload")}</span>
        <div class="omnibox">
          <span class="site-info">${icon("tune")}</span>
          <span class="url">${host}<span class="path">${path}</span></span>
          ${icon("star")}
        </div>
        <span class="tb${pressed ? " pressed" : ""}"><img src="/icon/48.png" alt=""></span>
        <span class="tb">${icon("puzzle")}</span>
        <span class="avatar-chip">A</span>
        <span class="tb">${icon("more")}</span>
      </div>`;
  };

  // The logo on its tile, as the content script draws the inline icon.
  document.addEventListener("DOMContentLoaded", () => {
    const { viewBox, paths, colors, gradient, radius } = window.LOGO;
    const svg = `<svg viewBox="${viewBox}">${paths
      .map((d, index) => `<path fill="${colors[index]}" fill-rule="evenodd" d="${d}"/>`)
      .join("")}</svg>`;
    for (const element of document.querySelectorAll(".inline-icon")) {
      element.style.background = gradient;
      element.style.borderRadius = `${element.offsetWidth * radius}px`;
      element.innerHTML = svg;
    }
  });
})();
