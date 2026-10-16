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

/* Moving water on the map, like in the game: the vanilla still-water texture (all frames of
 * water_still.png from the game jar) is drawn once per block over the water, masked by a water tile
 * per region and blended onto the map (soft light, so the biome color of the map stays). The frames
 * change every 2 game ticks like in vanilla, for all tiles at once through one CSS variable. Only
 * from zoom 1 on: further out a block is a single pixel and the texture would just flicker. */
const WATER_FRAME_MS = 100;    // frametime 2 in water_still.png.mcmeta = 2 ticks
const WATER_MIN_ZOOM = 1;
let waterFrames = null, waterTimer = null;
function loadWaterFrames() {
  if (waterFrames) return waterFrames;
  waterFrames = new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const n = img.width, count = Math.max(1, Math.floor(img.height / n));
      const c = document.createElement('canvas');
      c.width = n;
      c.height = n * count;
      const g = c.getContext('2d');
      g.drawImage(img, 0, 0);
      const px = g.getImageData(0, 0, n, n * count).data;
      // brightness relative to the mean of all frames: 128 = unchanged map color under soft light
      let sum = 0;
      for (let i = 0; i < px.length; i += 4) sum += (px[i] + px[i + 1] + px[i + 2]) / 3;
      const mean = sum / (px.length / 4);
      const out = [];
      const f = document.createElement('canvas');
      f.width = f.height = n;
      const fg = f.getContext('2d');
      for (let k = 0; k < count; k++) {
        const d = fg.createImageData(n, n);
        for (let i = 0; i < n * n; i++) {
          const j = (k * n * n + i) * 4;
          const v = Math.max(0, Math.min(255, Math.round(128 + ((px[j] + px[j + 1] + px[j + 2]) / 3 - mean) * 2.2)));
          d.data[i * 4] = d.data[i * 4 + 1] = d.data[i * 4 + 2] = v;
          d.data[i * 4 + 3] = 255;
        }
        fg.putImageData(d, 0, 0);
        out.push(`url(${f.toDataURL()})`);
      }
      resolve(out);
    };
    img.onerror = () => resolve(null);
    img.src = '/api/water.png';
  });
  return waterFrames;
}
function startWaterClock(layer, frames) {
  clearInterval(waterTimer);
  const box = layer.getContainer();
  const tick = () => {
    if (!box.isConnected) { clearInterval(waterTimer); return; }
    if (document.hidden) return;
    box.style.setProperty('--wf', frames[Math.floor(performance.now() / WATER_FRAME_MS) % frames.length]);
  };
  tick();
  waterTimer = setInterval(tick, WATER_FRAME_MS);
}
const WaterLayer = RegionLayer.extend({
  tileUrl(c) { return RegionLayer.prototype.tileUrl.call(this, c) + '&water=1'; },
  createTile(coords, done) {
    const div = document.createElement('div');
    div.className = 'water-tile';
    div.innerHTML = '<i></i>';
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
  clearInterval(waterTimer);
  const on = $('#show-water') ? $('#show-water').checked : true;
  if (!on || state.dim === 'the_nether' || state.dim === 'the_end' || !(state.layer || '').startsWith('surface')) return;
  const layer = new WaterLayer({ tileSize: 512, minZoom: WATER_MIN_ZOOM, maxZoom: MAX_ZOOM, maxNativeZoom: 0, minNativeZoom: 0, keepBuffer: 1, zIndex: 2, className: 'water-layer' });
  water = layer;
  loadWaterFrames().then((frames) => {
    if (water !== layer || !frames) return;
    layer.addTo(map);
    startWaterClock(layer, frames);
  });
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

if ($('#soft-shade')) {
  $('#soft-shade').checked = prefs.soft !== false;
  $('#soft-shade').addEventListener('change', (e) => {
    savePref('soft', e.target.checked);
    rebuildTiles();
  });
}
if ($('#show-water')) {
  $('#show-water').checked = prefs.water !== false;
  $('#show-water').addEventListener('change', (e) => {
    savePref('water', e.target.checked);
    rebuildWater();
  });
}

export { rebuildTiles, renderSelectors, select, tiles };
