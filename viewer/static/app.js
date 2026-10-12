/* Webmatica – Kartenoberflaeche.
 *
 * Koordinaten: Leaflet CRS.Simple, 1 Einheit = 1 Block.
 * Weltpunkt (x, z) <-> LatLng(-z, x). Zoomstufe 0 = 1 Pixel pro Block,
 * eine Kachel (512 px) = eine Region.
 */
'use strict';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));
const toLatLng = (x, z) => L.latLng(-z, x);
const fromLatLng = (ll) => ({ x: Math.floor(ll.lng), z: Math.floor(-ll.lat) });

const MIN_ZOOM = -6;
const MAX_ZOOM = 4;

const state = {
  worlds: [],
  server: null,
  dim: null,
  layer: null,
  follow: false,
  player: null,
  waypoints: [],
  markers: { signs: [], stashes: [] },
  since: 0,
  trail: false,
};

/* Einstellungen im Browser merken (Fehler egal, z. B. privater Modus). */
const prefs = (() => { try { return JSON.parse(localStorage.getItem('mapper.prefs') || '{}'); } catch (_) { return {}; } })();
function savePref(k, v) { prefs[k] = v; try { localStorage.setItem('mapper.prefs', JSON.stringify(prefs)); } catch (_) { /* egal */ } }

// ------------------------------------------------------------------ Hilfen

async function api(path, opts) {
  const r = await fetch(path, opts);
  if (!r.ok) {
    let msg = r.status + ' ' + r.statusText;
    try { msg = (await r.json()).error || msg; } catch (_) { /* egal */ }
    throw new Error(msg);
  }
  return r.json();
}

let toastTimer = null;
function toast(text) {
  const t = $('#toast');
  t.textContent = text;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = (n) => Math.round(n).toLocaleString(I18N.locale);
const dimOf = () => (state.worlds.find((w) => w.id === state.server) || { dims: [] }).dims.find((d) => d.key === state.dim);

// ------------------------------------------------------------------ Karte

const map = L.map('map', {
  crs: L.CRS.Simple,
  minZoom: MIN_ZOOM,
  maxZoom: MAX_ZOOM,
  zoomSnap: 1,
  attributionControl: false,
  zoomControl: false,
  preferCanvas: true,
});
L.control.zoom({ position: 'bottomright' }).addTo(map);
map.setView(toLatLng(0, 0), -2);

/* Kachel-Layer, das einzelne Kacheln gezielt neu laden kann. */
const RegionLayer = L.GridLayer.extend({
  initialize(opts) {
    L.GridLayer.prototype.initialize.call(this, opts);
    this._imgs = new Map();
    this._version = new Map();
  },
  tileUrl(c) {
    const v = this._version.get(`${c.z}:${c.x}:${c.y}`) || 0;
    return `/api/tile/${encodeURIComponent(state.server)}/${encodeURIComponent(state.dim)}/${state.layer}/${c.z}/${c.x}/${c.y}.png?v=${v}`;
  },
  createTile(coords, done) {
    const img = document.createElement('img');
    img.alt = '';
    img.onload = () => done(null, img);
    img.onerror = () => done(null, img);
    img.src = this.tileUrl(coords);
    this._imgs.set(`${coords.z}:${coords.x}:${coords.y}`, img);
    return img;
  },
  _removeTile(key) {
    const t = this._tiles[key];
    if (t) this._imgs.delete(`${t.coords.z}:${t.coords.x}:${t.coords.y}`);
    L.GridLayer.prototype._removeTile.call(this, key);
  },
  /* Region (rx, rz) hat sich geaendert: betroffene Kacheln aller Zoomstufen neu laden. */
  refreshRegion(rx, rz) {
    for (let z = MIN_ZOOM; z <= 0; z++) {
      const span = 2 ** -z;
      const k = `${z}:${Math.floor(rx / span)}:${Math.floor(rz / span)}`;
      this._version.set(k, Date.now());
      const img = this._imgs.get(k);
      if (img) {
        const [zz, x, y] = k.split(':').map(Number);
        img.src = this.tileUrl({ z: zz, x, y });
      }
    }
  },
});

let tiles = null;
const overlay = {
  waypoints: L.layerGroup().addTo(map),
  signs: L.layerGroup().addTo(map),
  stashes: L.layerGroup().addTo(map),
  player: L.layerGroup().addTo(map),
};

function rebuildTiles() {
  if (tiles) map.removeLayer(tiles);
  tiles = new RegionLayer({ tileSize: 512, minZoom: MIN_ZOOM, maxZoom: MAX_ZOOM, maxNativeZoom: 0, minNativeZoom: MIN_ZOOM, keepBuffer: 1 });
  tiles.addTo(map);
  tiles.bringToBack();
}

// ------------------------------------------------------------------ Auswahl Server / Dimension / Schicht

function renderSelectors() {
  const sel = $('#server');
  sel.innerHTML = state.worlds.map((w) => `<option value="${esc(w.id)}">${esc(w.label)}</option>`).join('');
  sel.value = state.server;
  sel.hidden = state.worlds.length < 2;

  const w = state.worlds.find((x) => x.id === state.server);
  $('#dims').innerHTML = (w ? w.dims : []).map((d) =>
    `<button data-dim="${esc(d.key)}" class="${d.key === state.dim ? 'on' : ''}">${esc(d.label)}</button>`).join('');

  const d = dimOf();
  const layers = d ? d.layers : [];
  $('#layers').innerHTML = layers.length > 1 ? layers.map((l) =>
    `<button data-layer="${l.id}" class="${l.id === state.layer ? 'on' : ''}">${esc(l.label)}</button>`).join('') : '';
}

function select(server, dim, layer, keepView) {
  if (sel.on) setSelect(false);
  const w = state.worlds.find((x) => x.id === server) || state.worlds[0];
  if (!w) return;
  const d = w.dims.find((x) => x.key === dim) || w.dims[0];
  state.server = w.id;
  state.dim = d.key;
  state.layer = (d.layers.find((l) => l.id === layer) || d.layers[0]).id;
  state.baseLayer = state.layer;
  cutRange(d.key);
  if (cut.mode !== 'off' && !(layer || '').startsWith('surface')) state.layer = `surface${cut.y}`;
  state.since = Date.now() / 1000;
  renderSelectors();
  rebuildTiles();
  if (!keepView) showDefaultView(d);
  drawWaypoints();
  loadMarkers();
  drawPlayer();
  loadPlacements();
  loadContainers();
  loadHistoryDates();
  loadTrail();
  saveHash();
  if (mode3d) { mode3d = false; set3d(true); }
}

/* Startansicht: alles Aufgenommene, wenn es klein ist, sonst die zuletzt
 * aufgenommene Stelle (auf 2b2t liegen Aufnahmen oft tausende Bloecke auseinander). */
function showDefaultView(d) {
  const [x0, z0, x1, z1] = d.bounds;
  const bounds = L.latLngBounds(toLatLng(x0, z0), toLatLng(x1, z1));
  if (map.getBoundsZoom(bounds) >= -3) map.fitBounds(bounds, { maxZoom: 1 });
  else map.setView(toLatLng(d.latest[0], d.latest[1]), -1);
}

$('#server').addEventListener('change', (e) => select(e.target.value, null, null));
$('#dims').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-dim]');
  if (!b || b.dataset.dim === state.dim) return;
  // Gleiche Stelle in der anderen Dimension zeigen (Nether = Overworld / 8).
  const c = fromLatLng(map.getCenter());
  const from = state.dim, to = b.dataset.dim;
  let { x, z } = c;
  if (from === 'overworld' && to === 'the_nether') { x /= 8; z /= 8; }
  if (from === 'the_nether' && to === 'overworld') { x *= 8; z *= 8; }
  select(state.server, to, null, true);
  if (to === 'the_end' || from === 'the_end') {
    showDefaultView(dimOf());
  } else {
    map.setView(toLatLng(x, z), map.getZoom());
  }
});
$('#layers').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-layer]');
  if (b) select(state.server, state.dim, b.dataset.layer, true);
});

// ------------------------------------------------------------------ Cursor + Spalten-Info

let infoTimer = null;
map.on('mousemove', (e) => {
  const { x, z } = fromLatLng(e.latlng);
  let txt = `X ${x}  Z ${z}`;
  if (state.dim === 'overworld') txt += `   Nether ${Math.floor(x / 8)} ${Math.floor(z / 8)}`;
  if (state.dim === 'the_nether') txt += `   Overworld ${x * 8} ${z * 8}`;
  $('#cursor').textContent = txt;
  clearTimeout(infoTimer);
  infoTimer = setTimeout(() => columnInfo(x, z), 120);
});

async function columnInfo(x, z) {
  try {
    const q = new URLSearchParams({ srv: state.server, dim: state.dim, layer: state.layer, x, z });
    const c = await api('/api/column?' + q);
    if (!c.known) { $('#info').textContent = 'nicht aufgenommen'; return; }
    if (!c.block) { $('#info').textContent = 'leer'; return; }
    const block = c.block.replace('minecraft:', '').replace(/\[.*\]$/, '');
    $('#info').textContent = `Y ${c.y}  ${block}  ·  ${c.biome.replace('minecraft:', '')}`;
  } catch (_) { $('#info').textContent = ''; }
}

// ------------------------------------------------------------------ Springen

$('#goto').addEventListener('submit', (e) => {
  e.preventDefault();
  const nums = ($('#goto-input').value.match(/-?\d+(\.\d+)?/g) || []).map(Number);
  if (nums.length < 2) { toast('Bitte X und Z eingeben, z. B. „1200 -340“'); return; }
  const [x, z] = nums.length >= 3 ? [nums[0], nums[2]] : nums;
  setFollow(false);
  map.setView(toLatLng(x, z), Math.max(map.getZoom(), 0));
  flash(x, z);
});

function flash(x, z) {
  const c = L.circleMarker(toLatLng(x + 0.5, z + 0.5), { radius: 14, color: '#fff', weight: 2, fill: false }).addTo(map);
  setTimeout(() => map.removeLayer(c), 1500);
}

// ------------------------------------------------------------------ Seitenleiste

/* Seitenleiste: Symbolleiste rechts waehlt den Bereich, nochmal klicken schliesst */
let sideTab = (() => { try { return localStorage.getItem('sideTab') || 'waypoints'; } catch (_) { return 'waypoints'; } })();
function showTab(tab) {
  sideTab = tab;
  try { localStorage.setItem('sideTab', tab); } catch (_) { /* egal */ }
  const btn = $(`#rail [data-tab="${tab}"]`);
  $('#side-title').textContent = btn ? btn.querySelector('span').textContent : '';
  $$('#side .pane').forEach((p) => p.classList.toggle('active', p.dataset.pane === tab));
  railState();
}
function railState() {
  const open = !$('#side').hidden;
  $$('#rail [data-tab]').forEach((b) => b.classList.toggle('active', open && b.dataset.tab === sideTab));
}
function toggleSide(force, tab) {
  const side = $('#side');
  if (tab) showTab(tab);
  side.hidden = force === undefined ? !side.hidden : !force;
  document.body.classList.toggle('side-open', !side.hidden);
  railState();
}
$$('#rail [data-tab]').forEach((b) => b.addEventListener('click', () => {
  const open = !$('#side').hidden;
  if (open && sideTab === b.dataset.tab) toggleSide(false);
  else toggleSide(true, b.dataset.tab);
}));
$('#side-close').addEventListener('click', () => toggleSide(false));
showTab(sideTab);

/* Tastenkuerzel-Hilfe */
function toggleHelp(force) { $('#help').hidden = force === undefined ? !$('#help').hidden : !force; }
$('#btn-help').addEventListener('click', () => toggleHelp());
$('#help-close').addEventListener('click', () => toggleHelp(false));
$('#help').addEventListener('click', (e) => { if (e.target.id === 'help') toggleHelp(false); });

/* Einklappbare Abschnitte merken sich ihren Zustand */
$$('details.fold').forEach((d) => {
  try { d.open = localStorage.getItem('fold:' + d.dataset.fold) === '1'; } catch (_) { /* egal */ }
  d.addEventListener('toggle', () => { try { localStorage.setItem('fold:' + d.dataset.fold, d.open ? '1' : '0'); } catch (_) { /* egal */ } });
});

// ------------------------------------------------------------------ Wegpunkte

async function loadWaypoints() {
  try { state.waypoints = await api('/api/waypoints'); } catch (_) { state.waypoints = []; }
  drawWaypoints();
}

async function saveWaypoints() {
  state.waypoints = await api('/api/waypoints', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(state.waypoints),
  });
  drawWaypoints();
}

function drawWaypoints() {
  overlay.waypoints.clearLayers();
  if (view3d) view3d.setWaypoints(state.waypoints);
  const here = state.waypoints.filter((w) => w.server === state.server && w.dim === state.dim);
  for (const w of here) {
    const icon = L.divIcon({ className: '', html: `<div class="wp-pin" style="background:${esc(w.color)}"></div>`, iconSize: [14, 14], iconAnchor: [7, 7] });
    L.marker(toLatLng(w.x + 0.5, w.z + 0.5), { icon, title: w.name })
      .bindTooltip(esc(w.name), { permanent: true, direction: 'right', offset: [8, 0], className: 'wp-label' })
      .addTo(overlay.waypoints);
  }
  const list = $('#wp-list');
  list.innerHTML = here.length ? here.map((w) => `
    <li data-id="${esc(w.id)}">
      <span class="swatch" style="background:${esc(w.color)}"></span>
      <div class="main"><div class="title">${esc(w.name)}</div><div class="sub mono">${w.x} ${w.y} ${w.z}</div></div>
      <button data-del title="Löschen">✕</button>
    </li>`).join('') : '<li class="empty">Noch keine Wegpunkte in dieser Dimension.</li>';
}

$('#wp-list').addEventListener('click', async (e) => {
  const li = e.target.closest('li[data-id]');
  if (!li) return;
  const w = state.waypoints.find((x) => x.id === li.dataset.id);
  if (!w) return;
  if (e.target.closest('[data-del]')) {
    if (!confirm(`Wegpunkt „${w.name}“ löschen?`)) return;
    state.waypoints = state.waypoints.filter((x) => x !== w);
    await saveWaypoints();
    return;
  }
  setFollow(false);
  map.setView(toLatLng(w.x, w.z), Math.max(map.getZoom(), 0));
});

const WP_COLORS = ['#e5484d', '#3e9bf5', '#f5a524', '#22a06b', '#a970ff', '#ffffff'];
map.on('contextmenu', (e) => {
  const { x, z } = fromLatLng(e.latlng);
  const d = document.createElement('div');
  d.className = 'ctx-pop';
  d.innerHTML = `<div class="muted mono">${x} ${z}</div>
    <button data-ctx="wp">Wegpunkt setzen</button>
    <button data-ctx="goto">Hierhin laufen (Baritone)</button>
    <button data-ctx="stop">Baritone stoppen</button>`;
  d.addEventListener('click', async (ev) => {
    const b = ev.target.closest('[data-ctx]');
    if (!b) return;
    map.closePopup();
    if (b.dataset.ctx === 'goto') return baritone({ kind: 'goto', x, z });
    if (b.dataset.ctx === 'stop') return baritone({ kind: 'stop' });
    let y = 64;
    try {
      const c = await api('/api/column?' + new URLSearchParams({ srv: state.server, dim: state.dim, layer: state.layer, x, z }));
      if (c.y !== undefined) y = c.y + 1;
    } catch (_) { /* Hoehe unbekannt */ }
    const name = prompt(`Wegpunkt bei ${x} ${y} ${z}`, 'Wegpunkt');
    if (!name) return;
    state.waypoints.push({ name, server: state.server, dim: state.dim, x, y, z, color: WP_COLORS[state.waypoints.length % WP_COLORS.length] });
    await saveWaypoints();
    toast('Wegpunkt gespeichert');
  });
  L.popup({ closeButton: false, className: 'ctx' }).setLatLng(e.latlng).setContent(d).openOn(map);
});

/* Baritone: Befehl ablegen, die Mod holt ihn ab und meldet zurueck */
async function baritone(cmd) {
  const p = state.player;
  if (!p || !p.active) { toast('Spieler ist nicht online, Baritone kann nicht starten'); return; }
  if (cmd.kind === 'goto' && p.dim !== state.dim) { toast('Du bist gerade in einer anderen Dimension'); return; }
  try {
    const c = await jsonPost('/api/commands', { srv: p.server || state.server, ...cmd });
    toast({ goto: `Baritone läuft nach ${cmd.x} ${cmd.z} …`, stop: 'Baritone wird gestoppt …', litematica: 'Baritone fängt an zu bauen …',
      clear: 'Baritone räumt den Bauplatz frei …', pause: 'Baritone pausiert …', resume: 'Baritone macht weiter …',
      highlight: 'Kisten werden im Spiel markiert …', fetch: 'Baritone holt die Items …' }[cmd.kind] || 'Baritone …');
    const long = cmd.kind === 'fetch' || cmd.kind === 'store';
    if (long || cmd.kind === 'goto' || cmd.kind === 'litematica' || cmd.kind === 'clear') {
      act.manual = { kind: cmd.kind, since: Date.now(), text: { goto: `läuft nach ${cmd.x} ${cmd.z}`, fetch: 'holt Items aus einer Kiste',
        litematica: 'baut mit Baritone', clear: 'räumt den Bauplatz frei', store: 'lagert ein' }[cmd.kind] };
      renderActivity();
    }
    const tries = long ? 400 : 12;   // Holen dauert: hinlaufen, oeffnen, nehmen
    for (let i = 0; i < tries; i++) {
      await new Promise((r) => setTimeout(r, 500));
      const last = await api('/api/commands/last?' + new URLSearchParams({ id: c.id }));
      if (last && last.id === c.id) {
        toast(`Baritone: ${last.msg}`);
        if (long && act.manual && act.manual.since && cmd.kind === act.manual.kind) { act.manual = null; renderActivity(); }
        return;
      }
    }
    toast('Keine Antwort aus dem Spiel (ist die Mod aktiv?)');
  } catch (e) { toast('Baritone: ' + e.message); }
}

/* Aktivitaet oben in der Mitte: was das Spiel gerade tut, mit Stopp fuer alles */
var act = { auto: null, online: false, manual: null };
async function pollActivity() {
  if (document.hidden || !state.server) return;
  const srv = (state.player && state.player.server) || state.server;
  try {
    const [a, c] = await Promise.all([api('/api/auto?' + new URLSearchParams({ srv })), api('/api/control/state?' + new URLSearchParams({ srv }))]);
    act.auto = a; act.online = !!(c && c.online);
  } catch (_) { act.online = false; }
  if (act.manual && Date.now() - act.manual.since > 180000) act.manual = null;
  renderActivity();
}
const autoRunning = (a) => a && ['plan', 'fetch', 'approach', 'build', 'unpack', 'store', 'return'].includes(a.phase);
/* Nur zeigen, wenn wirklich etwas laeuft oder gerade fertig/abgebrochen ist; sonst unsichtbar */
function renderActivity() {
  const el = $('#activity');
  const a = act.auto;
  let text = null, kind = 'run';
  if (autoRunning(a)) {
    text = `<b>${a.mode === 'store' ? 'Einlagern' : 'Autobau'}</b> ${esc(a.info || AB_PHASE[a.phase] || '')}`;
  } else if (act.manual) {
    text = `<b>Baritone</b> ${esc(act.manual.text)}`;
  } else if (a && (a.phase === 'error' || a.phase === 'done') && a.log && a.log.length) {
    const t = a.log[a.log.length - 1][0];
    if (Date.now() - t < 60000 && t !== act.dismissed) {
      kind = a.phase === 'error' ? 'bad' : 'ok';
      text = `<b>${a.mode === 'store' ? 'Einlagern' : 'Autobau'} ${a.phase === 'error' ? 'angehalten' : 'fertig'}</b> ${esc(a.info || '')}`;
      act.lastT = t;
    }
  }
  el.hidden = !text;
  if (!text) return;
  el.className = `panel activity ${kind}`;
  $('#act-text').innerHTML = text;
  $('#act-stop').hidden = kind !== 'run';
  $('#act-x').hidden = kind === 'run';
}
$('#act-x').addEventListener('click', () => { act.dismissed = act.lastT; renderActivity(); });
$('#act-stop').addEventListener('click', async () => {
  const srv = (state.player && state.player.server) || state.server;
  try { await jsonPost('/api/commands', { srv, kind: 'stop' }); toast('Alles gestoppt'); } catch (e) { toast('Stopp: ' + e.message); }
  act.manual = null;
  pollActivity();
});
$('#act-text').addEventListener('click', () => {
  const a = act.auto;
  if (a && a.mode === 'store') { toggleSide(true, 'chests'); return; }
  if (a && a.pid) {
    selectSchem(a.pid);
    const b = $('.sp-tabs button[data-sptab="build"]');
    if (b) b.click();
  }
});
setInterval(pollActivity, 1500);

// ------------------------------------------------------------------ Schilder + Stashes

async function loadMarkers() {
  try {
    state.markers = await api('/api/markers?' + new URLSearchParams({ srv: state.server, dim: state.dim, layer: state.layer }));
  } catch (_) { state.markers = { signs: [], stashes: [] }; }
  drawMarkers();
}

function drawMarkers() {
  overlay.signs.clearLayers();
  overlay.stashes.clearLayers();
  const filter = $('#sign-filter').value.trim().toLowerCase();
  const signs = state.markers.signs.filter((s) => !filter || s.lines.join(' ').toLowerCase().includes(filter));
  if ($('#show-signs').checked) {
    const icon = L.divIcon({ className: '', html: '<div class="sign-pin"></div>', iconSize: [12, 12], iconAnchor: [6, 6] });
    for (const s of signs) {
      L.marker(toLatLng(s.x + 0.5, s.z + 0.5), { icon })
        .bindPopup(`<div class="sign-text">${esc(s.lines.join('\n'))}</div><div class="muted mono">${s.x} ${s.y} ${s.z}</div>`)
        .addTo(overlay.signs);
    }
  }
  $('#sign-list').innerHTML = signs.length ? signs.slice(0, 500).map((s) => `
    <li data-x="${s.x}" data-z="${s.z}"><div class="main"><div class="title">${esc(s.lines.join(' · '))}</div>
    <div class="sub mono">${s.x} ${s.y} ${s.z}</div></div></li>`).join('')
    : '<li class="empty">Keine Schilder gefunden.</li>';

  const min = Number($('#stash-min').value) || 1;
  const stashes = state.markers.stashes.filter((s) => s.count >= min).sort((a, b) => b.count - a.count);
  if ($('#show-stashes').checked) {
    for (const s of stashes) {
      const r = Math.min(26, 6 + Math.sqrt(s.count) * 2);
      L.circleMarker(toLatLng(s.cx * 16 + 8, s.cz * 16 + 8), { radius: r, color: '#f5a524', weight: 2, fillOpacity: 0.25 })
        .bindPopup(stashText(s)).addTo(overlay.stashes);
    }
  }
  $('#stash-list').innerHTML = stashes.length ? stashes.slice(0, 500).map((s) => `
    <li data-x="${s.cx * 16 + 8}" data-z="${s.cz * 16 + 8}"><div class="main"><div class="title">${s.count} Behälter</div>
    <div class="sub">${esc(Object.entries(s.types).map(([k, v]) => `${v}× ${k}`).join(', '))}</div>
    <div class="sub mono">Chunk ${s.cx} ${s.cz} · Block ${s.cx * 16 + 8} ${s.cz * 16 + 8}</div></div></li>`).join('')
    : '<li class="empty">Nichts über der Schwelle.</li>';
}

function stashText(s) {
  return `<b>${s.count} Behälter</b><br>${esc(Object.entries(s.types).map(([k, v]) => `${v}× ${k}`).join(', '))}<br><span class="muted mono">${s.cx * 16} ${s.cz * 16} … ${s.cx * 16 + 15} ${s.cz * 16 + 15}</span>`;
}

['#sign-filter', '#stash-min'].forEach((s) => $(s).addEventListener('input', drawMarkers));
['#show-signs', '#show-stashes'].forEach((s) => $(s).addEventListener('change', drawMarkers));
['#sign-list', '#stash-list'].forEach((s) => $(s).addEventListener('click', (e) => {
  const li = e.target.closest('li[data-x]');
  if (!li) return;
  setFollow(false);
  map.setView(toLatLng(Number(li.dataset.x), Number(li.dataset.z)), Math.max(map.getZoom(), 1));
}));

// ------------------------------------------------------------------ Spieler

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
  // MC-Yaw 0 = Blick nach +Z (Sueden, auf der Karte unten); Pfeil zeigt anfangs nach oben.
  if (arrow) arrow.style.transform = `rotate(${p.yaw + 180}deg)`;
  if (state.follow) map.panTo(ll, { animate: true, duration: 0.25 });
}

async function pollPlayer() {
  try {
    onPlayer(await api('/api/player'));
  } catch (_) { onPlayer(null); }
}

/* Neue Meldung (aus dem Stream oder vom Abfragen). Die Darstellung selbst laeuft
 * pro Bildschirm-Frame interpoliert, siehe animatePlayer2d(). */
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
  if (Date.now() - statusAt < 200) return;   // Text nicht 20x pro Sekunde neu setzen
  statusAt = Date.now();
  const p = state.player;
  const status = $('#player-status');
  if (!p || !p.active) { status.className = 'dot off'; status.textContent = 'Spieler offline'; return; }
  status.className = p.recording ? 'dot' : 'dot paused';
  const where = p.dim === state.dim && p.server === state.server ? '' : ` · ${p.dim.replace('the_', '')}`;
  status.textContent = `${p.name || 'Spieler'} ${fmt(p.x)} ${fmt(p.y)} ${fmt(p.z)}${where}${p.recording ? '' : ' · Aufnahme aus'}`;
}

/* Kopf fuer die Karte: Gesicht + Hutschicht aus dem vollen Skin. Wird neu
 * gebaut, sobald der Server eine neue Skin-Version meldet. */
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

/* Live-Position per Server-Sent Events; faellt bei Problemen auf Abfragen zurueck. */
let playerTimer = null, playerStream = null;
function schedulePlayer() {
  if (playerStream || !window.EventSource) {
    if (!playerStream) { clearInterval(playerTimer); playerTimer = setInterval(pollPlayer, mode3d ? 250 : 1000); }
    return;
  }
  playerStream = new EventSource('/api/player/stream');
  playerStream.onmessage = (e) => { try { onPlayer(JSON.parse(e.data)); } catch (_) { /* egal */ } };
  playerStream.onerror = () => {
    // Browser verbindet selbst neu; solange abfragen
    if (!playerTimer) playerTimer = setInterval(pollPlayer, 1000);
  };
  playerStream.onopen = () => { clearInterval(playerTimer); playerTimer = null; };
}

function setFollow(on) {
  state.follow = on;
  $('#btn-follow').classList.toggle('on', on);
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
map.on('dragstart', () => state.follow && setFollow(false));

// ------------------------------------------------------------------ Live-Aktualisierung

async function pollChanges() {
  if (!state.server) return;
  try {
    const q = new URLSearchParams({ srv: state.server, dim: state.dim, since: state.since });
    const r = await api('/api/changes?' + q);
    state.since = r.now;
    if (r.regions.length) {
      // Kurz warten, damit der Server die Kachel schon neu gerendert hat.
      setTimeout(() => r.regions.forEach(([rx, rz]) => tiles && tiles.refreshRegion(rx, rz)), 300);
      loadMarkers();
    }
  } catch (_) { /* Server kurz weg */ }
}

async function pollWorlds() {
  try {
    const r = await api('/api/worlds');
    const before = JSON.stringify(state.worlds.map((w) => [w.id, w.dims.map((d) => d.key)]));
    state.worlds = r.worlds;
    if (JSON.stringify(state.worlds.map((w) => [w.id, w.dims.map((d) => d.key)])) !== before) renderSelectors();
  } catch (_) { /* egal */ }
}

// ------------------------------------------------------------------ URL-Hash (#server/dim/layer/x/z/zoom)

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
        view3d = mod.create($('#view3d'), { toast, onSelect: on3dSelect, onCam: on3dCam });
        window.__view3d = view3d;
        view3d.updatePlayer(state.player);
      } catch (e) { toast('3D konnte nicht starten: ' + e.message); return; }
    }
    const c = fromLatLng(map.getCenter());
    let y = 100;
    try {
      const col = await api('/api/column?' + new URLSearchParams({ srv: state.server, dim: state.dim, layer: state.layer, x: c.x, z: c.z }));
      if (col.y !== undefined) y = col.y + 30;
    } catch (_) { /* Standardhoehe */ }
    $('#view3d').hidden = false;
    document.body.classList.add('mode3d');
    view3d.setRadius(Number($('#radius3d').value));
    view3d.setLod(Number($('#lod3d').value));
    view3d.setFog($('#fog3d').checked);
    view3d.setHour(Number($('#hour3d').value));
    view3d.setFancy($('#fancy3d').checked);
    view3d.setClip(cut.mode, cut.y);
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
  if (on) showHint3d();
  else $('#set3d').hidden = true;
  schedulePlayer();
}

/* 3D: Hinweis nur kurz zeigen, Einstellungen hinter dem Zahnrad */
let hint3dTimer = null;
function showHint3d() {
  const h = $('#hint3d-text');
  h.classList.remove('gone');
  clearTimeout(hint3dTimer);
  hint3dTimer = setTimeout(() => h.classList.add('gone'), 8000);
}
$('#set3d-btn').addEventListener('click', () => {
  const p = $('#set3d');
  p.hidden = !p.hidden;
  $('#set3d-btn').classList.toggle('on', !p.hidden);
});
$('.hint3d').addEventListener('mouseenter', showHint3d);

/* Minimap in 3D: kleine 2D-Karte um die Kamera, Pfeil zeigt die Blickrichtung */
const mini = { map: null, layer: null, me: null, zoom: 0, timer: null, hidden: false };
try { mini.hidden = localStorage.getItem('miniHidden') === '1'; mini.zoom = Number(localStorage.getItem('miniZoom') || 0); } catch (_) { /* egal */ }
function miniStart() {
  if (!mini.map) {
    mini.map = L.map('mini-map', { crs: L.CRS.Simple, minZoom: MIN_ZOOM, maxZoom: 2, zoomSnap: 1, attributionControl: false, zoomControl: false,
      dragging: false, scrollWheelZoom: false, doubleClickZoom: false, boxZoom: false, keyboard: false, touchZoom: false });
    mini.me = L.circleMarker([0, 0], { radius: 5, color: '#fff', weight: 2, fillColor: '#22a06b', fillOpacity: 1 }).addTo(mini.map);
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
  try { localStorage.setItem('miniHidden', mini.hidden ? '1' : '0'); } catch (_) { /* egal */ }
}
function miniZoom(d) {
  mini.zoom = Math.max(-5, Math.min(2, mini.zoom + d));
  try { localStorage.setItem('miniZoom', String(mini.zoom)); } catch (_) { /* egal */ }
  miniTick(true);
}
function miniTick(force) {
  if (!view3d || !mode3d || mini.hidden || document.hidden) return;
  const p = view3d.position();
  const t = view3d.lookTarget(10);
  const ll = toLatLng(p.x, p.z);
  if (force === true || mini.map.getZoom() !== mini.zoom) mini.map.setView(ll, mini.zoom, { animate: false });
  else mini.map.panTo(ll, { animate: false });
  const ang = Math.atan2(t.x - p.x, -(t.z - p.z)) * 180 / Math.PI;   // 0 = Norden (oben)
  $('#mini-arrow').style.transform = `translate(-50%, -50%) rotate(${ang}deg)`;
  const pl = state.player;
  if (pl && pl.active && pl.dim === state.dim) { mini.me.setLatLng(toLatLng(pl.x, pl.z)); mini.me.setStyle({ opacity: 1, fillOpacity: 1 }); }
  else mini.me.setStyle({ opacity: 0, fillOpacity: 0 });
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
$('#btn-3d').addEventListener('click', () => set3d(!mode3d));
const hourText = (h) => `${String(Math.floor(h)).padStart(2, '0')}:${String(Math.round((h % 1) * 60)).padStart(2, '0')}`;
const sliderText = {
  radius3d: (v) => `${v}`,
  lod3d: (v) => (v > 0 ? `${fmt(v * 512)} Blöcke` : 'aus'),
  hour3d: hourText,
};
for (const [id, fn] of [['radius3d', 'setRadius'], ['lod3d', 'setLod'], ['hour3d', 'setHour']]) {
  const el = $('#' + id);
  if (prefs[id] !== undefined) el.value = prefs[id];
  $(`#${id}-val`).textContent = sliderText[id](Number(el.value));
  el.addEventListener('input', () => {
    $(`#${id}-val`).textContent = sliderText[id](Number(el.value));
    savePref(id, Number(el.value));
    if (view3d) view3d[fn](Number(el.value));
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

// ------------------------------------------------------------------ Schematic-Auswahl

const DIM_Y = { overworld: [-64, 319], the_nether: [0, 127], the_end: [0, 255] };
const sel = { on: false, start: null, rect: null, box: null };

function setSelect(on) {
  if (on && mode3d) return;
  sel.on = on;
  $('#btn-select').classList.toggle('on', on);
  document.body.classList.toggle('selecting', on);
  $('#sel-panel').hidden = !on;
  if (on) {
    if (state.follow) setFollow(false);
    map.dragging.disable();
    const [y0, y1] = DIM_Y[state.dim] || [-64, 319];
    $('#sel-y0').value = y0;
    $('#sel-y1').value = y1;
  } else {
    map.dragging.enable();
    if (sel.rect) { sel.rect.remove(); sel.rect = null; }
    sel.box = null;
    sel.start = null;
  }
  updateSel();
}

function updateSel() {
  const b = sel.box;
  $('#sel-go').disabled = !b;
  $('#sel-edit').disabled = !b;
  if (!b) { $('#sel-size').textContent = ''; $('#sel-hint').hidden = false; return; }
  $('#sel-hint').hidden = true;
  const y0 = Number($('#sel-y0').value), y1 = Number($('#sel-y1').value);
  const w = b.x1 - b.x0 + 1, l = b.z1 - b.z0 + 1, h = Math.abs(y1 - y0) + 1;
  $('#sel-size').textContent = `${fmt(w)} × ${fmt(h)} × ${fmt(l)}`;
  $('#sel-panel').title = `${b.x0} ${b.z0}  bis  ${b.x1} ${b.z1}`;
}

function drawSel(a, b) {
  sel.box = { x0: Math.min(a.x, b.x), z0: Math.min(a.z, b.z), x1: Math.max(a.x, b.x), z1: Math.max(a.z, b.z) };
  const bounds = L.latLngBounds(toLatLng(sel.box.x0, sel.box.z0), toLatLng(sel.box.x1 + 1, sel.box.z1 + 1));
  if (sel.rect) sel.rect.setBounds(bounds);
  else sel.rect = L.rectangle(bounds, { color: '#22a06b', weight: 2, fillOpacity: 0.12, interactive: false }).addTo(map);
  updateSel();
}

map.on('mousedown', (e) => {
  if (!sel.on || e.originalEvent.button !== 0) return;
  sel.start = fromLatLng(e.latlng);
  drawSel(sel.start, sel.start);
});
map.on('mousemove', (e) => { if (sel.on && sel.start) drawSel(sel.start, fromLatLng(e.latlng)); });
map.on('mouseup', () => { sel.start = null; });

async function downloadSel() {
  const b = sel.box;
  if (!b) return;
  const name = $('#sel-name').value.trim() || 'auswahl';
  const q = new URLSearchParams({
    srv: state.server, dim: state.dim, name,
    x0: b.x0, z0: b.z0, x1: b.x1, z1: b.z1, y0: $('#sel-y0').value, y1: $('#sel-y1').value,
  });
  const btn = $('#sel-go');
  btn.disabled = true;
  btn.textContent = 'Wird erstellt …';
  try {
    const r = await fetch('/api/schem?' + q);
    if (!r.ok) {
      let msg = r.status + ' ' + r.statusText;
      try { msg = (await r.json()).error || msg; } catch (_) { /* egal */ }
      throw new Error(msg);
    }
    const info = JSON.parse(r.headers.get('X-Schem-Info') || '{}');
    const url = URL.createObjectURL(await r.blob());
    const a = Object.assign(document.createElement('a'), { href: url, download: name + '.schem' });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    let msg = `${name}.schem: ${fmt(info.blocks || 0)} Blöcke`;
    if (info.blockEntities) msg += `, ${fmt(info.blockEntities)} Kisten/Schilder`;
    if (info.coverage < 1) msg += `, ${Math.round((1 - info.coverage) * 100)} % nicht aufgezeichnet (Luft)`;
    toast(msg);
  } catch (e) {
    toast('Export fehlgeschlagen: ' + e.message);
  } finally {
    btn.textContent = '.schem herunterladen';
    btn.disabled = !sel.box;
  }
}

$('#btn-select').addEventListener('click', () => setSelect(!sel.on));
$('#sel-cancel').addEventListener('click', () => setSelect(false));
$('#sel-go').addEventListener('click', downloadSel);
$('#sel-edit').addEventListener('click', () => {
  const b = sel.box;
  if (!b) return;
  openEditor({ cut: { srv: state.server, dim: state.dim, name: $('#sel-name').value.trim() || 'ausschnitt',
    x0: b.x0, z0: b.z0, x1: b.x1, z1: b.z1, y0: Number($('#sel-y0').value), y1: Number($('#sel-y1').value) } });
});
$('#sel-y0').addEventListener('input', updateSel);
$('#sel-y1').addEventListener('input', updateSel);

// ------------------------------------------------------------------ Spur

const trail = { points: [], line: null, lastLoad: 0 };

function setTrail(on) {
  state.trail = on;
  savePref('trail', on);
  $('#btn-trail').classList.toggle('on', on);
  if (on) loadTrail();
  else { trail.points = []; drawTrail(); }
}
$('#btn-trail').addEventListener('click', () => setTrail(!state.trail));

async function loadTrail() {
  if (!state.trail || !state.server) return;
  try {
    trail.points = await api('/api/trail?' + new URLSearchParams({ srv: state.server, dim: state.dim, n: 20000 }));
  } catch (_) { trail.points = []; }
  trail.lastLoad = Date.now();
  drawTrail();
}

function extendTrail(p) {
  if (p.dim !== state.dim || (p.server && p.server !== state.server)) return;
  const last = trail.points[trail.points.length - 1];
  if (last && Math.hypot(p.x - last[0], p.z - last[2]) < 3) return;
  trail.points.push([p.x, p.y, p.z, Date.now() / 1000]);
  if (Date.now() - trail.lastLoad > 2000) { trail.lastLoad = Date.now(); drawTrail(); }
}

function drawTrail() {
  if (trail.line) { map.removeLayer(trail.line); trail.line = null; }
  const pts = state.trail ? trail.points : [];
  if (view3d && mode3d) view3d.setTrail(pts.length ? pts : null);
  if (pts.length < 2) return;
  // Bei Spruengen (Teleport, Luecke in der Aufnahme) Linie unterbrechen
  const parts = [[]];
  pts.forEach((q, i) => {
    if (i && Math.hypot(q[0] - pts[i - 1][0], q[2] - pts[i - 1][2]) > 120) parts.push([]);
    parts[parts.length - 1].push(toLatLng(q[0], q[2]));
  });
  trail.line = L.polyline(parts, { color: '#ffd23f', weight: 2, opacity: 0.85, interactive: false }).addTo(map);
}

// ------------------------------------------------------------------ Schematics

const schem = { lib: [], list: [], sel: null, mats: null, check: null, layers: new Map(), drag: null, saveTimer: null };

const placedHere = () => schem.list.filter((p) => p.server === state.server && p.dim === state.dim);
const selPl = () => schem.list.find((p) => p.id === schem.sel);
const jsonPost = (path, body) => api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

async function loadLibrary() {
  try { schem.lib = (await api('/api/schematics/library')).files; } catch (_) { schem.lib = []; }
  renderLibrary();
}

async function loadPlacements() {
  if (!state.server) return;
  try { schem.list = await api('/api/schematics?' + new URLSearchParams({ srv: state.server, dim: state.dim })); } catch (_) { schem.list = []; }
  if (schem.sel && !selPl()) selectSchem(null);
  schemChanged();
}

/* Alles neu zeichnen, was von der Liste abhaengt. */
function schemChanged() {
  drawSchems2D();
  renderPlaced();
  if (view3d) view3d.setSchematics(schem.list);
  renderEditor();
}

function renderLibrary() {
  const f = $('#lib-filter').value.trim().toLowerCase();
  const files = schem.lib.filter((e) => !f || e.file.toLowerCase().includes(f));
  $('#lib-count').textContent = schem.lib.length ? `(${schem.lib.length})` : '';
  $('#lib-list').innerHTML = files.length ? files.slice(0, 300).map((e) => `
    <li data-file="${esc(e.file)}"><div class="main"><div class="title">${esc(e.name)}</div>
    <div class="sub">${esc(e.folder)} · ${fmt(e.bytes / 1024)} KB</div></div><button data-edit title="Im Editor öffnen">✎</button><button data-place>Platzieren</button></li>`).join('')
    : `<li class="empty">${schem.lib.length ? 'Nichts gefunden.' : 'Keine Schematics gefunden. Lade eine hoch oder lege sie in den Litematica-Ordner „schematics“.'}</li>`;
  if ($('#edp-list')) renderEdLib();
}
$('#lib-filter').addEventListener('input', renderLibrary);

/* Editor-Reiter: neu, aus der Welt, hochladen, vorhandene oeffnen */
function renderEdLib() {
  const f = $('#edp-filter').value.trim().toLowerCase();
  const files = schem.lib.filter((e) => !f || e.file.toLowerCase().includes(f));
  $('#edp-list').innerHTML = files.length ? files.slice(0, 300).map((e) => `
    <li data-file="${esc(e.file)}"><div class="main"><div class="title">${esc(e.name)}</div>
    <div class="sub">${esc(e.folder)} · ${fmt(e.bytes / 1024)} KB</div></div><button data-edit>Öffnen</button></li>`).join('')
    : `<li class="empty">${schem.lib.length ? 'Nichts gefunden.' : 'Noch keine Schematics.'}</li>`;
}
$('#edp-filter').addEventListener('input', renderEdLib);
$('#edp-list').addEventListener('click', (e) => {
  const li = e.target.closest('li[data-file]');
  if (li) openEditor({ file: li.dataset.file });
});
$('#edp-new').addEventListener('click', () => $('#lib-new').click());
$('#edp-up').addEventListener('click', () => $('#lib-upload').click());
$('#edp-cut').addEventListener('click', () => { toggleSide(false); setSelect(true); });

function renderPlaced() {
  const here = placedHere();
  $('#placed-list').innerHTML = here.length ? here.map((p) => `
    <li data-id="${esc(p.id)}" class="${p.id === schem.sel ? 'sel' : ''} ${p.visible === false ? 'hidden' : ''}">
      <div class="main"><div class="title">${esc(p.name)}${p.ingame ? `<span class="badge-ingame ${p.ingameState === 'ok' ? '' : 'wait'}" title="${esc(ingameText(p))}">im Spiel</span>` : ''}${p._pct != null ? `<span class="badge-ingame pct">${p._pct} %</span>` : ''}</div>
      <div class="sub mono">${p.x} ${p.y} ${p.z} · ${p.size.join('×')}</div>${p.error ? `<div class="err">${esc(p.error)}</div>` : ''}</div>
      <button data-vis title="Ein-/Ausblenden">${p.visible === false ? '◌' : '●'}</button>
      <button data-del title="Entladen">✕</button></li>`).join('')
    : '<li class="empty">Keine Schematic in dieser Dimension geladen.</li>';
}

/* Wo landet eine neue Schematic? 3D: vor der Kamera. Karte: Kartenmitte auf dem Boden. */
async function placeTarget() {
  if (mode3d && view3d) {
    const t = view3d.lookTarget(14);
    return { x: Math.floor(t.x), y: Math.floor(t.y), z: Math.floor(t.z) };
  }
  const c = fromLatLng(map.getCenter());
  let y = 64;
  try {
    const col = await api('/api/column?' + new URLSearchParams({ srv: state.server, dim: state.dim, layer: state.layer, x: c.x, z: c.z }));
    if (col.y !== undefined) y = col.y + 1;
  } catch (_) { /* Standardhoehe */ }
  return { x: c.x, y, z: c.z };
}

async function placeFile(file) {
  try {
    const pos = await placeTarget();
    const pl = await jsonPost('/api/schematics/place', { file, server: state.server, dim: state.dim, ...pos });
    // Mitte statt Ecke an das Ziel setzen
    pl.x -= Math.floor(pl.size[0] / 2);
    pl.z -= Math.floor(pl.size[2] / 2);
    schem.list.push(pl);
    await jsonPost('/api/schematics/update', { id: pl.id, x: pl.x, z: pl.z });
    selectSchem(pl.id);
    toast(`${pl.name} platziert (${pl.size.join(' × ')})`);
  } catch (e) { toast('Platzieren fehlgeschlagen: ' + e.message); }
}

$('#lib-list').addEventListener('click', (e) => {
  const li = e.target.closest('li[data-file]');
  if (!li) return;
  if (e.target.closest('[data-edit]')) openEditor({ file: li.dataset.file });
  else placeFile(li.dataset.file);
});

/* Editor in neuem Tab: Datei (wird ggf. als Kopie bearbeitbar gemacht) oder Platzierung */
async function openEditor(req, extra) {
  const w = window.open('about:blank', '_blank');
  try {
    const r = await jsonPost(req.cut ? '/api/editor/cut' : req.new ? '/api/editor/new' : '/api/editor/open', req.cut || req.new || req);
    const q = new URLSearchParams({ file: r.file, srv: state.server, dim: state.dim });
    if (r.pid) q.set('pid', r.pid);
    if (r.origin) q.set('origin', r.origin.join(','));
    if (w) w.location = '/editor?' + q; else location.href = '/editor?' + q;
    loadLibrary();
    loadPlacements();
  } catch (e) { if (w) w.close(); toast('Editor: ' + e.message); }
}
$('#lib-new').addEventListener('click', () => {
  const name = prompt('Name der neuen Schematic:', 'mein bau');
  if (name) openEditor({ new: { name, size: [16, 16, 16] } });
});
$('#sp-edit').addEventListener('click', () => { const pl = selPl(); if (pl) openEditor({ pid: pl.id }); });
// Zurueck aus dem Editor: geaenderte Dateien neu laden
window.addEventListener('focus', () => { if (state.server) { loadPlacements(); loadLibrary(); } });

$('#lib-upload').addEventListener('change', async (e) => {
  const files = Array.from(e.target.files);
  e.target.value = '';
  let last = null;
  for (const f of files) {
    try {
      const r = await api('/api/schematics/upload?' + new URLSearchParams({ name: f.name }), { method: 'POST', body: f });
      last = r.file;
      toast(`${f.name} hochgeladen`);
    } catch (err) { toast(`${f.name}: ${err.message}`); }
  }
  await loadLibrary();
  if (files.length === 1 && last) placeFile(last);
});

$('#placed-list').addEventListener('click', async (e) => {
  const li = e.target.closest('li[data-id]');
  if (!li) return;
  const pl = schem.list.find((p) => p.id === li.dataset.id);
  if (!pl) return;
  if (e.target.closest('[data-del]')) { unloadSchem(pl); return; }
  if (e.target.closest('[data-vis]')) { patchSchem(pl, { visible: pl.visible === false }); return; }
  selectSchem(pl.id);
  gotoSchem(pl);
});

function gotoSchem(pl) {
  const cx = pl.x + pl.size[0] / 2, cz = pl.z + pl.size[2] / 2;
  if (mode3d && view3d) view3d.flyTo(cx, pl.y + pl.size[1], cz + Math.max(pl.size[0], pl.size[2]) * 0.6);
  else { setFollow(false); map.setView(toLatLng(cx, cz), Math.max(map.getZoom(), 0)); }
}

async function unloadSchem(pl) {
  if (!confirm(`„${pl.name}“ entladen? Die Datei bleibt erhalten.`)) return;
  try {
    await jsonPost('/api/schematics/delete', { id: pl.id });
    schem.list = schem.list.filter((p) => p !== pl);
    if (schem.sel === pl.id) selectSchem(null);
    schemChanged();
  } catch (e) { toast('Entladen fehlgeschlagen: ' + e.message); }
}

/* Aenderung sofort anzeigen, gebuendelt speichern. */
function patchSchem(pl, patch) {
  const before = { rot: pl.rot, mirror: pl.mirror };
  Object.assign(pl, patch);
  if ('rot' in patch && ((patch.rot - before.rot) / 90) % 2) pl.size = [pl.size[2], pl.size[1], pl.size[0]];
  if ('rot' in patch || 'mirror' in patch) { schem.mats = null; schem.check = null; if (view3d) view3d.setVerify(pl.id, null); }
  if (['x', 'y', 'z'].some((k) => k in patch) && schem.check) { schem.check = null; if (view3d) view3d.setVerify(pl.id, null); }
  schemChanged();
  pl._dirty = { ...(pl._dirty || {}), ...patch };
  clearTimeout(schem.saveTimer);
  schem.saveTimer = setTimeout(() => flushSchems(), 250);
}

async function flushSchems() {
  for (const pl of schem.list) {
    if (!pl._dirty) continue;
    const body = { id: pl.id, ...pl._dirty };
    delete pl._dirty;
    try {
      const r = await jsonPost('/api/schematics/update', body);
      pl.size = r.size; pl.error = r.error;
    } catch (e) { toast('Speichern fehlgeschlagen: ' + e.message); }
  }
  schemChanged();
  if (schem.sel && !$('#schem-panel').hidden) loadPaneData();
}

/* Kurzinfo unter den grossen Reitern des Schematic-Fensters */
function spBadge(tab, text, cls) {
  const el = $('#spt-' + tab);
  if (!el) return;
  el.textContent = text;
  el.className = cls || '';
}
const SPT_DEFAULT = { place: 'Position', mats: 'Liste', check: 'Fortschritt', build: 'Autobau' };
function selectSchem(id, from3d) {
  if (schem.sel !== id) { schem.mats = null; schem.check = null; for (const k in SPT_DEFAULT) spBadge(k, SPT_DEFAULT[k]); }
  if (schem.sel && schem.sel !== id && view3d) view3d.setVerify(schem.sel, null);
  schem.sel = id;
  if (view3d && !from3d) view3d.select(id ? { type: 'schematic', id } : null, true);
  $('#schem-panel').hidden = !id;
  if (id && sel.on) setSelect(false);
  schemChanged();
  if (id) loadPaneData();
}

// --- 2D: Rechtecke, anklicken und ziehen

function drawSchems2D() {
  const here = new Set();
  for (const pl of placedHere()) {
    here.add(pl.id);
    const b = L.latLngBounds(toLatLng(pl.x, pl.z), toLatLng(pl.x + pl.size[0], pl.z + pl.size[2]));
    const isSel = pl.id === schem.sel;
    const style = { color: isSel ? '#ffd23f' : '#7fd1ff', weight: isSel ? 3 : 2, fillOpacity: pl.visible === false ? 0.03 : 0.12, dashArray: pl.visible === false ? '4 4' : null };
    let r = schem.layers.get(pl.id);
    if (!r) {
      r = L.rectangle(b, style).addTo(map);
      r.bindTooltip('', { permanent: true, direction: 'center', className: 'schem-label' });
      r.on('mousedown', (e) => startDrag(e, pl.id));
      r.on('click', (e) => { L.DomEvent.stop(e); if (!schem.drag || !schem.drag.moved) selectSchem(pl.id); });
      schem.layers.set(pl.id, r);
    } else {
      r.setBounds(b);
      r.setStyle(style);
    }
    r.setTooltipContent(esc(pl.name));
  }
  for (const [id, r] of schem.layers) if (!here.has(id)) { map.removeLayer(r); schem.layers.delete(id); }
}

function startDrag(e, id) {
  if (sel.on || e.originalEvent.button !== 0) return;
  const pl = schem.list.find((p) => p.id === id);
  if (!pl) return;
  L.DomEvent.stop(e);
  map.dragging.disable();
  const at = fromLatLng(e.latlng);
  schem.drag = { id, dx: at.x - pl.x, dz: at.z - pl.z, moved: false };
}
map.on('mousemove', (e) => {
  const d = schem.drag;
  if (!d) return;
  const pl = schem.list.find((p) => p.id === d.id);
  const at = fromLatLng(e.latlng);
  const x = at.x - d.dx, z = at.z - d.dz;
  if (pl && (x !== pl.x || z !== pl.z)) {
    d.moved = true;
    pl.x = x; pl.z = z;
    drawSchems2D();
  }
});
document.addEventListener('mouseup', () => {
  const d = schem.drag;
  if (!d) return;
  if (!sel.on) map.dragging.enable();
  const pl = schem.list.find((p) => p.id === d.id);
  if (d.moved && pl) patchSchem(pl, { x: pl.x, z: pl.z });
  if (!d.moved) selectSchem(d.id);
  setTimeout(() => { schem.drag = null; }, 0);
});

// --- Editor

function renderEditor() {
  const pl = selPl();
  if (!pl) return;
  if (document.activeElement !== $('#sp-name')) $('#sp-name').value = pl.name;
  for (const k of ['x', 'y', 'z']) if (document.activeElement !== $('#sp-' + k)) $('#sp-' + k).value = pl[k];
  $('#sp-sub').textContent = `${pl.size.join(' × ')} · ${pl.file.split('/').slice(1).join('/')} · ${pl.rot}°`;
  $('#sp-error').hidden = !pl.error;
  $('#sp-error').textContent = pl.error || '';
  $('#sp-mirror').classList.toggle('on', !!pl.mirror);
  $('#sp-vis').classList.toggle('on', pl.visible !== false);
  $('#sp-vis').textContent = pl.visible === false ? 'Ausgeblendet' : 'Sichtbar';
  $('#sp-op').value = Math.round((pl.opacity ?? 1) * 100);
  $('#sp-ingame').classList.toggle('on', !!pl.ingame);
  const st = $('#sp-ingame-st');
  st.textContent = ingameText(pl);
  st.className = 'ingame-st' + (pl.ingameState === 'ok' ? ' ok' : pl.ingame && pl.ingameState && pl.ingameState !== 'wartet' ? ' bad' : '');
}

function ingameText(pl) {
  if (!pl.ingame) return 'nur im Viewer';
  switch (pl.ingameState) {
    case 'ok': return 'in Litematica ✓';
    case 'offline': return 'wartet aufs Spiel (Mod 0.3 + Litematica)';
    case 'kein Litematica': return 'Litematica nicht gefunden';
    case 'andere Dimension': return 'erscheint in ihrer Dimension';
    case 'wartet': case undefined: case null: return 'wird übertragen …';
    default: return pl.ingameState;
  }
}

/* Status der Litematica-Bruecke nachladen, solange etwas "im Spiel" ist. */
setInterval(async () => {
  if (!state.server || !schem.list.some((p) => p.ingame)) return;
  try {
    const fresh = await api('/api/schematics?' + new URLSearchParams({ srv: state.server, dim: state.dim }));
    let changed = false;
    for (const f of fresh) {
      const pl = schem.list.find((p) => p.id === f.id);
      if (pl && pl.ingameState !== f.ingameState) { pl.ingameState = f.ingameState; changed = true; }
    }
    if (changed) { renderPlaced(); renderEditor(); }
  } catch (_) { /* Viewer kurz weg */ }
}, 2000);

$('#sp-close').addEventListener('click', () => selectSchem(null));
$('#sp-name').addEventListener('change', (e) => { const pl = selPl(); if (pl) patchSchem(pl, { name: e.target.value.trim() || pl.name }); });
for (const k of ['x', 'y', 'z']) {
  $('#sp-' + k).addEventListener('change', (e) => { const pl = selPl(); if (pl && e.target.value !== '') patchSchem(pl, { [k]: Math.round(Number(e.target.value)) }); });
}
$('#schem-panel').addEventListener('click', (e) => {
  const b = e.target.closest('[data-nudge]');
  const pl = selPl();
  if (!b || !pl) return;
  const [k, d] = b.dataset.nudge.split(',');
  patchSchem(pl, { [k]: pl[k] + Number(d) * (e.shiftKey ? 10 : 1) });
});
$('#sp-rot').addEventListener('click', () => { const pl = selPl(); if (pl) patchSchem(pl, { rot: (pl.rot + 90) % 360 }); });
$('#sp-mirror').addEventListener('click', () => { const pl = selPl(); if (pl) patchSchem(pl, { mirror: !pl.mirror }); });
$('#sp-vis').addEventListener('click', () => { const pl = selPl(); if (pl) patchSchem(pl, { visible: pl.visible === false }); });
$('#sp-ingame').addEventListener('click', () => { const pl = selPl(); if (pl) patchSchem(pl, { ingame: !pl.ingame, ingameState: pl.ingame ? null : 'wartet' }); });
$('#sp-op').addEventListener('input', (e) => { const pl = selPl(); if (pl) patchSchem(pl, { opacity: Number(e.target.value) / 100 }); });
$('#sp-goto').addEventListener('click', () => { const pl = selPl(); if (pl) gotoSchem(pl); });
$('#sp-del').addEventListener('click', () => { const pl = selPl(); if (pl) unloadSchem(pl); });
$('#sp-here').addEventListener('click', async () => {
  const pl = selPl();
  if (!pl) return;
  const p = state.player;
  let t;
  if (!mode3d && p && p.active && p.dim === state.dim) t = { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
  else t = await placeTarget();
  patchSchem(pl, { x: t.x - Math.floor(pl.size[0] / 2), y: t.y, z: t.z - Math.floor(pl.size[2] / 2) });
});

/* Tastatur, solange eine Schematic gewaehlt ist. Liefert true, wenn verbraucht. */
function schemKeys(e) {
  const pl = selPl();
  if (!pl || $('#schem-panel').hidden) return false;
  const n = e.shiftKey ? 10 : 1;
  const moves = { ArrowLeft: ['x', -n], ArrowRight: ['x', n], ArrowUp: ['z', -n], ArrowDown: ['z', n], PageUp: ['y', n], PageDown: ['y', -n] };
  if (moves[e.key]) { const [k, d] = moves[e.key]; patchSchem(pl, { [k]: pl[k] + d }); return true; }
  if (e.key === 'r' || e.key === 'R') { patchSchem(pl, { rot: (pl.rot + 90) % 360 }); return true; }
  if (e.key === 'm' || e.key === 'M') { patchSchem(pl, { mirror: !pl.mirror }); return true; }
  if (e.key === 'Delete') { unloadSchem(pl); return true; }
  if (e.key === 'Escape' && document.pointerLockElement === null) { selectSchem(null); return true; }
  return false;
}

// --- Materialliste und Abgleich

$$('.sp-tabs button').forEach((b) => b.addEventListener('click', () => {
  $$('.sp-tabs button').forEach((x) => x.classList.toggle('active', x === b));
  $$('.sp-pane').forEach((p) => p.classList.toggle('active', p.dataset.sppane === b.dataset.sptab));
  loadPaneData();
}));
const activeSpTab = () => ($('.sp-tabs button.active') || { dataset: { sptab: 'mats' } }).dataset.sptab;

async function loadPaneData() {
  const pl = selPl();
  if (!pl || pl.error) { $('#mat-list').innerHTML = ''; $('#mat-sum').textContent = ''; $('#chk-out').innerHTML = ''; return; }
  if (activeSpTab() === 'mats' && !schem.mats) {
    const id = pl.id;
    $('#mat-sum').textContent = 'Wird gezählt …';
    try {
      const m = await api(`/api/schematics/${encodeURIComponent(id)}/materials`);
      if (schem.sel !== id) return;
      schem.mats = m;
    } catch (e) { $('#mat-sum').textContent = 'Fehler: ' + e.message; return; }
  }
  renderMats();
  renderCheck();
  // Abgleich im Hintergrund, damit "Fehlt" schon gebaute Bloecke nicht mitzaehlt
  if (activeSpTab() === 'mats' && schem.mats && !schem.check && !schem.checking) {
    schem.checking = pl.id;
    runCheck(true).finally(() => { schem.checking = null; });
  }
}

const STACK16 = /(_sign|_banner|ender_pearl|snowball|egg|honey_bottle|armor_stand|bucket)$/;
const STACK1 = /(shulker_box|_bed|_bucket|minecart|_boat|_raft|cake|potion)$/;
const stackOf = (item) => (STACK1.test(item) ? 1 : STACK16.test(item) ? 16 : 64);
function stacksText(item, n) {
  const s = stackOf(item);
  const st = Math.floor(n / s), rest = n % s;
  const parts = [];
  if (st) parts.push(`${st}×${s}`);
  if (rest) parts.push(`${rest}`);
  const sh = n / (27 * s);
  return parts.join(' + ') + (sh >= 0.5 ? ` · ${sh.toFixed(1).replace('.', ',')} Shulker` : '');
}
const itemName = (it) => it.replace('minecraft:', '').replace(/_/g, ' ');

/* Fehlende Items: was noch verbaut werden muss, minus Inventar (auch Shulker darin), minus gemerkte Kisten */
function missingList() {
  const m = schem.mats;
  if (!m) return null;
  const built = !!(schem.check && schem.check.missingItems);
  const base = built ? schem.check.missingItems : m.items;
  const inv = invCounts();
  const rows = [];
  for (const [it, n] of base) {
    const id = fullId(it);
    const have = Math.min(n, inv.get(id) || 0);
    const box = Math.min(n - have, inChests(id).total);
    const miss = n - have - box;
    if (have + box < n || box) rows.push({ it, n, have, box, miss });
  }
  rows.sort((a, b) => b.miss - a.miss || b.box - a.box);
  return { built, rows, missing: rows.filter((r) => r.miss > 0), fetch: rows.filter((r) => !r.miss && r.box) };
}
function renderMissing() {
  const el = $('#mat-miss');
  const L = missingList();
  if (!L) { el.innerHTML = ''; return; }
  const src = L.built ? 'noch nicht gebaut' : 'ganze Schematic (Abgleich läuft …)';
  const minus = [myInv ? 'Inventar' : null, chests.list.length ? `${chests.list.length} gemerkte Kisten` : null].filter(Boolean).join(' und ') || 'nichts (Inventar und Kisten unbekannt)';
  const total = L.missing.reduce((a, r) => a + r.miss, 0);
  const sh = L.missing.reduce((a, r) => a + r.miss / (27 * stackOf(r.it)), 0);
  if (!L.missing.length) {
    spBadge('mats', 'alles da', 'good');
    el.innerHTML = `<div class="card-title">Fehlende Items</div><p class="ok-hint">Nichts fehlt: alles ist im Inventar oder in deinen Kisten${L.fetch.length ? `, ${L.fetch.length} ${L.fetch.length === 1 ? 'Sorte muss' : 'Sorten müssen'} noch aus Kisten geholt werden (Tab „Bauen“ → Autobau)` : ''}.</p>
      <p class="hint">Gezählt: ${src}, abgezogen: ${minus}.</p>`;
    return;
  }
  spBadge('mats', `${L.missing.length} fehlen`, 'warn');
  el.innerHTML = `<div class="card-title">Fehlende Items <span class="muted">${L.missing.length} Sorten · ${fmt(total)} Items · etwa ${Math.max(1, Math.ceil(sh))} Shulker</span>
      <button id="miss-copy" title="Liste als Text kopieren">Kopieren</button></div>
    <p class="hint">Gezählt: ${src}, abgezogen: ${minus}. Diese Items hast du nirgends, sie müssen gefarmt oder gekauft werden.${L.fetch.length ? ` ${L.fetch.length} weitere Sorten liegen komplett in Kisten.` : ''}</p>
    <table class="mats miss"><tbody>${L.missing.map((r) => `<tr><td>${itemIcon(r.it, 20)} ${esc(itemName(r.it))}</td>
      <td class="n miss-n">${fmt(r.miss)}</td><td class="s">${r.miss >= stackOf(r.it) ? stacksText(r.it, r.miss) : ""}</td>
      <td class="s">${r.have || r.box ? `<span class="muted" title="von ${fmt(r.n)} benötigt">${[r.have ? fmt(r.have) + ' dabei' : '', r.box ? fmt(r.box) + ' in Kisten' : ''].filter(Boolean).join(', ')}</span>` : ''}</td></tr>`).join('')}</tbody></table>`;
}
$('#mat-miss').addEventListener('click', (e) => {
  if (!e.target.closest('#miss-copy')) return;
  const L = missingList();
  if (!L) return;
  const txt = L.missing.map((r) => `${itemName(r.it)}: ${r.miss} (${stacksText(r.it, r.miss)})`).join('\n');
  navigator.clipboard.writeText(txt).then(() => toast('Liste kopiert'), () => toast('Kopieren nicht möglich'));
});

function renderMats() {
  const m = schem.mats;
  if (!m) return;
  renderMissing();
  const f = $('#mat-filter').value.trim().toLowerCase();
  const done = new Set(prefs['done:' + schem.sel] || []);
  const rows = m.items.filter(([it]) => !f || it.includes(f.replace(/ /g, '_')));
  const total = m.items.reduce((a, [, n]) => a + n, 0);
  const shulkers = m.items.reduce((a, [it, n]) => a + n / (27 * stackOf(it)), 0);
  $('#mat-sum').textContent = `${fmt(total)} Items, ${m.items.length} Sorten, etwa ${Math.ceil(shulkers)} Shulker voll`;
  $('#mat-list').innerHTML = rows.map(([it, n]) => `
    <tr class="${done.has(it) ? 'done' : ''}" data-item="${esc(it)}"><td><input type="checkbox" ${done.has(it) ? 'checked' : ''} title="Abhaken"></td>
    <td>${itemIcon(it, 20)} ${esc(itemName(it))}</td><td class="n">${fmt(n)}</td><td class="s">${stacksText(it, n)}</td>${chestCell(it)}</tr>`).join('');
}
/* Zelle "in Kisten" fuer Material- und Packlisten */
function chestCell(it) {
  if (!chests.list.length) return '';
  const k = inChests(it.includes(':') ? it : 'minecraft:' + it);
  if (!k.total) return '<td class="s muted">–</td>';
  const tip = k.where.slice(0, 4).map(([n, c]) => `${n} in ${chestWhere(c)}`).join('\n');
  return `<td class="s chest-have" title="${esc(tip)}">${fmt(k.total)} in Kisten</td>`;
}
$('#mat-filter').addEventListener('input', renderMats);
$('#mat-list').addEventListener('change', (e) => {
  const tr = e.target.closest('tr[data-item]');
  if (!tr) return;
  const key = 'done:' + schem.sel;
  const done = new Set(prefs[key] || []);
  if (e.target.checked) done.add(tr.dataset.item); else done.delete(tr.dataset.item);
  savePref(key, Array.from(done));
  renderMats();
});
$('#mat-csv').addEventListener('click', () => {
  const m = schem.mats, pl = selPl();
  if (!m || !pl) return;
  const csv = 'Item;Anzahl;Stacks\n' + m.items.map(([it, n]) => `${itemName(it)};${n};${stacksText(it, n)}`).join('\n');
  const url = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv' }));
  const a = Object.assign(document.createElement('a'), { href: url, download: `${pl.name}-materialien.csv` });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
});

async function runCheck(quiet) {
  const pl = selPl();
  if (!pl) return;
  const btn = $('#chk-run');
  if (!quiet) { btn.disabled = true; btn.textContent = 'Gleiche ab …'; }
  try {
    if (pl._dirty) await flushSchems();
    const c = await api(`/api/schematics/${encodeURIComponent(pl.id)}/verify`);
    if (selPl() !== pl) return;
    schem.check = c;
    pl._pct = Math.floor((c.stats.ok / Math.max(1, c.stats.total)) * 100);
    if (view3d && $('#chk-show').checked) view3d.setVerify(pl.id, c.points);
    renderCheck();
    renderMats();
    renderPlaced();
  } catch (e) { if (!quiet) $('#chk-out').innerHTML = `<p class="err">${esc(e.message)}</p>`; }
  finally { if (!quiet) { btn.disabled = false; btn.textContent = 'Jetzt abgleichen'; } }
}
$('#chk-run').addEventListener('click', () => runCheck(false));
/* Live: waehrend gebaut wird alle 3 s neu abgleichen (die Mod schreibt Aenderungen nach ~1,5 s) */
let chkBusy = false;
setInterval(async () => {
  if (!$('#chk-live').checked || !selPl() || chkBusy || document.hidden) return;
  chkBusy = true;
  try { await runCheck(true); } finally { chkBusy = false; }
}, 3000);
$('#chk-live').addEventListener('change', (e) => { if (e.target.checked) runCheck(true); });
$('#chk-out').addEventListener('click', (e) => {
  const tr = e.target.closest('tr[data-goto]');
  if (!tr) return;
  const [x, y, z] = tr.dataset.goto.split(',').map(Number);
  if (mode3d && view3d) view3d.flyTo(x + 0.5, y + 0.5, z + 0.5);
  else map.setView(toLatLng(x + 0.5, z + 0.5), Math.max(map.getZoom(), 3));
});
$('#chk-show').addEventListener('change', (e) => {
  if (view3d && schem.check) view3d.setVerify(schem.sel, e.target.checked ? schem.check.points : null);
});

/* Fehlende Items auf Shulker verteilen: volle Stacks, groesste Mengen zuerst, 27 Plaetze pro Kiste. */
function packShulkers(items) {
  const out = [];
  let cur = [], free = 27;
  for (const [it, n] of items.slice().sort((a, b) => b[1] - a[1])) {
    let left = Math.ceil(n / stackOf(it)), rest = n;
    while (left > 0) {
      if (!free) { out.push(cur); cur = []; free = 27; }
      const take = Math.min(left, free);
      const cnt = Math.min(rest, take * stackOf(it));
      cur.push([it, cnt]);
      left -= take; free -= take; rest -= cnt;
    }
  }
  if (cur.length) out.push(cur);
  return out;
}
function stacksShort(item, n) {
  const s = stackOf(item), st = Math.floor(n / s), r = n % s;
  return st ? (r ? `${st}×${s}+${r}` : `${st}×${s}`) : `${r}`;
}

function renderCheck() {
  const c = schem.check;
  if (!c) { $('#chk-out').innerHTML = ''; return; }
  const s = c.stats;
  const tot = Math.max(1, s.total);
  const pct = (n) => (n / tot) * 100;
  const keys = [['ok', 'richtig'], ['rotated', 'falsch gedreht'], ['wrong', 'falscher Block'], ['missing', 'fehlt'], ['unknown', 'nicht aufgezeichnet']];
  const colors = { ok: '#22a06b', rotated: '#ffe066', wrong: '#e5484d', missing: '#3e9bf5', unknown: '#555b66' };
  spBadge('check', `${Math.floor(pct(s.ok))} % fertig`, s.ok >= s.total ? 'good' : '');
  $('#chk-out').innerHTML = `
    <div class="bar-chk">${keys.map(([k]) => `<span style="width:${pct(s[k])}%;background:${colors[k]}"></span>`).join('')}</div>
    <div class="chk-stats">
      <b>${Math.floor(pct(s.ok))} % fertig</b><span class="mono">${fmt(s.ok)} / ${fmt(s.total)}</span>
      ${keys.slice(1).map(([k, l]) => `<span class="k-${k}">${l}</span><span class="mono">${fmt(s[k])}</span>`).join('')}
      <span class="k-extra">muss weg</span><span class="mono">${fmt(s.extra)}</span>
    </div>
    ${c.todo && c.todo.length ? `<h4>Als Nächstes bauen <span class="muted">(${fmt(c.todoTotal)} offen, unten zuerst${state.player && state.player.active ? ', dann nächste zu dir' : ''})</span></h4>
      <table class="mats todo"><tbody>${c.todo.slice(0, 80).map((t) => `<tr data-goto="${t[0]},${t[1]},${t[2]}" title="Hinfliegen"><td class="mono">${t[0]} ${t[1]} ${t[2]}</td>
      <td>${esc(itemName(t[3]))}</td><td class="k-${t[4]}">${t[4] === 'missing' ? 'fehlt' : t[4] === 'wrong' ? 'statt ' + esc(itemName(t[5] || '')) : 'drehen'}</td></tr>`).join('')}</tbody></table>` : ''}
    ${(() => {
      const need = stillNeeded(c.missingItems);
      if (!c.missingItems.length) return '';
      const tour = planTour(need);
      const carried = invCounts();
      return `<h4>Noch zu besorgen <span class="muted">${myInv ? '(abzüglich deinem Inventar)' : '(Inventar unbekannt: Mod 0.3.2)'}</span></h4>
      <table class="mats"><tbody>${c.missingItems.map(([it, n]) => { const full = fullId(it), have = carried.get(full) || 0;
        return `<tr class="${have >= n ? 'done' : ''}"><td>${itemIcon(it, 20)} ${esc(itemName(it))}</td><td class="n">${fmt(n)}</td>
        <td class="s ${have ? 'inv-have' : 'muted'}">${have ? fmt(Math.min(have, n)) + ' dabei' : '–'}</td>${chestCell(it)}</tr>`; }).join('')}</tbody></table>
      ${need.length ? `<h4>Packliste <span class="muted">(${packShulkers(need).length} Shulker)</span></h4>
      <ol class="pack">${packShulkers(need).map((sh) => `<li>${sh.map(([it, n]) => `${itemIcon(it, 16)} ${esc(itemName(it))} <span class="mono">${stacksShort(it, n)}</span>`).join(' · ')}</li>`).join('')}</ol>` : '<p class="hint ok-hint">Du hast alles dabei. Los geht\'s!</p>'}
      ${tour.stops.length ? `<h4>Holtour <span class="muted">(${tour.stops.length} Kisten, nächste zuerst)</span> <button data-tour-hl title="Alle Kisten der Tour im Spiel mit Lichtsäulen markieren">Im Spiel zeigen</button></h4>
      <ol class="tour">${tour.stops.map((st, i) => `<li data-tour="${i}"><div><b>${esc(st.chest.title || chestWhere(st.chest))}</b>
        <span class="muted">${st.dist != null ? Math.round(st.dist) + ' m' : ''}</span></div>
        <div class="take">${st.take.map(([it, n]) => `${itemIcon(it, 16)} ${esc(prettyItem(it))} <span class="mono">${stacksShort(chestKey(it), n)}</span>`).join(' · ')}</div>
        <div class="btns"><button data-tour-fetch title="Baritone läuft hin und nimmt genau diese Items">Holen</button><button data-tour-open>Öffnen</button>${st.chest.dim === state.dim ? '<button data-tour-go title="Baritone läuft hin">Hinlaufen</button>' : ''}</div></li>`).join('')}</ol>` : ''}
      ${tour.missing.length ? `<p class="hint">In keiner gemerkten Kiste: ${tour.missing.map(([it, n]) => `${esc(prettyItem(it))} (${fmt(n)})`).join(', ')}</p>` : ''}`;
    })()}
    ${s.unknown ? '<p class="hint">Teile sind nicht aufgezeichnet: einmal mit der Mod hinfliegen, dann neu abgleichen.</p>' : ''}`;
}



// --- Inventar, Holtour, Baritone-Knoepfe fuer den Bau

var myInv = null;    // { items, t } vom Spieler (Mod 0.3.2)
const fullId = (it) => (it.includes(':') ? it : 'minecraft:' + it);
function invCounts() {
  const m = new Map();
  for (const it of (myInv && myInv.items) || []) {
    m.set(it.id, (m.get(it.id) || 0) + it.count);
    for (const x of it.contents || []) m.set(x.id, (m.get(x.id) || 0) + x.count);
  }
  return m;
}
/* Fehlende Items minus das, was du schon dabei hast */
function stillNeeded(items) {
  const have = invCounts();
  const out = [];
  for (const [it, n] of items) {
    const rest = n - (have.get(fullId(it)) || 0);
    if (rest > 0) out.push([it, rest]);
  }
  return out;
}
/* Holtour: wenige Kisten, die moeglichst viel abdecken (gierig), dann naechste zuerst */
function planTour(need) {
  const rest = new Map(need.map(([it, n]) => [fullId(it), n]));
  const pool = chests.list.map((c) => {
    const m = new Map();
    for (const [id, n] of chestItems(c)) m.set(id, (m.get(id) || 0) + n);
    return { chest: c, have: m };
  });
  const picked = [];
  while (pool.length) {
    let best = null, bestGain = 0;
    for (const p of pool) {
      let g = 0;
      for (const [id, n] of rest) g += Math.min(n, p.have.get(id) || 0);
      if (g > bestGain) { best = p; bestGain = g; }
    }
    if (!best) break;
    pool.splice(pool.indexOf(best), 1);
    const take = [];
    for (const [id, n] of rest) {
      const k = Math.min(n, best.have.get(id) || 0);
      if (k) { take.push([id, k]); rest.set(id, n - k); }
    }
    for (const [id, n] of Array.from(rest)) if (n <= 0) rest.delete(id);
    picked.push({ chest: best.chest, take });
  }
  // Reihenfolge: Enderkiste zuerst (ueberall erreichbar), dann jeweils die naechste
  const p = state.player && state.player.active && state.player.dim === state.dim ? state.player : null;
  let cur = p ? [p.x, p.z] : null;
  const stops = [];
  const left = picked.slice();
  const ender = left.findIndex((s) => s.chest.dim === 'ender');
  if (ender >= 0) stops.push(left.splice(ender, 1)[0]);
  while (left.length) {
    let bi = 0, bd = Infinity;
    left.forEach((s, i) => {
      const d = cur && s.chest.dim === state.dim ? Math.hypot(s.chest.x - cur[0], s.chest.z - cur[1]) : 1e9 + i;
      if (d < bd) { bd = d; bi = i; }
    });
    const s = left.splice(bi, 1)[0];
    s.dist = bd < 1e9 ? bd : null;
    if (s.chest.dim === state.dim) cur = [s.chest.x, s.chest.z];
    stops.push(s);
  }
  return { stops, missing: Array.from(rest.entries()) };
}

$('#chk-out').addEventListener('click', async (e) => {
  const li = e.target.closest('li[data-tour]');
  if (li && schem.check) {
    const tour = planTour(stillNeeded(schem.check.missingItems));
    const st = tour.stops[Number(li.dataset.tour)];
    if (!st) return;
    if (e.target.closest('[data-tour-open]')) openInv(st.chest, st.take[0] && st.take[0][0]);
    if (e.target.closest('[data-tour-go]')) baritone({ kind: 'goto', x: st.chest.x, y: st.chest.y + 1, z: st.chest.z });
    if (e.target.closest('[data-tour-fetch]')) baritone({ kind: 'fetch', x: st.chest.x, y: st.chest.y, z: st.chest.z, items: st.take.map(([id, n]) => ({ id: fullId(id), count: n })) });
  }
  if (e.target.closest('[data-tour-hl]') && schem.check) highlightChests(planTour(stillNeeded(schem.check.missingItems)).stops.map((s) => s.chest));
});

async function loadInventory() {
  try {
    const inv2 = await api('/api/inventory');
    const changed = JSON.stringify(inv2 && inv2.items) !== JSON.stringify(myInv && myInv.items);
    myInv = inv2;
    if (changed && schem.check) renderCheck();
    if (changed && schem.mats) renderMissing();
  } catch (_) { /* egal */ }
}
setInterval(() => { if (selPl() && !document.hidden) loadInventory(); }, 3000);
loadInventory();

// ------------------------------------------------------------------ Kisten-Inventar

var chests = { list: [], layer: L.layerGroup().addTo(map), mode: prefs.ctMode || 'items', selItem: null };
var itemsMeta = null;   // Item-Bilder: { size, cols, index }
const chestKey = (it) => it.replace('minecraft:', '');
const prettyItem = (it) => chestKey(it).replace(/_/g, ' ');

(async () => {
  try {
    itemsMeta = await api('/api/items.json');
    document.documentElement.style.setProperty('--items-url', `url(/api/items.png?v=${Object.keys(itemsMeta.index).length})`);
    renderChests();
  } catch (_) { itemsMeta = null; }
})();

/* Item-Bild als HTML (32 px, oder klein mit size) */
function itemIcon(id, size = 32) {
  const i = itemsMeta && itemsMeta.index[id.includes(':') ? id : 'minecraft:' + id];
  if (i == null) return `<span class="ico none" style="width:${size}px;height:${size}px"></span>`;
  const k = size / itemsMeta.size;
  const x = (i % itemsMeta.cols) * size, y = Math.floor(i / itemsMeta.cols) * size;
  return `<span class="ico" style="width:${size}px;height:${size}px;background-position:-${x}px -${y}px;background-size:${itemsMeta.cols * size}px ${itemsMeta.rows * size}px"></span>`;
}

async function loadContainers() {
  if (!state.server) return;
  try { chests.list = await api('/api/containers?' + new URLSearchParams({ srv: state.server })); } catch (_) { chests.list = []; }
  await loadWorldChests();
  try { chests.lids = await api('/api/containers/lid?' + new URLSearchParams({ srv: state.server, dim: state.dim })); } catch (_) { chests.lids = []; }
  renderChests();
  drawChests();
  if (schem.mats) renderMats();
  if (inv.stack.length) refreshInv();
}

/* Kisten aus der Aufnahme (auch nie geoeffnete), rund um dich bzw. deine Kisten */
chests.world = [];
chests.worldAt = 0;
chests.lids = [];   // Kisten, die jemand anderes in Sichtweite geoeffnet hat (Deckel-Event)
async function loadWorldChests() {
  if (Date.now() - chests.worldAt < 25000) return;
  chests.worldAt = Date.now();
  const p = state.player;
  let x, z;
  if (p && p.active && p.dim === state.dim && (!p.server || p.server === state.server)) { x = p.x; z = p.z; }
  else {
    const mine = chests.list.filter((c) => c.dim === state.dim);
    if (mine.length) { x = mine.reduce((a, c) => a + c.x, 0) / mine.length; z = mine.reduce((a, c) => a + c.z, 0) / mine.length; }
    else { const c = fromLatLng(map.getCenter()); x = c.x; z = c.z; }
  }
  try { chests.world = await api('/api/containers/world?' + new URLSearchParams({ srv: state.server, dim: state.dim, x: Math.round(x), z: Math.round(z), r: 10 })); }
  catch (_) { chests.world = []; }
}
function unopenedChests() {
  const known = new Set(chests.list.filter((c) => c.dim === state.dim).map((c) => `${c.x},${c.y},${c.z}`));
  return chests.world.filter((w) => !known.has(`${w.x},${w.y},${w.z}`) && !(w.other && known.has(w.other.join(','))));
}
function renderKnown() {
  const un = unopenedChests();
  const opened = chests.list.length;
  const lids = chests.lids.filter((l) => l.opens > 0).length;
  $('#ct-known').innerHTML = `<b>${opened}</b> Kisten mit Inhalt gemerkt` + (lids ? ` · <b style="color:#c77dff">${lids}</b> von anderen benutzt` : '') + (un.length ? ` · <b>${un.length}</b> in der Nähe noch nie geöffnet
    <button data-hl-unopened title="Die nächsten 64 davon im Spiel mit Lichtsäulen markieren">Im Spiel zeigen</button>` : chests.world.length ? ' · alle Kisten in der Nähe sind bekannt' : '');
}
$('#ct-known').addEventListener('click', (e) => {
  if (!e.target.closest('[data-hl-unopened]')) return;
  const p = state.player;
  const un = unopenedChests().map((w) => ({ ...w, dim: state.dim }));
  if (p) un.sort((a, b) => Math.hypot(a.x - p.x, a.z - p.z) - Math.hypot(b.x - p.x, b.z - p.z));
  highlightChests(un.slice(0, 64));
});

/* Alle Items einer Kiste, Inhalte von Shulkern mitgezaehlt: [[item, anzahl, in-welchem-shulker]] */
function chestItems(c) {
  const out = [];
  for (const it of c.items) {
    out.push([it.id, it.count, null]);
    for (const inner of it.contents || []) out.push([inner.id, inner.count, it.name || prettyItem(it.id)]);
  }
  return out;
}
function chestWhere(c) {
  if (c.dim === 'ender') return 'Enderkiste';
  const what = prettyItem(c.block || 'kiste');
  return `${what} bei ${c.x} ${c.y} ${c.z}${c.dim !== state.dim ? ' (' + c.dim.replace('the_', '') + ')' : ''}`;
}
function agoText(ms) {
  const s = (Date.now() - ms) / 1000;
  if (s < 90) return 'gerade eben';
  if (s < 5400) return `vor ${Math.round(s / 60)} min`;
  if (s < 129600) return `vor ${Math.round(s / 3600)} h`;
  return `vor ${Math.round(s / 86400)} Tagen`;
}
/* Wie viele von item liegen in Kisten, und wo am meisten */
function inChests(item) {
  let total = 0;
  const where = [];
  for (const c of chests.list) {
    let n = 0;
    for (const [id, k] of chestItems(c)) if (id === item) n += k;
    if (n) { total += n; where.push([n, c]); }
  }
  where.sort((a, b) => b[0] - a[0]);
  return { total, where };
}
/* Gesamtliste aller Items ueber alle Kisten */
function allItems() {
  const m = new Map();
  for (const c of chests.list) for (const [id, n] of chestItems(c)) m.set(id, (m.get(id) || 0) + n);
  return Array.from(m.entries()).sort((a, b) => b[1] - a[1]);
}

function renderChests() {
  renderKnown();
  const f = $('#ct-filter').value.trim().toLowerCase().replace(/ /g, '_');
  $$('.ct-mode button').forEach((b) => b.classList.toggle('on', b.dataset.ctmode === chests.mode));
  if (!chests.list.length) {
    $('#ct-sum').textContent = 'Noch keine Kiste gemerkt. Öffne im Spiel eine Kiste (Mod 0.3.2).';
    $('#ct-grid').innerHTML = $('#ct-where').innerHTML = $('#ct-list').innerHTML = '';
    return;
  }
  if (chests.mode === 'items') {
    $('#ct-list').innerHTML = '';
    const all = allItems().filter(([id]) => !f || chestKey(id).includes(f));
    const total = all.reduce((a, [, n]) => a + n, 0);
    $('#ct-sum').textContent = `${fmt(total)} Items, ${all.length} Sorten in ${chests.list.length} Kisten`;
    $('#ct-grid').innerHTML = all.map(([id, n]) => `<div class="cell ${id === chests.selItem ? 'sel' : ''}" data-item="${esc(id)}" title="${esc(prettyItem(id))}: ${fmt(n)}">${itemIcon(id)}<span class="n">${n >= 10000 ? Math.round(n / 1000) + 'k' : n}</span></div>`).join('');
    renderWhere();
    return;
  }
  $('#ct-grid').innerHTML = $('#ct-where').innerHTML = '';
  const rows = chests.list.filter((c) => !f || chestItems(c).some(([id]) => chestKey(id).includes(f)))
    .sort((a, b) => b.t - a.t).slice(0, 300);
  $('#ct-sum').textContent = `${rows.length} von ${chests.list.length} Kisten`;
  $('#ct-list').innerHTML = rows.map((c) => {
    const items = chestItems(c).filter((e) => !e[2]).sort((a, b) => b[1] - a[1]);
    return `<li data-ckey="${esc(c.key)}"><div class="main"><div class="title">${esc(c.title || chestWhere(c))}</div>
      <div class="sub">${esc(chestWhere(c))} · ${agoText(c.t)}</div>
      <div class="mini-items">${items.slice(0, 8).map(([id]) => itemIcon(id, 20)).join('')}${items.length > 8 ? ` <span class="muted">+${items.length - 8}</span>` : ''}</div></div>
      <button data-cdel title="Vergessen">✕</button></li>`;
  }).join('');
}

/* Fundorte des gewaehlten Items */
function renderWhere() {
  const id = chests.selItem;
  if (!id) { $('#ct-where').innerHTML = ''; return; }
  const k = inChests(id);
  $('#ct-where').innerHTML = `<h4>${itemIcon(id, 20)} ${esc(prettyItem(id))} · ${fmt(k.total)} <button data-hl-all title="Alle diese Kisten im Spiel mit Lichtsäulen markieren">Im Spiel zeigen</button></h4><ul class="list">${k.where.map(([n, c]) => {
    const inside = chestItems(c).filter((e) => e[0] === id && e[2]).map((e) => e[2]);
    return `<li data-ckey="${esc(c.key)}"><div class="main"><div class="title">${fmt(n)} in ${esc(c.title || chestWhere(c))}</div>
      <div class="sub">${esc(chestWhere(c))}${inside.length ? ' · in Shulker „' + esc(Array.from(new Set(inside)).join('“, „')) + '“' : ''} · ${agoText(c.t)}</div></div></li>`;
  }).join('')}</ul>`;
}

$('#ct-filter').addEventListener('input', renderChests);
$$('.ct-mode button').forEach((b) => b.addEventListener('click', () => { chests.mode = b.dataset.ctmode; savePref('ctMode', chests.mode); renderChests(); }));
$('#ct-grid').addEventListener('click', (e) => {
  const c = e.target.closest('.cell[data-item]');
  if (!c) return;
  chests.selItem = chests.selItem === c.dataset.item ? null : c.dataset.item;
  renderChests();
});
async function onChestClick(e) {
  if (e.target.closest('[data-hl-all]') && chests.selItem) { highlightChests(inChests(chests.selItem).where.map((w) => w[1])); return; }
  const li = e.target.closest('li[data-ckey]');
  if (!li) return;
  const c = chests.list.find((x) => x.key === li.dataset.ckey);
  if (!c) return;
  if (e.target.closest('[data-cdel]')) {
    await jsonPost('/api/containers/delete', { key: c.key });
    loadContainers();
    return;
  }
  openInv(c, chests.selItem);
}
$('#ct-list').addEventListener('click', onChestClick);
$('#ct-where').addEventListener('click', onChestClick);
$('#ct-csv').addEventListener('click', () => {
  const rows = ['Item;Anzahl;Fundorte'];
  for (const [id, n] of allItems()) rows.push(`${prettyItem(id)};${n};${inChests(id).where.slice(0, 5).map(([k, c]) => `${k} ${chestWhere(c)}`).join(' | ')}`);
  const url = URL.createObjectURL(new Blob(['﻿' + rows.join('\n')], { type: 'text/csv' }));
  const a = Object.assign(document.createElement('a'), { href: url, download: `${state.server}-kisten.csv` });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
});

function drawChests() {
  chests.layer.clearLayers();
  if (!$('#show-chests').checked) return;
  for (const c of chests.list) {
    if (c.dim !== state.dim) continue;
    const n = chestItems(c).reduce((a, e) => a + e[1], 0);
    L.circleMarker(toLatLng(c.x + 0.5, c.z + 0.5), { radius: 5, color: '#c48a3a', weight: 2, fillColor: '#f0b35a', fillOpacity: 0.9 })
      .bindTooltip(`${esc(c.title || chestWhere(c))}<br>${fmt(n)} Items · ${agoText(c.t)}`)
      .on('click', () => openInv(c)).addTo(chests.layer);
  }
  for (const w of unopenedChests()) {
    L.circleMarker(toLatLng(w.x + 0.5, w.z + 0.5), { radius: 4, color: '#8a8f98', weight: 1.5, fillColor: '#3a3f47', fillOpacity: 0.6 })
      .bindTooltip(`${esc(prettyItem(w.block))} bei ${w.x} ${w.y} ${w.z}<br>noch nie geöffnet`).addTo(chests.layer);
  }
  // Von jemand anderem geoeffnet (lila Ring)
  for (const l of chests.lids) {
    if (l.dim !== state.dim || !l.opens) continue;
    const who = l.open ? '<b>gerade offen</b>' : `zuletzt ${agoText(l.last)}`;
    L.circleMarker(toLatLng(l.x + 0.5, l.z + 0.5), { radius: 8, color: l.open ? '#ff4fd8' : '#c77dff', weight: 2.5, fill: false })
      .bindTooltip(`${esc(prettyItem(l.block))} bei ${l.x} ${l.y} ${l.z}<br>von jemand anderem geöffnet: ${l.opens}× · ${who}`).addTo(chests.layer);
  }
}
$('#show-chests').addEventListener('change', drawChests);
setInterval(() => { if (state.server && !document.hidden) loadContainers(); }, 10000);

// --- Kisten-Fenster (wie im Spiel)

var inv = { stack: [], hl: null };   // stack: [{ chest, title, slots, items }]

function openInv(c, highlight) {
  inv.hl = highlight || null;
  inv.pick = new Set();
  inv.stack = [{ chest: c, title: c.title || chestWhere(c), slots: c.slots || 27, items: c.items }];
  renderInv();
  $('#inv-win').hidden = false;
  if (c.dim === state.dim && c.dim !== 'ender') {
    if (mode3d && view3d) view3d.flyTo(c.x + 0.5, c.y + 0.5, c.z + 0.5);
  }
}
function refreshInv() {
  const top = inv.stack[0];
  const c = chests.list.find((x) => x.key === top.chest.key);
  if (!c || inv.stack.length > 1) return;
  top.chest = c; top.items = c.items;
  renderInv();
}
function renderInv() {
  const w = inv.stack[inv.stack.length - 1];
  const c = inv.stack[0].chest;
  $('#inv-title').textContent = w.title;
  $('#inv-back').hidden = inv.stack.length < 2;
  const n = Math.max(9, Math.ceil(Math.max(w.slots, 1) / 9) * 9);
  const cols = w.slots === 5 ? 5 : w.slots === 9 && /dispenser|dropper|crafter/.test(c.block || '') ? 3 : 9;
  const grid = $('#inv-grid');
  grid.style.gridTemplateColumns = `repeat(${cols}, var(--slot, 40px))`;
  const slots = new Array(w.slots === 5 || cols === 3 ? w.slots : n).fill(null);
  let next = 0;
  for (const it of w.items) {
    let i = Number.isInteger(it.slot) && it.slot < slots.length && !slots[it.slot] ? it.slot : -1;
    if (i < 0) { while (next < slots.length && slots[next]) next++; i = next; }
    if (i >= slots.length) slots.push(it); else slots[i] = it;
  }
  grid.innerHTML = slots.map((it, i) => {
    if (!it) return '<div class="slot"></div>';
    const sh = (it.contents || []).length > 0;
    const hit = inv.hl && (it.id === inv.hl || (it.contents || []).some((x) => x.id === inv.hl));
    const pk = inv.stack.length === 1 && inv.pick && inv.pick.has(i);
    return `<div class="slot has ${sh ? 'shulker' : ''} ${hit ? 'hit' : ''} ${pk ? 'pick' : ''}" data-i="${i}">${itemIcon(it.id)}${it.count > 1 ? `<span class="n">${it.count}</span>` : ''}</div>`;
  }).join('');
  inv.slots = slots;
  $('#inv-info').textContent = c.dim === 'ender' ? `Enderkiste · ${agoText(c.t)}` : `${c.x} ${c.y} ${c.z} · ${agoText(c.t)}`;
  $('#inv-goto').hidden = c.dim === 'ender' || c.dim !== state.dim;
  const top = inv.stack.length === 1;
  const npick = top && inv.pick ? inv.pick.size : 0;
  $('#inv-fetch').hidden = !npick;
  $('#inv-fetch').textContent = `Holen (${npick} Stack${npick === 1 ? '' : 's'})`;
  $('#inv-all').hidden = !top;
}
function closeInv() { $('#inv-win').hidden = true; $('#inv-tip').hidden = true; inv.stack = []; }
$('#inv-close').addEventListener('click', closeInv);
$('#inv-win').addEventListener('click', (e) => { if (e.target.id === 'inv-win') closeInv(); });
$('#inv-back').addEventListener('click', () => { inv.stack.pop(); renderInv(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#inv-win').hidden) { if (inv.stack.length > 1) { inv.stack.pop(); renderInv(); } else closeInv(); } });
$('#inv-goto').addEventListener('click', () => {
  const c = inv.stack[0].chest;
  closeInv();
  if (mode3d && view3d) view3d.flyTo(c.x + 0.5, c.y + 0.5, c.z + 0.5);
  else { setFollow(false); map.setView(toLatLng(c.x + 0.5, c.z + 0.5), Math.max(map.getZoom(), 2)); }
});
$('#inv-grid').addEventListener('click', (e) => {
  const s = e.target.closest('.slot.has');
  if (!s || inv.stack.length !== 1) return;
  const i = Number(s.dataset.i);
  if (inv.pick.has(i)) inv.pick.delete(i); else inv.pick.add(i);
  renderInv();
});
$('#inv-all').addEventListener('click', () => {
  const all = inv.slots.map((it, i) => (it ? i : -1)).filter((i) => i >= 0);
  inv.pick = inv.pick.size === all.length ? new Set() : new Set(all);
  renderInv();
});
$('#inv-fetch').addEventListener('click', () => {
  const c = inv.stack[0].chest;
  const p = state.player;
  if (c.dim !== 'ender' && p && p.active && p.dim !== c.dim) { toast('Die Kiste ist in einer anderen Dimension'); return; }
  const slots = Array.from(inv.pick).map((i) => (Number.isInteger(inv.slots[i].slot) ? inv.slots[i].slot : i));
  baritone({ kind: 'fetch', x: c.x, y: c.y, z: c.z, slots });
  closeInv();
});
$('#inv-grid').addEventListener('dblclick', (e) => {
  const s = e.target.closest('.slot.shulker');
  if (!s) return;
  const it = inv.slots[Number(s.dataset.i)];
  inv.stack.push({ chest: inv.stack[0].chest, title: it.name || prettyItem(it.id), slots: 27, items: it.contents });
  $('#inv-tip').hidden = true;
  renderInv();
});
$('#inv-grid').addEventListener('mousemove', (e) => {
  const s = e.target.closest('.slot.has');
  const tip = $('#inv-tip');
  if (!s) { tip.hidden = true; return; }
  const it = inv.slots[Number(s.dataset.i)];
  const inner = it.contents || [];
  tip.innerHTML = `<div>${it.name ? `<span class="nm">${esc(it.name)}</span>` : esc(prettyItem(it.id))}${it.count > 1 ? ' × ' + it.count : ''}</div>`
    + (inner.length ? inner.slice(0, 6).map((x) => `<div class="row">${itemIcon(x.id, 16)} ${esc(prettyItem(x.id))} × ${x.count}</div>`).join('')
      + (inner.length > 6 ? `<div class="row">und ${inner.length - 6} weitere … (Doppelklick öffnet)</div>` : '<div class="row">Doppelklick öffnet</div>') : '');
  tip.hidden = false;
  tip.style.left = Math.min(window.innerWidth - tip.offsetWidth - 8, e.clientX + 14) + 'px';
  tip.style.top = Math.min(window.innerHeight - tip.offsetHeight - 8, e.clientY + 14) + 'px';
});
$('#inv-grid').addEventListener('mouseleave', () => { $('#inv-tip').hidden = true; });

// ------------------------------------------------------------------ Zeitleiste

var hist = { dates: [], res: null, layer: L.layerGroup().addTo(map) };

async function loadHistoryDates() {
  if (!state.server) return;
  try { hist.dates = await api('/api/history/dates?' + new URLSearchParams({ srv: state.server, dim: state.dim })); } catch (_) { hist.dates = []; }
  const sel = $('#hist-date');
  sel.innerHTML = hist.dates.length ? hist.dates.slice().reverse().map((d) => `<option value="${d}">seit ${d.split('-').reverse().join('.')}</option>`).join('')
    : '<option value="">noch kein früherer Stand</option>';
  $('#hist-run').disabled = !hist.dates.length;
  hist.res = null; drawHistory();
  $('#hist-out').innerHTML = ''; $('#hist-list').innerHTML = '';
}

$('#hist-run').addEventListener('click', async () => {
  const date = $('#hist-date').value;
  if (!date) return;
  const btn = $('#hist-run');
  btn.disabled = true; btn.textContent = 'Vergleiche …';
  try {
    hist.res = await api('/api/history/diff?' + new URLSearchParams({ srv: state.server, dim: state.dim, date }));
    const t = hist.res.totals;
    $('#hist-out').innerHTML = `<div class="chk-stats"><span class="k-added">neu gebaut</span><span class="mono">${fmt(t.added)}</span>
      <span class="k-removed">abgebaut</span><span class="mono">${fmt(t.removed)}</span>
      <span class="k-changed">ersetzt</span><span class="mono">${fmt(t.changed)}</span></div>
      ${hist.res.chunks.length ? '<h4>Am meisten verändert</h4>' : '<p class="hint">Seit diesem Tag hat sich in den gesicherten Chunks nichts verändert.</p>'}`;
    $('#hist-list').innerHTML = hist.res.chunks.slice(0, 100).map((c) => `<li data-hc="${c.cx},${c.cz}"><div class="main">
      <div class="title mono">${c.cx * 16 + 8} ${c.cz * 16 + 8}</div>
      <div class="sub"><span class="k-added">+${fmt(c.added)}</span> · <span class="k-removed">−${fmt(c.removed)}</span> · <span class="k-changed">~${fmt(c.changed)}</span></div></div></li>`).join('');
    drawHistory();
  } catch (e) { $('#hist-out').innerHTML = `<p class="err">${esc(e.message)}</p>`; }
  finally { btn.disabled = false; btn.textContent = 'Vergleichen'; }
});
$('#hist-list').addEventListener('click', (e) => {
  const li = e.target.closest('li[data-hc]');
  if (!li) return;
  const [cx, cz] = li.dataset.hc.split(',').map(Number);
  if (mode3d && view3d) view3d.flyTo(cx * 16 + 8, 80, cz * 16 + 8);
  else { setFollow(false); map.setView(toLatLng(cx * 16 + 8, cz * 16 + 8), Math.max(map.getZoom(), 1)); }
});
$('#hist-show').addEventListener('change', drawHistory);

function drawHistory() {
  hist.layer.clearLayers();
  const on = hist.res && $('#hist-show').checked;
  if (view3d && view3d.setDiff) view3d.setDiff(on ? hist.res.points : null);
  if (!on) return;
  const max = Math.max(1, ...hist.res.chunks.map((c) => c.added + c.removed + c.changed));
  for (const c of hist.res.chunks) {
    const n = c.added + c.removed + c.changed;
    const col = c.removed > c.added ? '#e5484d' : c.added > c.removed ? '#22a06b' : '#f5a524';
    L.rectangle(L.latLngBounds(toLatLng(c.cx * 16, c.cz * 16), toLatLng(c.cx * 16 + 16, c.cz * 16 + 16)),
      { color: col, weight: 1, fillColor: col, fillOpacity: 0.15 + 0.5 * Math.sqrt(n / max) })
      .bindTooltip(`+${fmt(c.added)} gebaut · −${fmt(c.removed)} abgebaut · ~${fmt(c.changed)} ersetzt`).addTo(hist.layer);
  }
}

// ------------------------------------------------------------------ Schichten (Schnitt in Y)

/* Karte: oberster Block unterhalb von Y (eigene Kachel-Schicht "surface<Y>").
 * 3D: alles oberhalb wird weggeschnitten, bei "Nur Ebene" auch alles darunter. */
var cut = { mode: prefs.cutMode || 'off', y: prefs.cutY ?? 64 };
let cutTimer = null;

function cutRange(dimKey) {
  const [y0, y1] = DIM_Y[dimKey] || [-64, 319];
  for (const id of ['#cut-y', '#cut-yn']) { $(id).min = y0; $(id).max = y1; }
  cut.y = Math.max(y0, Math.min(y1, cut.y));
}

function setCut(mode, y, now) {
  cut.mode = mode; cut.y = Math.round(y);
  savePref('cutMode', mode); savePref('cutY', cut.y);
  $('#cut-mode').value = mode;
  $('#cut-y').value = cut.y;
  if (document.activeElement !== $('#cut-yn')) $('#cut-yn').value = cut.y;
  $('.cut').classList.toggle('off', mode === 'off');
  if (view3d) view3d.setClip(mode, cut.y);
  // Karte: Kacheln fuer die neue Hoehe erst nach kurzer Pause anfordern
  clearTimeout(cutTimer);
  cutTimer = setTimeout(() => {
    if (!state.server) return;
    const want = mode === 'off' ? (state.baseLayer || state.layer) : `surface${cut.y}`;
    if (want === state.layer) return;
    state.layer = want;
    rebuildTiles();
    renderSelectors();
    loadMarkers();
    saveHash();
  }, now ? 0 : 350);
}
$('#cut-mode').addEventListener('change', (e) => {
  let y = cut.y;
  // Beim Einschalten: Hoehe unter dem Spieler bzw. 3D-Kamera als Startwert
  if (cut.mode === 'off' && e.target.value !== 'off') {
    if (mode3d && view3d) y = Math.floor(view3d.position().y) - 2;
    else if (state.player && state.player.active && state.player.dim === state.dim) y = Math.floor(state.player.y) + 1;
  }
  setCut(e.target.value, y);
});
$('#cut-y').addEventListener('input', (e) => setCut(cut.mode, Number(e.target.value)));
$('#cut-yn').addEventListener('change', (e) => setCut(cut.mode, Number(e.target.value)));

// ------------------------------------------------------------------ Tastatur

document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, select, textarea')) return;
  if (e.key === 'Tab') { e.preventDefault(); set3d(!mode3d); return; }
  if (schemKeys(e)) { e.preventDefault(); return; }
  if ((e.key === ',' || e.key === '.') && cut.mode !== 'off') {
    setCut(cut.mode, cut.y + (e.key === '.' ? 1 : -1) * (e.shiftKey ? 10 : 1));
    return;
  }
  if (e.key === 't' || e.key === 'T') { setTrail(!state.trail); return; }
  if (mode3d) {
    if ((e.key === 'f' || e.key === 'F') && state.player && state.player.active) view3d.gotoPlayer(state.player);
    return;
  }
  if (e.key === '?') { toggleHelp(); return; }
  if (e.key === 'Escape' && !$('#help').hidden) { toggleHelp(false); return; }
  const tabs = ['waypoints', 'signs', 'stashes', 'schematics', 'editor', 'chests', 'history', 'radar'];
  if (/^[1-8]$/.test(e.key) && !e.ctrlKey && !e.altKey && !e.metaKey) {
    const t = tabs[Number(e.key) - 1];
    if (!$('#side').hidden && sideTab === t) toggleSide(false); else toggleSide(true, t);
    return;
  }
  if (e.key === 'f' || e.key === 'F') setFollow(!state.follow);
  else if (e.key === 's' || e.key === 'S') toggleSide();
  else if (e.key === 'e' || e.key === 'E') setSelect(!sel.on);
  else if (e.key === 'Escape' && sel.on) setSelect(false);
  else if (e.key === '/') { e.preventDefault(); $('#goto-input').focus(); }
});


// ------------------------------------------------------------------ Bauen: Baritone, Printer, Schichten

function highlightChests(list) {
  const p = state.player;
  const pts = list.filter((c) => c.dim !== 'ender' && (!p || c.dim === p.dim)).map((c) => [c.x, c.y, c.z]);
  if (!pts.length) { toast('Keine dieser Kisten ist in deiner Dimension'); return; }
  baritone({ kind: 'highlight', pts });
}

var ctl = { state: null, busy: false, pending: 0 };
async function pollControl() {
  if ($('#schem-panel').hidden || document.hidden || !state.server) return;
  const srv = (state.player && state.player.server) || state.server;
  try { ctl.state = await api('/api/control/state?' + new URLSearchParams({ srv })); } catch (_) { ctl.state = null; }
  try { ctl.auto = await api('/api/auto?' + new URLSearchParams({ srv })); } catch (_) { ctl.auto = null; }
  renderBuild();
  renderAuto();
}

/* Autobau: holen und bauen im Wechsel (rechnet der Viewer, siehe autobuild.py) */
const AB_PHASE = { unpack: 'packt Shulker aus', plan: 'plant', fetch: 'holt Material', approach: 'läuft zur Baustelle', build: 'baut', return: 'bringt Reste zurück', done: 'fertig', error: 'angehalten', off: 'aus' };
function renderAuto() {
  const a = ctl.auto, pl = selPl();
  const running = a && a.mode !== 'store' && ['plan', 'fetch', 'approach', 'build', 'unpack', 'return'].includes(a.phase);
  const mine = running && pl && a.pid === pl.id;
  $('#ab-toggle').textContent = running ? '■ Autobau stoppen' : '▶ Automatisch holen und bauen';
  spBadge('build', mine ? (AB_PHASE[a.phase] || 'läuft') : 'Autobau', mine ? 'good' : '');
  $('#ab-toggle').classList.toggle('danger', !!running);
  $('#ab-toggle').classList.add('primary');
  $('#ab-toggle').disabled = !running && !(pl && pl.ingame);
  $('#ab-toggle').title = running || (pl && pl.ingame) ? '' : 'Erst „Ins Spiel“ einschalten, damit Baritone die Schematic aus Litematica bauen kann';
  if (!a || a.mode === 'store' || a.phase === 'off' && !a.log) { $('#ab-info').textContent = ''; $('#ab-log').innerHTML = ''; return; }
  $('#ab-info').innerHTML = `<b class="${a.phase === 'error' ? 'bad' : a.phase === 'done' ? 'ok' : a.paused ? 'warn' : ''}">${running && a.paused ? 'pausiert' : AB_PHASE[a.phase] || a.phase}</b>` +
    (running && !mine ? ' (andere Schematic)' : '') + (a.info ? ' · ' + esc(a.info) : '') + (a.fetched ? ` · ${a.fetched}× geholt` : '');
  $('#ab-log').innerHTML = (a.log || []).slice(-8).reverse().map(([t, m]) =>
    `<li><span class="mono muted">${new Date(t).toLocaleTimeString(I18N.locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span> ${esc(m)}</li>`).join('');
}
/* Schutz-Schalter fuer den Autobau (Spieler-Pause, Essen, Werkzeug, Reste) */
const guard = { cfg: null };
function renderGuard() {
  const g = guard.cfg;
  if (!g) return;
  $$('[data-guard]').forEach((c) => { c.checked = !!g[c.dataset.guard]; });
  if (document.activeElement !== $('#gd-dist')) $('#gd-dist').value = g.radarDist;
  if (document.activeElement !== $('#gd-friends')) $('#gd-friends').value = (g.friends || []).join(', ');
  const on = [g.radar && 'Spieler-Pause', g.eat && 'Essen', g.tools && 'Werkzeug', g.leftovers && 'Reste'].filter(Boolean);
  $('#gd-sum').textContent = on.length ? '· ' + on.join(', ') : '· alles aus';
}
async function saveGuard(patch) {
  try { guard.cfg = await api('/api/auto/settings', { method: 'POST', body: JSON.stringify(patch) }); } catch (e) { toast('Speichern: ' + e.message); }
  renderGuard();
}
$$('[data-guard]').forEach((c) => c.addEventListener('change', () => saveGuard({ [c.dataset.guard]: c.checked })));
$('#gd-dist').addEventListener('change', (e) => saveGuard({ radarDist: Number(e.target.value) || 64 }));
$('#gd-friends').addEventListener('change', (e) => saveGuard({ friends: e.target.value.split(',') }));
api('/api/auto/settings').then((g) => { guard.cfg = g; renderGuard(); }).catch(() => {});

/* Einlagern (Kisten-Tab): laeuft im selben Ablauf wie der Autobau */
const storeUi = { timer: null };
async function pollStore() {
  const srv = (state.player && state.player.server) || state.server;
  let a = null;
  try { a = await api('/api/auto?' + new URLSearchParams({ srv })); } catch (_) { /* egal */ }
  const running = a && a.mode === 'store' && a.phase === 'store';
  $('#st-go').textContent = running ? '■ Einlagern stoppen' : 'Inventar einlagern';
  if (a && a.mode === 'store') {
    $('#st-info').innerHTML = `<b class="${a.phase === 'error' ? 'bad' : a.phase === 'done' ? 'ok' : ''}">${running ? 'lagert ein' : a.phase === 'done' ? 'fertig' : 'angehalten'}</b> · ${esc(a.info || '')}`;
  }
  if (!running && storeUi.timer) { clearInterval(storeUi.timer); storeUi.timer = null; loadContainers(); }
}
$('#st-go').addEventListener('click', async () => {
  const srv = (state.player && state.player.server) || state.server;
  try {
    if (storeUi.timer) { await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv }) }); }
    else {
      if (!confirm('Inventar einlagern?\n\nBaritone läuft zu deinen Kisten und legt jeden Stack dorthin, wo dieses Item schon liegt. Was in keine Kiste passt, bleibt bei dir.' + ($('#st-hotbar').checked ? '' : '\n\nDie Hotbar bleibt, wie sie ist.'))) return;
      await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv, store: true, hotbar: $('#st-hotbar').checked }) });
      storeUi.timer = setInterval(pollStore, 1500);
    }
  } catch (e) { toast('Einlagern: ' + e.message); }
  pollStore();
});

$('#ab-toggle').addEventListener('click', async () => {
  const a = ctl.auto, pl = selPl();
  const srv = (state.player && state.player.server) || state.server;
  const running = a && a.mode !== 'store' && ['plan', 'fetch', 'approach', 'build', 'unpack', 'return'].includes(a.phase);
  if (!running && !pl) return;
  if (!running && !confirm('Autobau starten?\n\nBaritone läuft zu deinen Kisten, nimmt das fehlende Material und baut die Schematic. Unterwegs baut es nichts ab, beim Bauen selbst schon (falsche Blöcke im Rahmen).\n\nShulker im Inventar packt er neben sich aus und baut sie wieder ab.\\n\\nAbbrechen jederzeit mit „■ Stopp“.')) return;
  try {
    ctl.auto = await api('/api/auto', { method: 'POST', body: JSON.stringify(running ? { srv } : { srv, pid: pl.id }) });
  } catch (e) { toast('Autobau: ' + e.message); }
  renderAuto();
});
setInterval(pollControl, 1500);

async function control(body) {
  ctl.pending = Date.now();
  try {
    ctl.state = await jsonPost('/api/control', { srv: (state.player && state.player.server) || state.server, ...body });
    renderBuild();
  } catch (e) { toast('Printer: ' + e.message); }
}

function renderBuild() {
  const c = ctl.state;
  const g = c && c.game;
  const pr = g && g.printer, ly = g && g.layer;
  const pl = selPl();
  let txt;
  if (!c || !c.online) txt = 'Spiel nicht verbunden (Minecraft mit Mod 0.3.2 starten)';
  else {
    const parts = [];
    parts.push(pr && pr.available ? `Printer ${pr.on ? '<b class="ok">AN</b>' : 'aus'}` : 'kein Printer gefunden');
    if (ly && ly.available) {
      parts.push(ly.mode === 'ALL' ? 'Litematica: alle Schichten' : ly.mode === 'SINGLE_LAYER' ? `Litematica: Schicht Y ${ly.single}` :
        ly.mode === 'LAYER_RANGE' ? `Litematica: Y ${ly.min} bis ${ly.max}` : `Litematica: ${ly.mode.toLowerCase()}`);
    }
    txt = parts.join(' · ');
  }
  $('#bd-game').innerHTML = txt;
  $('#bd-game').classList.toggle('muted', !(c && c.online));
  // Regler nur nachziehen, wenn gerade niemand daran dreht
  const idle = Date.now() - ctl.pending > 2500;
  const ok = !!(pr && pr.available);
  $('#pr-toggle').textContent = ok ? (pr.on ? 'Printer AN, klicken zum Ausschalten' : 'Printer aus, klicken zum Einschalten') : 'Printer: nicht verbunden';
  $('#pr-toggle').classList.toggle('on', ok && pr.on);
  for (const el of $$('#pr-range, #pr-interval, [data-pr]')) el.disabled = !ok;
  if (ok && idle) {
    $('#pr-range').value = pr.range; $('#pr-interval').value = pr.interval;
    $$('[data-pr]').forEach((cb) => { cb.checked = !!pr[cb.dataset.pr]; });
  }
  $('#pr-range-v').textContent = Number($('#pr-range').value).toFixed(1);
  $('#pr-interval-v').textContent = $('#pr-interval').value + ' Ticks';
  const auto = c && pl && c.auto === pl.id;
  $('#ly-auto').checked = !!auto;
  if (idle && document.activeElement !== $('#ly-y')) {
    const y = ly && ly.available && ly.mode !== 'ALL' ? (ly.mode === 'SINGLE_LAYER' ? ly.single : ly.min) : (pl ? pl.y : '');
    if ($('#ly-y').value === '' || (ly && ly.mode !== 'ALL')) $('#ly-y').value = y;
  }
  $('#ly-info').textContent = auto ? (c.autoInfo || 'rechnet …') : c && c.auto ? 'Automatik läuft für eine andere Schematic' : '';
  $('#bd-build').disabled = !(pl && pl.ingame);
  $('#bd-build').title = pl && pl.ingame ? 'Baritone baut die Schematic aus Litematica (#litematica)' : 'Erst „Ins Spiel“ einschalten';
}

$('#pr-toggle').addEventListener('click', () => {
  const pr = ctl.state && ctl.state.game && ctl.state.game.printer;
  if (pr && pr.available) control({ printer: { on: !pr.on } });
});
$('#pr-range').addEventListener('input', () => { ctl.pending = Date.now(); renderBuild(); });
$('#pr-interval').addEventListener('input', () => { ctl.pending = Date.now(); renderBuild(); });
$('#pr-range').addEventListener('change', (e) => control({ printer: { range: Number(e.target.value) } }));
$('#pr-interval').addEventListener('change', (e) => control({ printer: { interval: Number(e.target.value) } }));
$$('[data-pr]').forEach((cb) => cb.addEventListener('change', () => control({ printer: { [cb.dataset.pr]: cb.checked } })));
$('#ly-all').addEventListener('click', () => control({ layer: { mode: 'all' }, auto: null }));
const setLayer = (y) => { $('#ly-y').value = y; control({ layer: { mode: 'range', min: y, max: y }, auto: null }); };
$('#ly-set').addEventListener('click', () => setLayer(Number($('#ly-y').value)));
$('#ly-up').addEventListener('click', () => setLayer(Number($('#ly-y').value) + 1));
$('#ly-down').addEventListener('click', () => setLayer(Number($('#ly-y').value) - 1));
$('#ly-y').addEventListener('keydown', (e) => { if (e.key === 'Enter') setLayer(Number(e.target.value)); });
$('#ly-auto').addEventListener('change', (e) => {
  const pl = selPl();
  if (e.target.checked && pl && !pl.ingame) toast('Tipp: „Ins Spiel“ einschalten, sonst sieht Litematica die Schematic nicht');
  control({ auto: e.target.checked && pl ? pl.id : null });
});

$('#bd-build').addEventListener('click', () => { const pl = selPl(); if (pl) baritone({ kind: 'litematica', pid: pl.id }); });
$('#bd-pause').addEventListener('click', () => baritone({ kind: 'pause' }));
$('#bd-resume').addEventListener('click', () => baritone({ kind: 'resume' }));
$('#bd-stop').addEventListener('click', () => baritone({ kind: 'stop' }));
$('#bd-clear').addEventListener('click', () => {
  const pl = selPl();
  if (!pl) return;
  const [w, h, l] = pl.size;
  const done = schem.check ? schem.check.stats.ok : 0;
  if (!confirm(`Baritone baut ALLES im Rahmen ${pl.x} ${pl.y} ${pl.z} bis ${pl.x + w - 1} ${pl.y + h - 1} ${pl.z + l - 1} ab (${fmt(w * h * l)} Blöcke).` +
    (done ? `\n\nAchtung: ${fmt(done)} Blöcke der Schematic stehen schon und würden mit abgebaut.` : '') + '\n\nWirklich starten?')) return;
  baritone({ kind: 'clear', x0: pl.x, y0: pl.y, z0: pl.z, x1: pl.x + w - 1, y1: pl.y + h - 1, z1: pl.z + l - 1 });
});

// ------------------------------------------------------------------ Radar

var radar = { layer: L.layerGroup().addTo(map), markers: new Map(), alarmed: new Map(), log: [], lastLog: 0 };
$('#rd-radius').value = prefs.rdRadius || 96;
$('#rd-sound').checked = prefs.rdSound !== false;
$('#rd-radius-v').textContent = $('#rd-radius').value;
$('#rd-radius').addEventListener('input', (e) => { $('#rd-radius-v').textContent = e.target.value; savePref('rdRadius', Number(e.target.value)); });
$('#rd-sound').addEventListener('change', (e) => savePref('rdSound', e.target.checked));

function beep() {
  try {
    const ac = new (window.AudioContext || window.webkitAudioContext)();
    [0, 0.25].forEach((t) => {
      const o = ac.createOscillator(), g = ac.createGain();
      o.frequency.value = 880; o.connect(g); g.connect(ac.destination);
      g.gain.setValueAtTime(0.15, ac.currentTime + t); g.gain.exponentialRampToValueAtTime(0.001, ac.currentTime + t + 0.2);
      o.start(ac.currentTime + t); o.stop(ac.currentTime + t + 0.22);
    });
  } catch (_) { /* egal */ }
}

function radarUpdate(p) {
  const others = p && p.active && p.dim === state.dim && (!p.server || p.server === state.server) ? (p.others || []) : [];
  const seen = new Set();
  for (const o of others) {
    seen.add(o.name);
    let m = radar.markers.get(o.name);
    if (!m) {
      m = L.marker(toLatLng(o.x, o.z), { icon: L.divIcon({ className: '', html: `<div class="radar-pin"><span>${esc(o.name)}</span></div>`, iconSize: [12, 12], iconAnchor: [6, 6] }), zIndexOffset: 900 }).addTo(radar.layer);
      radar.markers.set(o.name, m);
    }
    m.setLatLng(toLatLng(o.x, o.z));
  }
  for (const [n, m] of radar.markers) if (!seen.has(n)) { radar.layer.removeLayer(m); radar.markers.delete(n); }
  if (view3d) view3d.setOthers(others);
  // Alarm: jemand nahe an dir oder an einer Baustelle
  const R = Number($('#rd-radius').value);
  const sites = placedHere().map((pl) => [pl.x + pl.size[0] / 2, pl.z + pl.size[2] / 2, pl.name]);
  for (const o of others) {
    let why = null;
    if (o.dist != null && o.dist < R) why = `${Math.round(o.dist)} m von dir`;
    for (const [sx, sz, name] of sites) {
      const d = Math.hypot(o.x - sx, o.z - sz);
      if (d < R) { why = `${Math.round(d)} m von „${name}“`; break; }
    }
    if (why && Date.now() - (radar.alarmed.get(o.name) || 0) > 120000) {
      radar.alarmed.set(o.name, Date.now());
      toast(`⚠ ${o.name} ist ${why}`);
      if ($('#rd-sound').checked) beep();
      if (window.Notification && Notification.permission === 'granted' && document.hidden) new Notification('Webmatica Radar', { body: `${o.name} ist ${why}` });
    }
  }
  const badge = $('#radar-badge');
  badge.hidden = !others.length;
  badge.textContent = others.length;
  $('#rd-now').innerHTML = others.length ? others.slice().sort((a, b) => a.dist - b.dist).map((o) => `<li data-x="${Math.round(o.x)}" data-z="${Math.round(o.z)}"><div class="main"><div class="title radar-name">${esc(o.name)}</div>
    <div class="sub mono">${fmt(o.x)} ${fmt(o.y)} ${fmt(o.z)} · ${Math.round(o.dist)} m</div></div></li>`).join('') : '<li class="muted">niemand</li>';
  if (Date.now() - radar.lastLog > 10000) { radar.lastLog = Date.now(); loadRadarLog(); }
}
async function loadRadarLog() {
  try { radar.log = await api('/api/radar?' + new URLSearchParams({ srv: state.server })); } catch (_) { return; }
  $('#rd-log').innerHTML = radar.log.length ? radar.log.slice(0, 100).map((e) => `<li data-x="${e.x}" data-z="${e.z}"><div class="main"><div class="title">${esc(e.name)}</div>
    <div class="sub">${agoText(e.last * 1000)} · ${Math.round((e.last - e.first) / 60)} min · am nächsten ${Math.round(e.closest)} m bei ${e.x} ${e.z}${e.dim !== 'overworld' ? ' (' + esc(e.dim) + ')' : ''}</div></div></li>`).join('') : '<li class="muted">noch niemand gesehen</li>';
}
for (const id of ['#rd-now', '#rd-log']) {
  $(id).addEventListener('click', (e) => {
    const li = e.target.closest('li[data-x]');
    if (li) map.setView(toLatLng(Number(li.dataset.x), Number(li.dataset.z)), Math.max(map.getZoom(), 2));
  });
}
$('#rd-sound').addEventListener('click', () => { if (window.Notification && Notification.permission === 'default') Notification.requestPermission(); });

// ------------------------------------------------------------------ Start

(async function start() {
  try {
    state.worlds = (await api('/api/worlds')).worlds;
  } catch (e) {
    toast('Server nicht erreichbar: ' + e.message);
    return;
  }
  if (!state.worlds.length) {
    toast('Noch keine Aufnahmen gefunden. Mit der Mod spielen, dann neu laden.');
    setInterval(async () => { await pollWorlds(); if (state.worlds.length) location.reload(); }, 5000);
    return;
  }
  const h = readHash();
  if (h && state.worlds.some((w) => w.id === h.server)) {
    select(h.server, h.dim, h.layer, true);
    map.setView(toLatLng(h.x, h.z), h.zoom);
  } else {
    select(state.worlds[0].id, null, null);
  }
  setCut(cut.mode, cut.y, true);
  loadWaypoints();
  loadLibrary();
  if (prefs.trail) setTrail(true);
  schedulePlayer();
  setInterval(pollChanges, 3000);
  setInterval(pollWorlds, 15000);
  pollPlayer();
})();
