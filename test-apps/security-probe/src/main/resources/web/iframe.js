"use strict";
// Runs inside a SAME-ORIGIN (jdesk://app) subframe embedded by the first probe document.
// It honestly probes what native authority a subframe has and then attempts to invoke a
// command that requires a capability the window was NEVER granted (secure:never).
//
// Cross-platform reality of bridge injection (documented, not faked):
//   * macOS (WKWebView) and Linux (WebKitGTK) inject the window.__jdesk world into the
//     TOP frame only (forMainFrameOnly / INJECT_TOP_FRAME) — a subframe has no own bridge.
//   * Windows (WebView2) injects the init script into all frames, so a subframe may have
//     its own window.__jdesk, but the host delivers the per-navigation nonce to the top
//     frame only.
// In every case the security boundary that IS enforced at the IPC layer is the capability
// gate on the top-level origin (spec 12.1/12.2): the runtime cannot distinguish a subframe
// from the top frame at the message boundary, so subframe authority is bounded by the
// window's granted capabilities. This probe reaches the bridge with the parent's valid
// nonce (same-origin DOM access) — the strongest attempt available — and must still be
// denied. Java-side, the ungranted handler must never run.
(function () {
  var out = {
    __sameOriginFrame: true,
    hasOwnBridge: false,
    reachedParentBridge: false,
    haveNonce: false,
    posted: false,
    err: null
  };
  try {
    var id = window.name; // the invoke id the parent handed us via iframe.name
    out.hasOwnBridge = !!(window.__jdesk && typeof window.__jdesk.post === "function");

    var parentBridge = null;
    try {
      parentBridge = window.parent.__jdesk; // same-origin: readable
    } catch (e) {
      out.err = "parent-access:" + (e && e.name);
    }
    out.reachedParentBridge = !!(parentBridge && typeof parentBridge.post === "function");

    // Prefer our own bridge if the platform gave the subframe one; otherwise use the
    // parent's post function (its closure targets the real platform primitive).
    var poster = out.hasOwnBridge ? window.__jdesk.post
      : (out.reachedParentBridge ? parentBridge.post : null);
    var nonce = (window.__jdesk && window.__jdesk.nonce)
      || (parentBridge && parentBridge.nonce) || null;
    out.haveNonce = !!nonce;

    if (poster && nonce && id) {
      var envelope = JSON.stringify({
        v: 1, kind: "invoke", id: id, command: "secure.denied",
        payload: { boom: 1 }, nonce: nonce
      });
      poster(envelope); // genuine invoke issued from the subframe's JS context
      out.posted = true;
    }
  } catch (e2) {
    out.err = (out.err ? out.err + ";" : "") + String(e2 && e2.name);
  }
  try {
    window.parent.postMessage(out, "*");
  } catch (e3) {
    /* parent unreachable: nothing more to report */
  }
})();
