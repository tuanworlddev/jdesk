"use strict";
// Target for the automation /input probe: records a real click and typed value.
(function () {
  var button = document.getElementById("input-probe");
  var state = document.getElementById("input-probe-state");
  button.addEventListener("click", function () {
    window.__inputProbeClicked = true;
    state.textContent = "clicked";
  });
})();
