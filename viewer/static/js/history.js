/* Webmatica map UI: Timeline, timelapse overlay and Y layer cut. */
'use strict';

// ------------------------------------------------------------------ Timeline

var hist = { dates: [], res: null, layer: L.layerGroup().addTo(map) };

async function loadHistoryDates() {
  if (!state.server) return;
  try { hist.dates = await api('/api/history/dates?' + new URLSearchParams({ srv: state.server, dim: state.dim })); } catch (_) { hist.dates = []; }
  const sel = $('#hist-date');
  sel.innerHTML = hist.dates.length ? hist.dates.slice().reverse().map((d) => `<option value="${d}">seit ${d.split('-').reverse().join('.')}</option>`).join('')
    : '<option value="">noch kein früherer Stand</option>';
  $('#hist-run').disabled = !hist.dates.length;
  tlReset();
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

/* Timelapse: overlay tiles "state on the morning of day X" (changed chunks only) on the map */
var tl = { layer: null, idx: 0, timer: null };
const dayLabel = (d) => d.split('-').reverse().join('.');
function tlReset() {
  tlStop();
  const n = hist.dates.length;
  $('#tl-range').max = n; $('#tl-range').value = n;
  $('#tl-range').disabled = $('#tl-play').disabled = !n;
  $('#tl-hint').textContent = n ? 'Schieb nach links, um die Karte zu sehen, wie sie am Morgen dieses Tages aussah. Nur auf der 2D-Karte.'
    : 'Noch keine früheren Stände. Die Mod sichert sie, sobald sich ein Chunk an einem neuen Tag ändert.';
  tlShow(n);
}
function tlShow(i) {
  const n = hist.dates.length;
  tl.idx = Math.max(0, Math.min(n, i));
  $('#tl-range').value = tl.idx;
  const date = tl.idx < n ? hist.dates[tl.idx] : null;
  $('#tl-label').textContent = date ? dayLabel(date) : 'heute';
  $('#tl-badge').hidden = !date;
  $('#tl-badge').textContent = date ? `Stand ${dayLabel(date)} · zurück zu heute ✕` : '';
  const old = tl.layer;
  tl.layer = null;
  if (!date) { if (old) map.removeLayer(old); return; }
  const url = `/api/tile/${encodeURIComponent(state.server)}/${encodeURIComponent(state.dim)}/${state.layer}/{z}/{x}/{y}.png?at=${date}`;
  const layer = L.tileLayer(url, { tileSize: 512, minZoom: MIN_ZOOM, maxZoom: MAX_ZOOM, maxNativeZoom: 0, minNativeZoom: MIN_ZOOM, keepBuffer: 1 });
  // remove the old state only once the new one has loaded (no flicker during playback)
  if (old) layer.once('load', () => map.removeLayer(old));
  layer.addTo(map);
  tl.layer = layer;
}
function tlStop() {
  clearInterval(tl.timer); tl.timer = null;
  $('#tl-play').textContent = '▶'; $('#tl-play').title = 'Abspielen';
}
$('#tl-range').addEventListener('input', (e) => { tlStop(); tlShow(Number(e.target.value)); });
$('#tl-play').addEventListener('click', () => {
  if (tl.timer) { tlStop(); return; }
  if (mode3d) toast('Der Zeitraffer läuft auf der 2D-Karte');
  if (tl.idx >= hist.dates.length) tlShow(0);
  $('#tl-play').textContent = '❚❚'; $('#tl-play').title = 'Anhalten';
  tl.timer = setInterval(() => {
    if (tl.idx >= hist.dates.length) { tlStop(); return; }
    tlShow(tl.idx + 1);
  }, 1500);
});
$('#tl-badge').addEventListener('click', () => { tlStop(); tlShow(hist.dates.length); });

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

// ------------------------------------------------------------------ Layers (Y cut)

/* Map: topmost block below Y (its own tile layer "surface<Y>").
 * 3D: everything above is cut away, with "Nur Ebene" (single level) everything below too. */
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
  $('#btn-cut').classList.toggle('on', mode !== 'off');
  $('#cut-label').textContent = mode === 'off' ? '' : (mode === 'layer' ? '=' : '≤') + cut.y;
  if (view3d) view3d.setClip(mode, cut.y);
  // Map: request tiles for the new height only after a short pause
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
  // When switching on: height below the player or 3D camera as the start value
  if (cut.mode === 'off' && e.target.value !== 'off') {
    if (mode3d && view3d) y = Math.floor(view3d.position().y) - 2;
    else if (state.player && state.player.active && state.player.dim === state.dim) y = Math.floor(state.player.y) + 1;
  }
  setCut(e.target.value, y);
});
$('#cut-y').addEventListener('input', (e) => setCut(cut.mode, Number(e.target.value)));
$('#cut-yn').addEventListener('change', (e) => setCut(cut.mode, Number(e.target.value)));
