// Package web embeds the static pages so the Go rendezvous binary serves the
// same site as the Cloudflare Worker.
package web

import "embed"

// FS holds index.html, s.html, 404.html, style.css, share.js, latch.svg and
// robots.txt.
//
//go:embed *.html *.css *.js *.svg *.txt
var FS embed.FS
