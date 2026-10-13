/* Webmatica map UI: Schematics: library, placement, 2D drag, editor link, Litematica bridge. */
'use strict';

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

/* Redraw everything that depends on the list. */
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

/* Editor tab: new, from the world, upload, open existing */
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

/* Where does a new schematic land? 3D: in front of the camera. Map: map center on the ground. */
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
  } catch (_) { /* default height */ }
  return { x: c.x, y, z: c.z };
}

async function placeFile(file) {
  try {
    const pos = await placeTarget();
    const pl = await jsonPost('/api/schematics/place', { file, server: state.server, dim: state.dim, ...pos });
    // Put the center instead of the corner on the target
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

/* Editor in a new tab: file (made editable as a copy if needed) or placement */
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
// Back from the editor: reload changed files
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

/* Show changes immediately, save in batches. */
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

/* Summary below the big tabs of the schematic window */
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

// --- 2D: rectangles, click and drag

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
      // otherwise the label doesn't move along while dragging
      if (r.getTooltip()) r.getTooltip().setLatLng(b.getCenter());
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

/* Refresh the Litematica bridge status while something is "in game". */
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
  } catch (_) { /* viewer briefly gone */ }
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

/* Keyboard while a schematic is selected. Returns true if the key was consumed. */
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
