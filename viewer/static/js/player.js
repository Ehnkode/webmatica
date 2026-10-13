/* Webmatica map UI: Player marker, follow mode, live updates, URL hash, 3D glue, minimap, popups. */
'use strict';

// ------------------------------------------------------------------ Player

let playerMarker = null;
function drawPlayer() {
  const p = state.player;
  const status = $('#player-status');
  if (!p || !p.active) {
    status.className = 'dot off';
    status.textContent = 'Spieler offline';
  } else {
    status.className = p.recording ? 'dot' : 'dot paused';
    const where = p.dim === state.dim && p.server === state.server ? '' : ` · ${p.dim.replace('the_', '')}`;
    status.textContent = `${p.name || 'Spieler'} ${fmt(p.x)} ${fmt(p.y)} ${fmt(p.z)}${where}${p.recording ? '' : ' · Aufnahme aus'}`;
  }
  const visible = p && p.active && p.dim === state.dim && (!p.server || p.server === state.server);
  if (!visible) {
    overlay.player.clearLayers();
    playerMarker = null;
    return;
  }
  const ll = toLatLng(p.x, p.z);
  if (!playerMarker) {
    const icon = L.divIcon({
      className: '',
      html: `<div class="player-pin"><div class="arrow"></div><img src="${head.url || ''}" alt=""${head.url ? '' : ' style="visibility:hidden"'}></div>`,
      iconSize: [28, 28], iconAnchor: [14, 14],
    });
    playerMarker = L.marker(ll, { icon, zIndexOffset: 1000, keyboard: false }).addTo(overlay.player);
    playerMarker.bindPopup(() => playerPopup(), { offset: [0, -8] });
  } else {
    playerMarker.setLatLng(ll);
  }
  const arrow = playerMarker.getElement() && playerMarker.getElement().querySelector('.arrow');
  // MC yaw 0 = looking towards +Z (south, down on the map); the arrow initially points up.
  if (arrow) arrow.style.transform = `rotate(${p.yaw + 180}deg)`;
  if (state.follow) map.panTo(ll, { animate: true, duration: 0.25 });
}

async function pollPlayer() {
  try {
    onPlayer(await api('/api/player'));
  } catch (_) { onPlayer(null); }
}

/* New report (from the stream or from polling). The display itself runs
 * interpolated per screen frame, see animatePlayer2d(). */
const interp2d = new window.PlayerInterp();
function onPlayer(p) {
  const prev = state.player;
  const wasActive = prev && prev.active;
  state.player = p;
  interp2d.push(p);
  updateHead(state.player);
  if (!p || !p.active || !wasActive || p.dim !== prev.dim || p.server !== prev.server) drawPlayer();
  if (view3d) view3d.updatePlayer(state.player);
  if (state.trail && state.player && state.player.active) extendTrail(state.player);
  radarUpdate(state.player);
  statusText();
}

function animatePlayer2d() {
  requestAnimationFrame(animatePlayer2d);
  if (mode3d || !playerMarker || !state.player || !state.player.active) return;
  const s = interp2d.sample();
  if (!s) return;
  const ll = toLatLng(s.x, s.z);
  playerMarker.setLatLng(ll);
  const arrow = playerMarker.getElement() && playerMarker.getElement().querySelector('.arrow');
  if (arrow) arrow.style.transform = `rotate(${s.yaw + 180}deg)`;
  if (state.follow && !map._animatingZoom) map.panTo(ll, { animate: false, noMoveStart: true });
}
requestAnimationFrame(animatePlayer2d);

let statusAt = 0;
function statusText() {
  if (Date.now() - statusAt < 200) return;   // Don't set the text 20 times per second
  statusAt = Date.now();
  const p = state.player;
  const status = $('#player-status');
  if (!p || !p.active) { status.className = 'dot off'; status.textContent = 'Spieler offline'; return; }
  status.className = p.recording ? 'dot' : 'dot paused';
  const where = p.dim === state.dim && p.server === state.server ? '' : ` · ${p.dim.replace('the_', '')}`;
  status.textContent = `${p.name || 'Spieler'} ${fmt(p.x)} ${fmt(p.y)} ${fmt(p.z)}${where}${p.recording ? '' : ' · Aufnahme aus'}`;
}

/* Head for the map: face + hat layer from the full skin. Rebuilt
 * as soon as the server reports a new skin version. */
const head = { version: -1, url: null };
function updateHead(p) {
  if (!p || p.skinVersion === head.version) return;
  if (!p.hasSkin && !p.name) return;
  head.version = p.skinVersion;
  const img = new Image();
  img.onload = () => {
    const c = document.createElement('canvas');
    c.width = 8; c.height = 8;
    const g = c.getContext('2d');
    g.drawImage(img, 8, 8, 8, 8, 0, 0, 8, 8);
    g.drawImage(img, 40, 8, 8, 8, 0, 0, 8, 8);
    setHead(c.toDataURL());
  };
  img.onerror = () => setHead(p.hasSkin ? `/api/skin/face.png?v=${p.skinVersion}` : null);
  img.src = `/api/skin/full.png?v=${p.skinVersion}`;
}
function setHead(url) {
  head.url = url;
  $('#pc-head').hidden = !url;
  if (url) $('#pc-head').src = url;
  const img = playerMarker && playerMarker.getElement() && playerMarker.getElement().querySelector('img');
  if (img && url) { img.src = url; img.style.visibility = ''; }
}

function playerPopup() {
  const p = state.player || {};
  const d = document.createElement('div');
  d.className = 'pl-pop';
  d.innerHTML = `<b>${esc(p.name || 'Spieler')}</b><span class="muted mono">${fmt(p.x)} ${fmt(p.y)} ${fmt(p.z)}</span>
    <div class="btns"><button data-pl="follow">Karte folgt</button><button data-pl="3d-follow">3D verfolgen</button><button data-pl="3d-ego">3D Ego</button></div>`;
  d.addEventListener('click', async (e) => {
    const b = e.target.closest('[data-pl]');
    if (!b) return;
    map.closePopup();
    if (b.dataset.pl === 'follow') { setFollow(true); return; }
    await set3d(true);
    if (view3d) { view3d.select({ type: 'player' }); view3d.setCam(b.dataset.pl === '3d-ego' ? 'ego' : 'follow'); }
  });
  return d;
}

/* Live position via Server-Sent Events; falls back to polling on problems. */
let playerTimer = null, playerStream = null;
function schedulePlayer() {
  if (document.hidden) return;   // visibilitychange starts it again
  if (playerStream || !window.EventSource) {
    if (!playerStream) { clearInterval(playerTimer); playerTimer = setInterval(pollPlayer, mode3d ? 250 : 1000); }
    return;
  }
  playerStream = new EventSource('/api/player/stream');
  playerStream.onmessage = (e) => { try { onPlayer(JSON.parse(e.data)); } catch (_) { /* ignore */ } };
  playerStream.onerror = () => {
    // The browser reconnects by itself; poll in the meantime
    if (!playerTimer) playerTimer = setInterval(pollPlayer, 1000);
  };
  playerStream.onopen = () => { clearInterval(playerTimer); playerTimer = null; };
}
/* A stream holds one of the browser's few connections to the viewer (Firefox: 6 for all tabs).
   Background tabs give theirs back, otherwise a few open tabs block every request. */
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    if (playerStream) { playerStream.close(); playerStream = null; }
    clearInterval(playerTimer); playerTimer = null;
  } else {
    schedulePlayer();
  }
});

/* Follow: in 2D the map follows, in 3D the camera (follow mode) */
function setFollow(on) {
  state.follow = on;
  $('#btn-follow').classList.toggle('on', on);
  if (mode3d && view3d) {
    if (on && state.player && state.player.active) { view3d.select({ type: 'player' }); view3d.setCam('follow'); }
    else if (on) { state.follow = false; $('#btn-follow').classList.remove('on'); toast('Kein Spieler online – startet Minecraft mit der Mod?'); }
    else view3d.setCam('free');
    return;
  }
  if (on && state.player && state.player.active) {
    const p = state.player;
    if (p.server && p.server !== state.server || p.dim !== state.dim) {
      if (state.worlds.some((w) => w.id === p.server && w.dims.some((d) => d.key === p.dim))) select(p.server, p.dim, null, true);
    }
    map.setView(toLatLng(p.x, p.z), Math.max(map.getZoom(), 0));
  } else if (on) {
    toast('Kein Spieler online – startet Minecraft mit der Mod?');
  }
}
$('#btn-follow').addEventListener('click', () => setFollow(!state.follow));
/* Click on the player chip: jump there once, without following */
$('#pc-jump').addEventListener('click', () => {
  const p = state.player;
  if (!p || !p.active) { toast('Kein Spieler online – startet Minecraft mit der Mod?'); return; }
  if (mode3d && view3d) { view3d.flyTo(p.x, p.y + 12, p.z); return; }
  if (p.server && p.server !== state.server || p.dim !== state.dim) {
    if (state.worlds.some((w) => w.id === p.server && w.dims.some((d) => d.key === p.dim))) select(p.server, p.dim, null, true);
  }
  map.setView(toLatLng(p.x, p.z), Math.max(map.getZoom(), 1));
  flash(Math.floor(p.x), Math.floor(p.z));
});
map.on('dragstart', () => state.follow && setFollow(false));

// ------------------------------------------------------------------ Live updates

async function pollChanges() {
  if (!state.server) return;
  try {
    const q = new URLSearchParams({ srv: state.server, dim: state.dim, since: state.since });
    const r = await api('/api/changes?' + q);
    state.since = r.now;
    if (r.regions.length) {
      // Wait briefly so the server has already re-rendered the tile.
      setTimeout(() => r.regions.forEach(([rx, rz]) => tiles && tiles.refreshRegion(rx, rz)), 300);
      loadMarkers();
    }
  } catch (_) { /* server briefly gone */ }
}

async function pollWorlds() {
  try {
    const r = await api('/api/worlds');
    const before = JSON.stringify(state.worlds.map((w) => [w.id, w.dims.map((d) => d.key)]));
    state.worlds = r.worlds;
    if (JSON.stringify(state.worlds.map((w) => [w.id, w.dims.map((d) => d.key)])) !== before) renderSelectors();
  } catch (_) { /* ignore */ }
}

// ------------------------------------------------------------------ URL hash (#server/dim/layer/x/z/zoom)

function saveHash() {
  if (!state.server) return;
  const c = fromLatLng(map.getCenter());
  const h = `#${encodeURIComponent(state.server)}/${state.dim}/${state.layer}/${c.x}/${c.z}/${map.getZoom()}`;
  history.replaceState(null, '', h);
}
map.on('moveend', saveHash);

function readHash() {
  const p = location.hash.slice(1).split('/');
  if (p.length < 6) return null;
  return { server: decodeURIComponent(p[0]), dim: p[1], layer: p[2], x: Number(p[3]), z: Number(p[4]), zoom: Number(p[5]) };
}

// ------------------------------------------------------------------ 3D

let view3d = null;
let mode3d = false;

async function set3d(on) {
  if (on === mode3d) return;
  if (on) {
    if (sel.on) setSelect(false);
    if (!view3d) {
      try {
        const mod = await import('/static/view3d.js');
        view3d = mod.create($('#view3d'), { toast, onSelect: on3dSelect, onCam: on3dCam, onXray: (x) => xrayStats(x) });
        window.__view3d = view3d;
        view3d.updatePlayer(state.player);
      } catch (e) { toast('3D konnte nicht starten: ' + e.message); return; }
    }
    const c = fromLatLng(map.getCenter());
    let y = 100;
    try {
      const col = await api('/api/column?' + new URLSearchParams({ srv: state.server, dim: state.dim, layer: state.layer, x: c.x, z: c.z }));
      if (col.y !== undefined) y = col.y + 30;
    } catch (_) { /* default height */ }
    $('#view3d').hidden = false;
    document.body.classList.add('mode3d');
    applySight(Number($('#sight3d').value));
    view3d.setFog($('#fog3d').checked);
    view3d.setHour(Number($('#hour3d').value));
    view3d.setFancy($('#fancy3d').checked);
    view3d.setClip(cut.mode, cut.y);
    view3d.setSpawn($('#spawn3d').checked);
    xrayApply();
    view3d.open(state.server, state.dim, c.x, y, c.z);
    view3d.setSchematics(schem.list);
    drawHistory();
    view3d.setWaypoints(state.waypoints);
    view3d.setTrail(state.trail ? trail.points : null);
    if (schem.sel) view3d.select({ type: 'schematic', id: schem.sel }, true);
    miniStart();
  } else {
    miniStop();
    const p = view3d.position();
    view3d.close();
    $('#cam-panel').hidden = true;
    $('#view3d').hidden = true;
    document.body.classList.remove('mode3d');
    map.setView(toLatLng(p.x, p.z), Math.max(map.getZoom(), 0));
  }
  mode3d = on;
  $('#btn-3d').classList.toggle('on', on);
  $('#btn-2d').classList.toggle('on', !on);
  if (on) showHint3d();
  if (state.follow) setFollow(false);
  if (sel.on) setSelect(false);
  if (farmUi.pick3d) pick3dStop();
  renderPickLabel();
  drawFarmArea();
  schedulePlayer();
}

/* 3D: show the hint only briefly, settings behind the gear */
let hint3dTimer = null;
function showHint3d() {
  const h = $('.hint3d');
  h.classList.remove('gone');
  clearTimeout(hint3dTimer);
  hint3dTimer = setTimeout(() => h.classList.add('gone'), 8000);
}
$('.hint3d').addEventListener('mouseenter', showHint3d);

/* Minimap in 3D: small 2D map around the camera, the arrow shows the view direction */
const mini = { map: null, layer: null, me: null, zoom: 0, timer: null, hidden: false };
try { mini.hidden = localStorage.getItem('miniHidden') === '1'; mini.zoom = Number(localStorage.getItem('miniZoom') || 0); } catch (_) { /* ignore */ }
function miniStart() {
  if (!mini.map) {
    mini.map = L.map('mini-map', { crs: L.CRS.Simple, minZoom: MIN_ZOOM, maxZoom: 2, zoomSnap: 1, attributionControl: false, zoomControl: false,
      dragging: false, scrollWheelZoom: false, doubleClickZoom: false, boxZoom: false, keyboard: false, touchZoom: false });
    // Player as on the big map: head with a view-direction arrow
    mini.me = L.marker([0, 0], { keyboard: false, interactive: false, opacity: 0, icon: L.divIcon({
      className: '', iconSize: [22, 22], iconAnchor: [11, 11],
      html: '<div class="player-pin mini-pin"><div class="arrow"></div><img alt="" style="visibility:hidden"></div>' }) }).addTo(mini.map);
    mini.map.on('click', (e) => {
      const c = fromLatLng(e.latlng);
      const p = view3d.position();
      view3d.flyTo(c.x, Math.max(p.y - 20, 70), c.z);
    });
    $('#mini').addEventListener('wheel', (e) => { e.preventDefault(); miniZoom(e.deltaY < 0 ? 1 : -1); }, { passive: false });
  }
  if (mini.layer) mini.map.removeLayer(mini.layer);
  mini.layer = new RegionLayer({ tileSize: 512, minZoom: MIN_ZOOM, maxZoom: 2, maxNativeZoom: 0, minNativeZoom: MIN_ZOOM, keepBuffer: 0 }).addTo(mini.map);
  miniVisible();
  clearInterval(mini.timer);
  mini.timer = setInterval(miniTick, 120);
}
function miniStop() { clearInterval(mini.timer); mini.timer = null; }
function miniVisible() {
  $('#mini').hidden = mini.hidden;
  $('#mini-show').hidden = !mini.hidden;
  if (!mini.hidden && mini.map) { mini.map.invalidateSize(); miniTick(true); }
  try { localStorage.setItem('miniHidden', mini.hidden ? '1' : '0'); } catch (_) { /* ignore */ }
}
function miniZoom(d) {
  mini.zoom = Math.max(-5, Math.min(2, mini.zoom + d));
  try { localStorage.setItem('miniZoom', String(mini.zoom)); } catch (_) { /* ignore */ }
  miniTick(true);
}
function miniTick(force) {
  if (!view3d || !mode3d || mini.hidden || document.hidden) return;
  const p = view3d.position();
  const t = view3d.lookTarget(10);
  const ll = toLatLng(p.x, p.z);
  if (force === true || mini.map.getZoom() !== mini.zoom) mini.map.setView(ll, mini.zoom, { animate: false });
  else mini.map.panTo(ll, { animate: false });
  const ang = Math.atan2(t.x - p.x, -(t.z - p.z)) * 180 / Math.PI;   // 0 = north (up)
  $('#mini-arrow').style.transform = `translate(-50%, -50%) rotate(${ang}deg)`;
  const pl = state.player;
  if (pl && pl.active && pl.dim === state.dim) {
    mini.me.setLatLng(toLatLng(pl.x, pl.z));
    mini.me.setOpacity(1);
    const el = mini.me.getElement();
    if (el) {
      const img = el.querySelector('img');
      if (head.url && img.getAttribute('src') !== head.url) { img.src = head.url; img.style.visibility = ''; }
      el.querySelector('.arrow').style.transform = `rotate(${pl.yaw + 180}deg)`;
    }
  } else mini.me.setOpacity(0);
}
$('#mini-in').addEventListener('click', (e) => { e.stopPropagation(); miniZoom(1); });
$('#mini-out').addEventListener('click', (e) => { e.stopPropagation(); miniZoom(-1); });
$('#mini-hide').addEventListener('click', (e) => { e.stopPropagation(); mini.hidden = true; miniVisible(); });
$('#mini-show').addEventListener('click', () => { mini.hidden = false; miniVisible(); });

function on3dSelect(selection) {
  const isPlayer = selection && selection.type === 'player';
  $('#cam-panel').hidden = !isPlayer && view3d.cam() === 'free';
  if (isPlayer) $('#cam-name').textContent = (state.player && state.player.name) || 'Spieler';
  if (selection && selection.type === 'schematic') selectSchem(selection.id, true);
  else if (schem.sel) selectSchem(null, true);
}
function on3dCam(mode) {
  $$('#cam-modes button').forEach((b) => b.classList.toggle('on', b.dataset.cam === mode));
  if (mode !== 'free') $('#cam-panel').hidden = false;
  // In 3D the follow button shows whether the camera is attached to the player
  state.follow = mode !== 'free';
  $('#btn-follow').classList.toggle('on', state.follow);
}
$('#cam-modes').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-cam]');
  if (b && view3d) view3d.setCam(b.dataset.cam);
});
$('#cam-close').addEventListener('click', () => {
  if (!view3d) return;
  view3d.setCam('free');
  view3d.select(null);
  $('#cam-panel').hidden = true;
});
$('#btn-3d').addEventListener('click', () => mode3d || set3d(true));
$('#btn-2d').addEventListener('click', () => mode3d && set3d(false));

/* Small popups above the bottom bar: a button with data-pop opens one, only one at a time */
function openPop(id) {
  $$('.pop').forEach((p) => { p.hidden = p.id !== id || !p.hidden; });
  $$('[data-pop]').forEach((b) => b.classList.toggle('open', b.dataset.pop === id && !$('#' + id).hidden));
  const p = id && $('#' + id), b = id && $(`[data-pop="${id}"]`);
  if (p && !p.hidden && b) {
    // align above the button, but stay inside the window
    const r = b.getBoundingClientRect();
    p.style.left = Math.max(8, Math.min(innerWidth - p.offsetWidth - 8, r.left + r.width / 2 - p.offsetWidth / 2)) + 'px';
  }
}
$$('[data-pop]').forEach((b) => b.addEventListener('click', (e) => { e.stopPropagation(); openPop(b.dataset.pop); }));
document.addEventListener('pointerdown', (e) => {
  if (!e.target.closest('.pop, [data-pop]')) $$('.pop').forEach((p) => { if (!p.hidden) openPop(null); });
});
const hourText = (h) => `${String(Math.floor(h)).padStart(2, '0')}:${String(Math.round((h % 1) * 60)).padStart(2, '0')}`;
// One slider for view distance: level -> [chunks with full models, far view in blocks]
const SIGHT = [[6, 0], [8, 1024], [12, 2048], [16, 3072], [24, 4096], [32, 6144], [32, 8192], [40, 12288],
  [48, 16384], [48, 24576], [48, 32768], [48, 49152], [48, 65536]];
const sightBlocks = (v) => Math.max(SIGHT[v][0] * 16, SIGHT[v][1]);
function applySight(v) {
  if (!view3d) return;
  view3d.setRadius(SIGHT[v][0]);
  view3d.setLod(SIGHT[v][1]);
}
const sliderText = {
  sight3d: (v) => `${fmt(sightBlocks(v))} Blöcke`,
  hour3d: hourText,
};
// convert the former two sliders (chunks, far view) to the single one
if (prefs.sight3d === undefined && (prefs.lod3d !== undefined || prefs.radius3d !== undefined)) {
  const OLD = [0, 512, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384, 24576, 32768, 49152, 65536];
  const blocks = prefs.lod3dv ? OLD[prefs.lod3d] ?? 4096 : (prefs.lod3d ?? 8) * 512;
  savePref('sight3d', SIGHT.reduce((best, s, i) => (Math.abs(s[1] - blocks) < Math.abs(SIGHT[best][1] - blocks) ? i : best), 0));
}
for (const [id, fn] of [['sight3d', applySight], ['hour3d', (v) => view3d && view3d.setHour(v)]]) {
  const el = $('#' + id);
  if (prefs[id] !== undefined) el.value = prefs[id];
  $(`#${id}-val`).textContent = sliderText[id](Number(el.value));
  el.addEventListener('input', () => {
    $(`#${id}-val`).textContent = sliderText[id](Number(el.value));
    savePref(id, Number(el.value));
    fn(Number(el.value));
  });
}
$('#fancy3d').checked = !!prefs.fancy3d;
$('#hour3d-wrap').hidden = !prefs.fancy3d;
$('#fancy3d').addEventListener('change', (e) => {
  savePref('fancy3d', e.target.checked);
  $('#hour3d-wrap').hidden = !e.target.checked;
  if (view3d) view3d.setFancy(e.target.checked);
});
$('#fog3d').checked = !!prefs.fog3d;
$('#fog3d').addEventListener('change', (e) => { savePref('fog3d', e.target.checked); if (view3d) view3d.setFog(e.target.checked); });
$('#shot3d').addEventListener('click', () => {
  if (!view3d) return;
  const a = Object.assign(document.createElement('a'), { href: view3d.screenshot(), download: `webmatica-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.png` });
  document.body.append(a); a.click(); a.remove();
});
$('#full3d').addEventListener('change', (e) => view3d && view3d.setFull(e.target.checked));
// Mob spawns: just a toggle, not saved
$('#spawn3d').addEventListener('change', (e) => {
  $('#spawn3d-legend').hidden = !e.target.checked;
  if (view3d) view3d.setSpawn(e.target.checked);
});
