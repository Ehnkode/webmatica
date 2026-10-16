/* Webmatica map UI: Schematic area selection (2D and 3D) and the player trail. */
import './player.js';   // runs before this file (load order, see index.html)
import { $, api, fmt, fromLatLng, map, savePref, state, toast, toLatLng } from './core.js';
import { mode3d, setFollow, view3d } from './player.js';
import { openEditor } from './schematics.js';
import { drawFarmArea, farmUi, pick3dStop } from './farm.js';

// ------------------------------------------------------------------ Schematic selection

const DIM_Y = { overworld: [-64, 319], the_nether: [0, 127], the_end: [0, 255] };
const sel = { on: false, start: null, rect: null, box: null, corner: null };

function setSelect(on) {
  if (on && farmUi.pick3d) pick3dStop();
  sel.on = on;
  $('#btn-select').classList.toggle('on', on);
  document.body.classList.toggle('selecting', on && !mode3d);
  $('#sel-panel').hidden = !on;
  if (on) {
    if (state.follow) setFollow(false);
    const [y0, y1] = DIM_Y[state.dim] || [-64, 319];
    $('#sel-y0').value = y0;
    $('#sel-y1').value = y1;
    if (mode3d && view3d) sel3dStart();
    else { map.dragging.disable(); $('#sel-hint').textContent = 'Auf der Karte ein Rechteck aufziehen.'; }
  } else {
    map.dragging.enable();
    if (sel.rect) { sel.rect.remove(); sel.rect = null; }
    sel.box = null;
    sel.start = null;
    if (sel.corner !== null && view3d) { view3d.setBlockPick(null); sel.corner = null; drawFarmArea(); }
  }
  updateSel();
}

/* In 3D like WorldEdit/Axiom: left click sets the first corner, right click the second,
 * Alt+click takes the air block in front of the face. The mouse wheel pushes the side of the
 * box you look at outwards (up) or back in (down), Ctrl = 8 blocks at a time. */
const HINT3D = 'Linksklick: erste Ecke · Rechtsklick: zweite Ecke · Alt+Klick: Luftblock davor · Mausrad: Auswahl in Blickrichtung vergrößern/verkleinern (Strg = 8)';
function sel3dStart() {
  sel.corner = undefined;
  sel.p1 = sel.p2 = null;
  $('#sel-hint').textContent = HINT3D;
  view3d.setBlockPick((b, how) => {
    if (!sel.on) return;
    if (how && how.button === 2) sel.p2 = b; else sel.p1 = b;
    const a = sel.p1 || sel.p2, c = sel.p2 || sel.p1;
    sel.box = { x0: Math.min(a.x, c.x), z0: Math.min(a.z, c.z), x1: Math.max(a.x, c.x), z1: Math.max(a.z, c.z) };
    $('#sel-y0').value = Math.min(a.y, c.y);
    $('#sel-y1').value = Math.max(a.y, c.y);
    showBox();
  }, wheelSel);
}

/* Mouse wheel in 3D: move the face of the box that faces the view direction */
function wheelSel(step, look, e) {
  const b = sel.box;
  if (!b) return false;
  const n = step * (e.ctrlKey ? 8 : 1);
  const ax = Math.abs(look.x), ay = Math.abs(look.y), az = Math.abs(look.z);
  let y0 = Math.min(Number($('#sel-y0').value), Number($('#sel-y1').value));
  let y1 = Math.max(Number($('#sel-y0').value), Number($('#sel-y1').value));
  const [lo, hi] = DIM_Y[state.dim] || [-64, 319];
  if (ay >= ax && ay >= az) {
    if (look.y > 0) y1 = Math.max(y0, Math.min(hi, y1 + n)); else y0 = Math.min(y1, Math.max(lo, y0 - n));
  } else if (ax >= az) {
    if (look.x > 0) b.x1 = Math.max(b.x0, b.x1 + n); else b.x0 = Math.min(b.x1, b.x0 - n);
  } else if (look.z > 0) b.z1 = Math.max(b.z0, b.z1 + n);
  else b.z0 = Math.min(b.z1, b.z0 - n);
  $('#sel-y0').value = y0;
  $('#sel-y1').value = y1;
  cornersFromBox();
  showBox();
  return true;
}
function cornersFromBox() {
  const b = sel.box;
  if (!b) return;
  const y0 = Number($('#sel-y0').value), y1 = Number($('#sel-y1').value);
  sel.p1 = { x: b.x0, y: Math.min(y0, y1), z: b.z0 };
  sel.p2 = { x: b.x1, y: Math.max(y0, y1), z: b.z1 };
}

/* Draw the box (2D rectangle or 3D frame) and fill the coordinate fields */
function showBox() {
  const b = sel.box;
  if (b) {
    $('#sel-x0').value = b.x0; $('#sel-x1').value = b.x1;
    $('#sel-z0').value = b.z0; $('#sel-z1').value = b.z1;
    if (mode3d && view3d) {
      const y0 = Number($('#sel-y0').value), y1 = Number($('#sel-y1').value);
      view3d.setArea({ ...b, y0: Math.min(y0, y1), y1: Math.max(y0, y1) });
    } else {
      const bounds = L.latLngBounds(toLatLng(b.x0, b.z0), toLatLng(b.x1 + 1, b.z1 + 1));
      if (sel.rect) sel.rect.setBounds(bounds);
      else sel.rect = L.rectangle(bounds, { color: '#22a06b', weight: 2, fillOpacity: 0.12, interactive: false }).addTo(map);
    }
  }
  updateSel();
}

function updateSel() {
  const b = sel.box;
  $('#sel-go').disabled = !b;
  $('#sel-edit').disabled = !b;
  $('#sel-farm').disabled = !b;
  for (const k of ['x0', 'x1', 'z0', 'z1']) $('#sel-' + k).disabled = !b;
  if (!b) {
    $('#sel-size').textContent = '';
    $('#sel-hint').hidden = false;
    for (const k of ['x0', 'x1', 'z0', 'z1']) $('#sel-' + k).value = '';
    return;
  }
  $('#sel-hint').hidden = !mode3d;
  const y0 = Number($('#sel-y0').value), y1 = Number($('#sel-y1').value);
  const w = b.x1 - b.x0 + 1, l = b.z1 - b.z0 + 1, h = Math.abs(y1 - y0) + 1;
  $('#sel-size').textContent = `${fmt(w)} × ${fmt(h)} × ${fmt(l)}`;
  $('#sel-panel').title = `${b.x0} ${b.z0}  bis  ${b.x1} ${b.z1}`;
}

function drawSel(a, b) {
  sel.box = { x0: Math.min(a.x, b.x), z0: Math.min(a.z, b.z), x1: Math.max(a.x, b.x), z1: Math.max(a.z, b.z) };
  showBox();
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
      try { msg = (await r.json()).error || msg; } catch (_) { /* ignore */ }
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
    btn.textContent = 'Herunterladen';
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
/* Typed coordinates: X/Z move the box sides, Y the height (2D and 3D) */
function selFieldsChanged() {
  const b = sel.box;
  if (b) {
    const num = (k, d) => { const v = $('#sel-' + k).value; return v === '' || !Number.isFinite(Number(v)) ? d : Math.round(Number(v)); };
    const x0 = num('x0', b.x0), x1 = num('x1', b.x1), z0 = num('z0', b.z0), z1 = num('z1', b.z1);
    sel.box = { x0: Math.min(x0, x1), x1: Math.max(x0, x1), z0: Math.min(z0, z1), z1: Math.max(z0, z1) };
    cornersFromBox();
    const keep = document.activeElement;
    showBox();
    if (keep && keep.id && keep.id.startsWith('sel-')) {
      // don't swap the typed value while typing (x0 > x1 for a moment)
      $('#sel-x0').value = x0; $('#sel-x1').value = x1; $('#sel-z0').value = z0; $('#sel-z1').value = z1;
    }
  } else updateSel();
}
for (const k of ['x0', 'x1', 'z0', 'z1', 'y0', 'y1']) $('#sel-' + k).addEventListener('input', selFieldsChanged);

// ------------------------------------------------------------------ Trail

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
  // Break the line on jumps (teleport, gap in the recording)
  const parts = [[]];
  pts.forEach((q, i) => {
    if (i && Math.hypot(q[0] - pts[i - 1][0], q[2] - pts[i - 1][2]) > 120) parts.push([]);
    parts[parts.length - 1].push(toLatLng(q[0], q[2]));
  });
  trail.line = L.polyline(parts, { color: '#ffd23f', weight: 2, opacity: 0.85, interactive: false }).addTo(map);
}

export { DIM_Y, extendTrail, loadTrail, sel, setSelect, setTrail, trail };
