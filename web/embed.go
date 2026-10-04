// Package web embeds the static pages so the Go rendezvous binary serves the
// same site as the Cloudflare Worker.
package web

import "embed"

// FS holds the pages (index, s, privacy, 404), style.css, share.js, the
// brand assets (latch.svg, the PNG icons, og.png), site.webmanifest and
// robots.txt. The sources of the rendered images are in brand/.
//
//go:embed *.html *.css *.js *.svg *.png *.txt *.webmanifest
var FS embed.FS
