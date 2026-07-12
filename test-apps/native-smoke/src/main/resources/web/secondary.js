"use strict";
document.addEventListener("jdesk-message", function (event) {
  var message = JSON.parse(event.detail);
  if (message.kind === "event" && message.event === "route.probe") {
    window.__routeTarget = message.payload.target;
  }
});
window.__routeReady = "ready";
