/* Webmatica map UI: the shown world, dimension and layer (selector buttons, map tiles). */
import './core.js';   // runs before this file (load order, see index.html)
import { $, dimOf, esc, fromLatLng, map, MAX_ZOOM, MIN_ZOOM, prefs, RegionLayer, savePref, state, toLatLng } from './core.js';
import { drawWaypoints, loadMarkers } from './places.js';
import { drawPlayer, mode3d, reload3d, saveHash, spawn2dSync } from './player.js';
import { loadTrail, sel, setSelect } from './selection.js';
import { loadPlacements } from './schematics.js';
import { loadContainers } from './chests.js';
import { cut, cutRange, loadHistoryDates, tl, tlShow } from './history.js';
import { xray2dSoon } from './xray.js';

let tiles = null;
let water = null;

/* Moving water on the map (like BlueMap): a mask tile per region says where water is; a soft,
 * seamless ripple texture slides over it and is blended onto the map (soft light). The browser
 * does the animation on the GPU, nothing is redrawn per frame. Only from zoom 0 on: further out
 * the ripples would be smaller than a pixel. */
function rippleTexture(seed) {
  const N = 32;   // one texel per block, repeats every 32 blocks
  const c = document.createElement('canvas');
  c.width = c.height = N;
  const g = c.getContext('2d');
  const img = g.createImageData(N, N);
  // whole-number wave counts per tile, so the texture repeats without seams
  const waves = (seed ? [[2, 1, 0.8], [-1, 3, 0.6], [4, -3, 0.35], [-5, -6, 0.2]] : [[1, 2, 0.9], [3, -1, 0.6], [-2, 3, 0.5], [6, 5, 0.25]]).map(([kx, ky, a], i) => [kx, ky, a, seed * 1.7 + i * 2.1]);
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      let v = 0;
      for (const [kx, ky, a, ph] of waves) v += a * Math.sin((2 * Math.PI * (kx * x + ky * y)) / N + ph);
      // sharp bright crests, broad troughs: looks like light on small waves
      const t = Math.max(0, Math.min(1, 0.5 + v * 0.22));
      const crest = Math.pow(t, 3);
      const k = (y * N + x) * 4;
      img.data[k] = img.data[k + 1] = img.data[k + 2] = Math.round(255 * (0.35 + 0.65 * crest));
      img.data[k + 3] = 255;
    }
  }
  g.putImageData(img, 0, 0);
  return c.toDataURL();
}
let rippleCss = null;
function ensureRipples() {
  if (rippleCss) return;
  rippleCss = true;
  document.documentElement.style.setProperty('--ripple-a', `url(${rippleTexture(0)})`);
  document.documentElement.style.setProperty('--ripple-b', `url(${rippleTexture(1)})`);
}
const WaterLayer = RegionLayer.extend({
  tileUrl(c) { return RegionLayer.prototype.tileUrl.call(this, c) + '&water=1'; },
  createTile(coords, done) {
    const div = document.createElement('div');
    div.className = 'water-tile';
    div.innerHTML = '<i class="wa"></i><i class="wb"></i>';
    // all tiles in step with one clock, otherwise the ripples break at the tile edges
    const t = performance.now() / 1000;
    div.children[0].style.animationDelay = `-${(t % 11).toFixed(3)}s`;
    div.children[1].style.animationDelay = `-${(t % 7).toFixed(3)}s`;
    const img = new Image();
    img.onload = () => {
      div.style.webkitMaskImage = div.style.maskImage = `url(${img.src})`;
      done(null, div);
    };
    img.onerror = () => done(null, div);
    img.src = this.tileUrl(coords);
    this._imgs.set(`${coords.z}:${coords.x}:${coords.y}`, img);
    return div;
  },
});
function rebuildWater() {
  if (water) { map.removeLayer(water); water = null; }
  const on = $('#show-water') ? $('#show-water').checked : true;
  if (!on || state.dim === 'the_nether' || state.dim === 'the_end' || !(state.layer || '').startsWith('surface')) return;
  ensureRipples();
  water = new WaterLayer({ tileSize: 512, minZoom: 0, maxZoom: MAX_ZOOM, maxNativeZoom: 0, minNativeZoom: 0, keepBuffer: 1, zIndex: 2, className: 'water-layer' });
  water.addTo(map);
}

function rebuildTiles() {
  if (tiles) map.removeLayer(tiles);
  tiles = new RegionLayer({ tileSize: 512, minZoom: MIN_ZOOM, maxZoom: MAX_ZOOM, maxNativeZoom: 0, minNativeZoom: MIN_ZOOM, keepBuffer: 1 });
  const refresh = tiles.refreshRegion;
  tiles.refreshRegion = function (rx, rz) { refresh.call(this, rx, rz); if (water) water.refreshRegion(rx, rz); };
  tiles.addTo(map);
  tiles.bringToBack();
  rebuildWater();
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
  if (mode3d) reload3d();
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

if ($('#show-water')) {
  $('#show-water').checked = prefs.water !== false;
  $('#show-water').addEventListener('change', (e) => {
    savePref('water', e.target.checked);
    rebuildWater();
  });
}

export { rebuildTiles, renderSelectors, select, tiles };
