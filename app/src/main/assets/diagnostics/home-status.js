/* Horizontally scrollable applet strip. Text is always untrusted. */
(function () {
  "use strict";
  var emptyApplets = window.BridgitReportedState ? "Applet status not reported" : "No applets running";
  var slot = { id: "appletStatus", items: [] };
  function drawApplets(slot) {
    var strip = document.getElementById(slot.id);
    strip.textContent = "";
    function entry(item) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = item ? "applet-item" : "applet-empty";
      if (item) {
        button.setAttribute("aria-label", item.description || item.state + " · " + item.name);
        var icon = document.createElement("span"); icon.className = "applet-icon";
        icon.setAttribute("aria-hidden", "true"); icon.textContent = item.icon || "◈";
        var name = document.createElement("span"); name.textContent = item.name;
        button.append(icon, name);
      } else button.textContent = emptyApplets;
      button.addEventListener("click", function () {
        if (window.BridgitHome) window.BridgitHome.open("applets");
      });
      strip.appendChild(button);
    }
    if (slot.items.length) slot.items.forEach(entry); else entry(null);
  }
  window.BridgitHomeStatus = {
    update: function (state) {
      var items = state.applets || [], emptyText = state.emptyApplets || (window.BridgitReportedState && !("applets" in state) ? "Applet status not reported" : "No applets running");
      var changed = JSON.stringify(items) !== JSON.stringify(slot.items) || emptyText !== emptyApplets;
      slot.items = items;
      emptyApplets = emptyText;
      // Preserve horizontal scroll and keyboard focus across unrelated updates.
      if (changed) drawApplets(slot);
    }
  };
  drawApplets(slot);
})();
