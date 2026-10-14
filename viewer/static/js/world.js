/* Webmatica map UI: the shown world, dimension and layer (selector buttons, map tiles). */
'use strict';

let tiles = null;

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
