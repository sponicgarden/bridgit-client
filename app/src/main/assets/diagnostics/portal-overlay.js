/* Portal additions to the Home scene, shared by the Bridgit Portal iOS app and the APK (scene.html is unchanged).
   The APK uses only viewer(): the iPhone/iPad (or Android phone) currently viewing it through the portal.
   - the connected phones as one tile at the top right, opposite Tailscale and under Weather, linked to the phone: the
     viewing iPhone/iPad, a hub's satellites and portals, a satellite's hub (viewers()); several are a stack with a count;
   - the phone's name in the centre hexagon, with a small "bridgit" above it, in place of the wordmark and bridge;
     while another phone is shown, the hexagon becomes a 12-pointed star (two hexagons, one slowly turning) with "HUB".
   Everything is drawn into the scene's existing SVG groups and follows its resizes. Text is set with textContent only. */
(function () {
  "use strict";
  var NS = "http://www.w3.org/2000/svg";
  var state = { hub: null, other: false, viewers: [] };
  function $(id) { return document.getElementById(id); }
  function el(tag, attrs, parent) {
    var n = document.createElementNS(NS, tag);
    for (var k in attrs) n.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(n);
    return n;
  }
  function centre() {
    var core = $("core"), m = core && /translate\(([-\d.]+)px,\s*([-\d.]+)px\)/.exec(core.style.transform || "");
    return m ? { x: +m[1], y: +m[2] } : { x: 180, y: 360 };
  }
  function width() {
    var svg = $("scene"), vb = svg && svg.getAttribute("viewBox");
    return vb ? +vb.split(/\s+/)[2] : 360;
  }

  // ---- Centre hexagon: phone name with a small "bridgit" ----
  // Another phone shown (owner, 5 Nov 2026): the hexagon becomes a 12-pointed star, a fixed pink hexagon and a gold
  // one that turns slowly (one turn in 2 minutes), both colours gently pulsing, and the small word reads "HUB".
  var HEX = "0,-42 36.4,-21 36.4,21 0,42 -36.4,21 -36.4,-21";
  function drawStar() {
    var core = $("core"), bridge = $("bridge");
    if (!core || !bridge) return;
    var star = core.querySelector(".portal-star");
    core.classList.toggle("portal-other", state.other);
    if (!state.other) { if (star) star.parentNode.removeChild(star); return; }
    if (star) return;
    star = el("g", { "class": "portal-star" });
    core.insertBefore(star, bridge);
    el("polygon", { "class": "portal-star-a", points: HEX }, star);
    var turn = el("g", { transform: "rotate(30)" }, star);
    el("polygon", { "class": "portal-star-b", points: HEX }, turn);
    if (!(window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches))
      el("animateTransform", { attributeName: "transform", type: "rotate", from: "30", to: "390", dur: "120s", repeatCount: "indefinite" }, turn);
  }
  function drawHub() {
    var bridge = $("bridge");
    if (!bridge || !state.hub) return;
    drawStar();
    var label = bridge.querySelector(".portal-hub");
    if (!label) {
      Array.prototype.forEach.call(bridge.children, function (c) { c.style.display = "none"; });
      label = el("g", { "class": "portal-hub" }, bridge);
      el("text", { "class": "portal-hub-brand", y: -13, "text-anchor": "middle" }, label).textContent = "bridgit";
      el("text", { "class": "portal-hub-name", y: 2, "text-anchor": "middle" }, label);
      el("text", { "class": "portal-hub-name", y: 12, "text-anchor": "middle" }, label);
    }
    label.querySelector(".portal-hub-brand").textContent = state.other ? "HUB" : "bridgit";
    var lines = split(state.hub, 11), names = label.querySelectorAll(".portal-hub-name");
    names[0].textContent = lines[0]; names[1].textContent = lines[1] || "";
    // One line sits in the middle; two lines share it.
    names[0].setAttribute("y", lines[1] ? 2 : 6);
    names.forEach(function (t) {
      t.removeAttribute("textLength"); t.removeAttribute("lengthAdjust");
      if (t.getComputedTextLength && t.getComputedTextLength() > 54) { t.setAttribute("textLength", 54); t.setAttribute("lengthAdjust", "spacingAndGlyphs"); }
    });
  }
  // At most two lines, broken at a space near the middle.
  function split(name, max) {
    name = String(name).trim();
    if (name.length <= max) return [name];
    var best = -1;
    for (var i = 0; i < name.length; i++) if (name[i] === " " && (best < 0 || Math.abs(i - name.length / 2) < Math.abs(best - name.length / 2))) best = i;
    return best > 0 ? [name.slice(0, best), name.slice(best + 1)] : [name];
  }

  // ---- Connected phones, top right (owner, 5 Nov 2026): one tile. One phone is drawn as itself; several as a
  // stack of phones with a count. Tapping it lists them all (BridgitHome.open("connected")), like the hexagon's chooser.
  function clearViewers() {
    [".portal-viewer", ".portal-viewer-link", ".portal-viewer-flow"].forEach(function (q) {
      Array.prototype.forEach.call(document.querySelectorAll(q), function (n) { n.parentNode.removeChild(n); });
    });
  }
  // The stack's caption: "3 portals" when all are one kind, else "3 satellites".
  function counts(list) {
    var kinds = {};
    list.forEach(function (v) { kinds[v.sub || "Portal"] = 1; });
    var k = Object.keys(kinds);
    return k.length === 1 ? k[0].toLowerCase() + "s" : "satellites";
  }
  function drawViewers() {
    var gExt = $("ext"), gTun = $("tunnels");
    if (!gExt || !gTun) return;
    var list = state.viewers || [];
    if (!list.length) { clearViewers(); return; }
    var g = gExt.querySelector(".portal-viewer"), path = gTun.querySelector(".portal-viewer-link"), flow = gTun.querySelector(".portal-viewer-flow");
    if (!g) {
      // A special link: a solid, thicker line with a fine stream of dots running along it.
      path = el("path", { "class": "portal-viewer-link" }, gTun);
      flow = el("path", { "class": "portal-viewer-flow" }, gTun);
      g = el("g", { "class": "ext on portal-viewer" }, gExt);
      el("rect", { x: -20, y: -20, width: 40, height: 40, rx: 9, "class": "box" }, g);
      // Up to three phone outlines, offset like a stack (the scene's symbol font only has the glyphs the APK uses).
      g.devices = [0, 1, 2].map(function () { return { body: el("rect", { "class": "portal-device", rx: 2.6 }, g), notch: el("rect", { "class": "portal-notch", rx: 0.8 }, g) }; });
      g.t0 = el("text", { "class": "cap2 portal-self", x: -28, y: -16, "text-anchor": "end" }, g);
      g.t1 = el("text", { "class": "cap1", x: -28, y: -3, "text-anchor": "end" }, g);
      g.t2 = el("text", { "class": "cap2", x: -28, y: 10, "text-anchor": "end" }, g);
      g.style.cursor = "pointer";
      g.setAttribute("role", "button");
      g.setAttribute("aria-label", "Connected phones");
      g.addEventListener("click", function (e) { e.stopPropagation(); if (window.BridgitHome) window.BridgitHome.open("connected"); }, true);
    }
    var one = list.length === 1, v = list[0];
    var tablet = one && /ipad|tab/i.test(v.kind || "");
    var dw = tablet ? 18 : 12, dh = tablet ? 24 : 22, shown = one ? 1 : Math.min(3, list.length);
    if (!one) { dw = 11; dh = 19; }
    g.devices.forEach(function (d, i) {
      var on = i < shown;
      d.body.style.display = d.notch.style.display = on ? "" : "none";
      if (!on) return;
      // Back phones up and to the right, the front one bottom left; the front one hides the lines behind it.
      var off = one ? 0 : (shown - 1 - i) * 3.5 - (shown - 1) * 1.75;
      var x0 = -dw / 2 + off, y0 = -dh / 2 - off;
      [["x", x0], ["y", y0], ["width", dw], ["height", dh]].forEach(function (a) { d.body.setAttribute(a[0], a[1]); });
      [["x", x0 + dw / 2 - 2.5], ["y", y0 + 2], ["width", 5], ["height", 1.6]].forEach(function (a) { d.notch.setAttribute(a[0], a[1]); });
      d.body.classList.toggle("portal-device-back", !one && i < shown - 1);
    });
    // Later-drawn outlines sit on top: keep the front phone last.
    g.devices.slice(0, shown).forEach(function (d) { g.appendChild(d.body); g.appendChild(d.notch); });
    g.appendChild(g.t0); g.appendChild(g.t1); g.appendChild(g.t2);
    // The phone in your hand, drawn as the tile (a satellite or portal viewing its hub): "This device" above its name.
    g.t0.textContent = one && v.self ? "This device" : "";
    g.t1.textContent = one ? (v.kind || "iPhone") : list.length + " " + counts(list);
    // A stack has one caption line, level with the tile, so it stays clear of the Microphone label below.
    g.t2.textContent = one ? (v.sub || "Portal") : "";
    g.t1.setAttribute("y", one ? -3 : 4);
    var live = list.some(function (x) { return x.live; }), idle = list.every(function (x) { return x.idle; });
    g.classList.toggle("live-view", live);
    g.classList.toggle("idle-view", idle);
    var x = width() - 32, c = centre();
    // Raised clear of the Microphone label below it (owner, 5 Nov 2026).
    var y = 138;
    g.setAttribute("transform", "translate(" + x + " " + y + ")");
    // Cross above the feature circles, then descend through the gap at twelve o'clock.
    var d = "M" + (x - 20) + " " + (y + 12) + " H" + (c.x + 9) + " V" + (c.y - 40);
    [path, flow].forEach(function (p) { p.setAttribute("d", d); p.classList.toggle("live-view", live); p.classList.toggle("idle-view", idle); });
  }

  // Tapping the centre hexagon opens the list of phones (details and switching), like a node tap does.
  function wireHub() {
    var core = $("core");
    if (!core || core.dataset.portalTap) return;
    core.dataset.portalTap = "1";
    core.style.cursor = "pointer";
    core.setAttribute("role", "button");
    core.setAttribute("aria-label", "Phone details and other phones");
    core.addEventListener("click", function (e) {
      e.stopPropagation();
      if (window.BridgitHome) window.BridgitHome.open("phones");
    }, true);
  }

  // The hexagon tap belongs to the iOS app, which names the phone with hub(); the APK never calls hub().
  function redraw() { if (state.hub) wireHub(); drawHub(); drawViewers(); }
  var style = document.createElement("style");
  style.textContent =
    ".portal-hub-brand { font-family: var(--display, system-ui); font-weight: 700; font-size: 6px; fill: var(--muted); letter-spacing: .02em; }" +
    ".portal-hub-name { font-family: var(--display, system-ui); font-weight: 650; font-size: 8.6px; fill: var(--ink); }" +
    ".portal-viewer { opacity: 1 !important; transition: opacity .6s; --c: var(--core, #6b96ff); }" +
    ".portal-viewer .box { stroke-dasharray: none; }" +
    ".portal-device { fill: none; stroke: var(--ink); stroke-width: 1.4; }" +
    ".portal-notch { fill: var(--ink); }" +
    ".portal-self { font-weight: 600; }" +
    ".portal-device-back { fill: var(--panel, #0d1424); }" +
    ".portal-viewer-link { fill: none; stroke: var(--core, #6b96ff); stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round; opacity: .35; }" +
    ".portal-viewer-link.live-view { opacity: .55; filter: drop-shadow(0 0 2px var(--core, #6b96ff)); }" +
    ".portal-viewer-flow { fill: none; stroke: var(--ink); stroke-width: 1.1; stroke-linecap: round; stroke-dasharray: .1 3.2; opacity: 0; }" +
    ".portal-viewer-flow.live-view { opacity: .9; animation: portalFlow 1.6s linear infinite; }" +
    ".portal-viewer.idle-view { opacity: .55 !important; }" +
    ".portal-viewer-link.idle-view { opacity: .15; stroke-dasharray: 3 3; }" +
    "@keyframes portalFlow { to { stroke-dashoffset: -16.5; } }" +
    "#core.portal-other > polygon { display: none; }" +
    ".portal-star polygon { stroke-width: 2.2; stroke-linejoin: round; }" +
    ".portal-star-a { fill: var(--panel, #0d1424); stroke: #ff5fa2; animation: portalPinkD 7s ease-in-out infinite; }" +
    ".portal-star-b { fill: none; stroke: #ffd166; animation: portalGoldD 7s ease-in-out infinite; }" +
    ":root[data-theme=light] .portal-star-a { stroke: #c2185b; animation-name: portalPinkL; }" +
    ":root[data-theme=light] .portal-star-b { stroke: #b26a00; animation-name: portalGoldL; }" +
    "@keyframes portalPinkD { 50% { stroke: #ff8fd0; } }" +
    "@keyframes portalGoldD { 50% { stroke: #ffa94d; } }" +
    "@keyframes portalPinkL { 50% { stroke: #a3137a; } }" +
    "@keyframes portalGoldL { 50% { stroke: #c24e00; } }" +
    "@media (prefers-reduced-motion: reduce) { .portal-viewer-flow.live-view, .portal-star polygon { animation: none; } }";
  document.head.appendChild(style);

  window.BridgitPortal = {
    // other: true while this screen shows another phone (the star); the iOS app passes only the name.
    hub: function (name, other) { state.hub = name; state.other = !!other; wireHub(); drawHub(); },
    // viewer(null) removes the node and its link (the APK hides it when no iPhone/iPad is viewing).
    viewer: function (v) { state.viewers = v ? [v] : []; drawViewers(); },
    // Several connected phones, top to bottom: [{kind, sub, live, idle, self}]; self: the phone showing this screen. [] removes them all.
    viewers: function (list) { state.viewers = list || []; drawViewers(); },
    redraw: redraw
  };
  window.addEventListener("resize", function () { setTimeout(redraw, 0); });
  if (window.ResizeObserver && $("stage")) new ResizeObserver(function () { setTimeout(redraw, 0); }).observe($("stage"));
})();
