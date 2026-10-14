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

/* Start check before an automation: one line per condition.
 * ok: true = fine, false = blocks the start, null = only a warning. */
function renderChecklist(el, items) {
  el.innerHTML = items.map((it) => `<li class="${it.ok === true ? 'ok' : it.ok === false ? 'bad' : 'warn'}">` +
    `<span>${it.text}</span>${it.action ? `<button class="small" data-check-action="${it.action.id}">${esc(it.action.label)}</button>` : ''}</li>`).join('');
  return items.every((it) => it.ok !== false);
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
    img.className = 'region-tile';   // faint grid where nothing is recorded yet (see style.css)
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

const overlay = {
  waypoints: L.layerGroup().addTo(map),
  signs: L.layerGroup().addTo(map),
  stashes: L.layerGroup().addTo(map),
  player: L.layerGroup().addTo(map),
};

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

map.on('mouseout', () => { clearTimeout(infoTimer); $('#cursor').textContent = ''; $('#info').textContent = ''; });

/* Scale: a bar of at most 100 px with a round number of blocks */
function drawScale() {
  const px = 2 ** map.getZoom();          // pixels per block
  const nice = [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000];
  const n = nice.filter((v) => v * px <= 100).pop() || 1;
  $('#scale i').style.width = `${Math.round(n * px)}px`;
  $('#scale span').textContent = `${fmt(n)} ${n === 1 ? 'Block' : 'Blöcke'}`;
}
map.on('zoomend', drawScale);
drawScale();

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
