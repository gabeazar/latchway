// Renders the brand SVGs to the PNGs that web/ ships. Run from the repo
// root after changing anything in brand/:
//
//   npm install --no-save @resvg/resvg-js && node brand/render.mjs
//
// The PNGs are committed, so nobody needs this to build or deploy; it only
// exists so the SVGs stay the single source of truth. Text in og.svg is
// set in Segoe UI when available (Windows); on other systems resvg falls
// back to whatever sans-serif it finds, which is fine for a preview card.

import { readFileSync, writeFileSync, copyFileSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { Resvg } = require("@resvg/resvg-js");

function render(src, out, width) {
  const svg = readFileSync(src, "utf8");
  const r = new Resvg(svg, {
    fitTo: { mode: "width", value: width },
    font: { loadSystemFonts: true, defaultFontFamily: "Segoe UI" },
    background: "rgba(0,0,0,0)",
  });
  writeFileSync(out, r.render().asPng());
  console.log(`${out}  ${width}px`);
}

render("brand/icon.svg", "web/icon-192.png", 192);
render("brand/icon.svg", "web/icon-512.png", 512);
render("brand/icon.svg", "web/apple-touch-icon.png", 180);
render("brand/og.svg", "web/og.png", 1200);
copyFileSync("brand/latch.svg", "web/latch.svg");
console.log("web/latch.svg  copied");
