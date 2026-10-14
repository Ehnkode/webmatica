/* Webmatica map UI: Timeline, timelapse overlay and Y layer cut. */
import './gif.js';   // runs before this file (load order, see index.html)
import {
  $, $$, api, esc, fmt, map, MAX_ZOOM, MIN_ZOOM, prefs, savePref, state, toast, toLatLng,
} from './core.js';
import { rebuildTiles, renderSelectors } from './world.js';
import { loadMarkers } from './places.js';
import { mode3d, saveHash, setFollow, view3d } from './player.js';
import { DIM_Y } from './selection.js';
import { encodeGif } from './gif.js';

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

/* Timelapse: "state on the morning of day X". On the map as overlay tiles (changed chunks only),
 * in 3D as chunk meshes of that day. Frames = the backed-up days in the chosen range (every day
 * or one per week), "heute" as the last frame. */
var tl = { layer: null, idx: 0, frames: [], run: 0, date: null };
const dayLabel = (d) => d.split('-').reverse().join('.');
const tlDay = (d) => Date.parse(d + 'T00:00:00Z') / 86400000;

function tlFrames() {
  const from = $('#tl-from').value, to = $('#tl-to').value;
  const step = Number($('#tl-step').value) || 1;
  const out = [];
  for (const d of hist.dates) {
    if ((from && d < from) || (to && d > to)) continue;
    if (out.length && tlDay(d) - tlDay(out[out.length - 1]) < step) continue;
    out.push(d);
  }
  if (!to) out.push(null);   // today
  return out;
}

function tlReset() {
  tlStop();
  const n = hist.dates.length;
  const opts = (sel) => hist.dates.map((d) => `<option value="${d}"${d === sel ? ' selected' : ''}>${dayLabel(d)}</option>`).join('');
  $('#tl-from').innerHTML = opts(hist.dates[0]);
  $('#tl-to').innerHTML = opts(null) + '<option value="" selected>heute</option>';
  for (const id of ['#tl-from', '#tl-to', '#tl-step', '#tl-speed', '#tl-play', '#tl-range', '#tl-gif', '#tl-video']) $(id).disabled = !n;
  $('#tl-hint').textContent = n ? 'Schieb nach links, um zu sehen, wie es am Morgen dieses Tages aussah. Läuft auf der Karte und in 3D.'
    : 'Noch keine früheren Stände. Die Mod sichert sie, sobald sich ein Chunk an einem neuen Tag ändert.';
  tlRange(true);
}

// Range or step changed: new frame list, slider back to the end (today or the last day)
function tlRange(toEnd) {
  tl.frames = tlFrames();
  const r = $('#tl-range');
  r.max = Math.max(0, tl.frames.length - 1);
  tlShow(toEnd ? tl.frames.length - 1 : Math.min(tl.idx, tl.frames.length - 1));
}

function tlShow(i) {
  const n = tl.frames.length;
  tl.idx = Math.max(0, Math.min(n - 1, i));
  $('#tl-range').value = tl.idx;
  const date = n ? tl.frames[tl.idx] : null;
  tl.date = date;
  $('#tl-label').textContent = date ? dayLabel(date) : 'heute';
  $('#tl-badge').hidden = !date;
  $('#tl-badge').textContent = date ? `Stand ${dayLabel(date)} · zurück zu heute ✕` : '';
  if (view3d) view3d.setAt(date);
  const old = tl.layer;
  tl.layer = null;
  if (!date) { if (old) map.removeLayer(old); return; }
  const url = `/api/tile/${encodeURIComponent(state.server)}/${encodeURIComponent(state.dim)}/${state.layer}/{z}/{x}/{y}.png?at=${date}`;
  const layer = L.tileLayer(url, { tileSize: 512, minZoom: MIN_ZOOM, maxZoom: MAX_ZOOM, maxNativeZoom: 0, minNativeZoom: MIN_ZOOM, keepBuffer: 1 });
  // remove the old state only once the new one has loaded (no flicker during playback)
  layer.once('load', () => { layer._tlLoaded = true; if (old) map.removeLayer(old); });
  layer.addTo(map);
  tl.layer = layer;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Wait until the shown frame is complete: map tiles loaded or, in 3D, the chunks near the camera
async function tlSettled(run, maxMs = 20000) {
  const t0 = performance.now();
  await sleep(60);
  while (tl.run === run && performance.now() - t0 < maxMs) {
    const busy = mode3d ? view3d && view3d.busy() : tl.layer && !tl.layer._tlLoaded;
    if (!busy) return;
    await sleep(100);
  }
}

function tlStop() {
  tl.run++;
  $('#tl-play').textContent = '▶'; $('#tl-play').title = 'Abspielen';
}

async function tlPlay() {
  const run = ++tl.run;
  $('#tl-play').textContent = '❚❚'; $('#tl-play').title = 'Anhalten';
  if (tl.idx >= tl.frames.length - 1) tlShow(0);
  while (tl.run === run) {
    const t0 = performance.now();
    await tlSettled(run);
    await sleep(Math.max(0, Number($('#tl-speed').value) - (performance.now() - t0)));
    if (tl.run !== run) return;
    if (tl.idx >= tl.frames.length - 1) {
      if (!$('#tl-loop').checked) { tlStop(); return; }
      tlShow(0);
    } else tlShow(tl.idx + 1);
  }
}

$('#tl-range').addEventListener('input', (e) => { tlStop(); tlShow(Number(e.target.value)); });
for (const id of ['#tl-from', '#tl-to', '#tl-step']) $(id).addEventListener('change', () => { tlStop(); tlRange(true); });
$('#tl-play').addEventListener('click', () => { if ($('#tl-play').title === 'Anhalten') tlStop(); else tlPlay(); });
$('#tl-badge').addEventListener('click', () => { tlStop(); tlShow(tl.frames.length - 1); if (tl.date) { $('#tl-to').value = ''; tlRange(true); } });

/* Export: plays the range once and records every frame (map: the visible tiles, 3D: the picture). */
const TL_MAX_W = 960;
function tlCapture() {
  let src, w, h;
  if (mode3d) { src = view3d.canvas(); w = src.width; h = src.height; } else { const r = $('#map').getBoundingClientRect(); w = r.width; h = r.height; }
  const k = Math.min(1, TL_MAX_W / w);
  const cv = document.createElement('canvas');
  cv.width = Math.round(w * k) & ~1; cv.height = Math.round(h * k) & ~1;
  const g = cv.getContext('2d');
  g.imageSmoothingEnabled = mode3d;
  if (mode3d) g.drawImage(src, 0, 0, cv.width, cv.height);
  else {
    g.fillStyle = getComputedStyle($('#map')).backgroundColor || '#0e1014';
    g.fillRect(0, 0, cv.width, cv.height);
    const base = $('#map').getBoundingClientRect();
    for (const img of $$('#map .leaflet-tile-pane img.leaflet-tile-loaded')) {
      const r = img.getBoundingClientRect();
      const op = Number(getComputedStyle(img.closest('.leaflet-layer') || img).opacity || 1);
      g.globalAlpha = op;
      try { g.drawImage(img, (r.left - base.left) * k, (r.top - base.top) * k, r.width * k, r.height * k); } catch (_) { /* not loaded */ }
    }
    g.globalAlpha = 1;
  }
  // date in the corner
  const label = tl.date ? dayLabel(tl.date) : 'heute';
  const fs = Math.max(14, Math.round(cv.height / 24));
  g.font = `600 ${fs}px system-ui, sans-serif`;
  const tw = g.measureText(label).width;
  g.fillStyle = 'rgba(14,16,20,0.75)';
  g.fillRect(fs * 0.6, cv.height - fs * 2.3, tw + fs * 1.2, fs * 1.7);
  g.fillStyle = '#f5a524';
  g.fillText(label, fs * 1.2, cv.height - fs * 1.05);
  return cv;
}

async function tlExport(kind) {
  if (!tl.frames.length) return;
  tlStop();
  const run = ++tl.run;
  const btn = $(kind === 'gif' ? '#tl-gif' : '#tl-video');
  const label = btn.textContent;
  $('#tl-gif').disabled = $('#tl-video').disabled = true;
  const delay = Number($('#tl-speed').value);
  try {
    const shots = [];
    for (let i = 0; i < tl.frames.length; i++) {
      btn.textContent = `Bild ${i + 1} von ${tl.frames.length} …`;
      tlShow(i);
      await tlSettled(run, 30000);
      if (tl.run !== run) return;
      await sleep(mode3d ? 300 : 150);   // one more frame drawn
      shots.push(tlCapture());
    }
    const name = `webmatica-zeitraffer-${tl.frames[0] || 'heute'}-${tl.frames[tl.frames.length - 1] || 'heute'}`;
    let blob, ext;
    if (kind === 'gif') {
      btn.textContent = 'GIF wird erstellt …';
      await sleep(30);
      const frames = shots.map((c) => c.getContext('2d').getImageData(0, 0, c.width, c.height));
      const delays = frames.map((_, i) => (i === frames.length - 1 ? delay * 2 : delay));
      blob = encodeGif(frames, delays); ext = 'gif';
    } else {
      btn.textContent = 'Video wird aufgenommen …';
      ({ blob, ext } = await tlRecord(shots, delay));
    }
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = `${name}.${ext}`;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 30000);
    toast(`Gespeichert: ${name}.${ext} (${(blob.size / 1048576).toFixed(1)} MB)`);
  } catch (e) {
    toast('Export fehlgeschlagen: ' + e.message);
  } finally {
    btn.textContent = label;
    $('#tl-gif').disabled = $('#tl-video').disabled = !hist.dates.length;
  }
}

// Video: draws the frames onto a canvas in real time and records it (MP4 where the browser can, otherwise WebM)
async function tlRecord(shots, delay) {
  if (!window.MediaRecorder) throw new Error('Dein Browser kann keine Videos aufnehmen, nimm GIF');
  const type = ['video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm'].find((t) => MediaRecorder.isTypeSupported(t));
  const cv = document.createElement('canvas');
  cv.width = shots[0].width; cv.height = shots[0].height;
  const g = cv.getContext('2d');
  g.drawImage(shots[0], 0, 0);
  const stream = cv.captureStream(30);
  const rec = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 6e6 });
  const parts = [];
  rec.ondataavailable = (e) => e.data.size && parts.push(e.data);
  const done = new Promise((r) => { rec.onstop = r; });
  rec.start();
  for (let i = 0; i < shots.length; i++) {
    g.drawImage(shots[i], 0, 0);
    await sleep(i === shots.length - 1 ? delay * 2 : delay);
  }
  rec.stop();
  await done;
  stream.getTracks().forEach((t) => t.stop());
  return { blob: new Blob(parts, { type: type.split(';')[0] }), ext: type.startsWith('video/mp4') ? 'mp4' : 'webm' };
}
$('#tl-gif').addEventListener('click', () => tlExport('gif'));
$('#tl-video').addEventListener('click', () => tlExport('video'));

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

export { cut, cutRange, drawHistory, loadHistoryDates, setCut, tl, tlShow };
