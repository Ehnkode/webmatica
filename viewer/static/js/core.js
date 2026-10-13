/* Webmatica – map UI.
 *
 * Coordinates: Leaflet CRS.Simple, 1 unit = 1 block.
 * World point (x, z) <-> LatLng(-z, x). Zoom level 0 = 1 pixel per block,
 * one tile (512 px) = one region.
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

/* Remember settings in the browser (errors don't matter, e.g. private mode). */
const prefs = (() => { try { return JSON.parse(localStorage.getItem('mapper.prefs') || '{}'); } catch (_) { return {}; } })();
function savePref(k, v) { prefs[k] = v; try { localStorage.setItem('mapper.prefs', JSON.stringify(prefs)); } catch (_) { /* ignore */ } }

// ------------------------------------------------------------------ Helpers

async function api(path, opts) {
  const r = await fetch(path, opts);
  if (!r.ok) {
    let msg = r.status + ' ' + r.statusText;
    try { msg = (await r.json()).error || msg; } catch (_) { /* ignore */ }
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

// ------------------------------------------------------------------ Map

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

/* Tile layer that can reload individual tiles on demand. */
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
  /* Region (rx, rz) changed: reload the affected tiles at every zoom level. */
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
  if (tl && tl.layer) tlShow(tl.idx);   // Switch the timelapse to the new layer
  if (typeof spawn2dSync === 'function') spawn2dSync();
}

// ------------------------------------------------------------------ Server / dimension / layer selection

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
  else if (typeof xray2dSoon === 'function') xray2dSoon(0);
}

/* Initial view: everything recorded if it is small, otherwise the most recently
 * recorded spot (on 2b2t recordings are often thousands of blocks apart). */
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
  // Show the same spot in the other dimension (Nether = Overworld / 8).
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

// ------------------------------------------------------------------ Cursor + column info

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
