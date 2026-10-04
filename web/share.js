// Share page behaviour for /s/<shareId>#<secret>.
//
// Privacy rule for this file: the fragment (the secret) is read only to
// build the deep link that hands it to the app on this device, and to show
// the receiver their own complete link in the command-line hint. It is
// never sent anywhere, logged, or stored.
(function () {
  "use strict";

  var SHARE_RE = /^\/s\/([A-Za-z0-9_-]{22})\/?$/;
  var SECRET_RE = /^#[A-Za-z0-9_-]{43}$/;

  var m = location.pathname.match(SHARE_RE);
  var shareId = m ? m[1] : null;
  var fragment = location.hash;

  var openBtn = document.getElementById("open");
  var status = document.getElementById("status");
  var statusText = document.getElementById("status-text");
  var installHint = document.getElementById("install-hint");
  var notAndroid = document.getElementById("not-android");
  var badLink = document.getElementById("bad-link");
  var cmdLink = document.getElementById("cmd-link");

  function show(el) { el.classList.remove("hidden"); }
  function hide(el) { el.classList.add("hidden"); }

  if (!shareId || !SECRET_RE.test(fragment)) {
    show(badLink);
    hide(openBtn);
    hide(status);
    return;
  }

  // Hand the complete link to the app via its custom scheme. The https
  // version of the same link opens the app directly on Android when App
  // Links verification is in place; this is the fallback path.
  openBtn.setAttribute("href", "latchway://" + location.host + "/s/" + shareId + fragment);
  if (cmdLink) cmdLink.textContent = "https://" + location.host + "/s/" + shareId + fragment;

  var isAndroid = /Android/i.test(navigator.userAgent);
  if (!isAndroid) show(notAndroid);

  // If the page is still visible a moment after tapping, the app most
  // likely isn't installed.
  openBtn.addEventListener("click", function () {
    setTimeout(function () {
      if (!document.hidden) show(installHint);
    }, 1800);
  });

  fetch("/v1/status/" + shareId, { cache: "no-store" })
    .then(function (r) { return r.json(); })
    .then(function (s) {
      if (s && s.active) {
        status.classList.add("online");
        statusText.textContent = "The sender is online. The file is ready.";
      } else {
        status.classList.add("offline");
        statusText.textContent = "The sender isn't online right now. Latchway needs to be running on their device for the file to come through.";
      }
    })
    .catch(function () {
      statusText.textContent = "Couldn't check whether the sender is online. You can still try opening the file.";
    });
})();
