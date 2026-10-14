/* Webmatica map UI: Wrong blocks, build queue, farms, automations, 3D area picking. */
'use strict';

// ------------------------------------------------------------------ Wrong blocks, queue, farms

const gameSrv = () => (state.player && state.player.server) || state.server;

$('#chk-out').addEventListener('change', (e) => { if (e.target.id === 'chk-fix-extra') schem.fixExtra = e.target.checked; });
/* Breaking wrong blocks: first show exactly these blocks in 3D, then confirm with the number */
function fixPreview(on) {
  const pl = selPl(), c = schem.check;
  schem.fixArm = on;
  if (view3d && pl && c) {
    const p = c.points;
    view3d.setVerify(pl.id, on ? { wrong: p.wrong, rotated: p.rotated, extra: schem.fixExtra ? p.extra : [] }
      : ($('#chk-show').checked ? p : null));
  }
  renderCheck();
}
$('#chk-out').addEventListener('click', async (e) => {
  if (e.target.id === 'chk-todo-all') { schem.todoAll = !schem.todoAll; renderCheck(); return; }
  if (e.target.id === 'chk-fix-cancel') { fixPreview(false); return; }
  const pl = selPl(), c = schem.check;
  if (!pl || !c) return;
  if (e.target.id === 'chk-fix') {
    if (!mode3d) await set3d(true);
    gotoSchem(pl);
    fixPreview(true);
    return;
  }
  if (e.target.id !== 'chk-fix-go') return;
  fixPreview(false);
  try {
    const r = await api('/api/fix', { method: 'POST', body: JSON.stringify({ srv: gameSrv(), pid: pl.id, extra: !!schem.fixExtra }) });
    toast(r.n ? `Baritone baut ${fmt(r.n)} Blöcke ab` : 'Nichts abzubauen');
  } catch (err) { toast('Abbauen: ' + err.message); }
});

/* Queue: after "done" auto-build builds the next schematic */
function renderQueue(a, running) {
  const q = (a && a.queue) || [];
  const pl = selPl();
  $('#q-sum').textContent = q.length ? `· ${q.length}` : '';
  $('#q-hint').hidden = q.length > 0;
  $('#q-list').innerHTML = q.map((id, i) => {
    const p = schem.list.find((x) => x.id === id);
    return `<li data-q="${i}"><span>${esc(p ? p.name : 'gelöscht')}</span>${i ? '<button data-qup title="Nach oben">↑</button>' : ''}<button data-qdel title="Entfernen">✕</button></li>`;
  }).join('');
  $('#q-add').disabled = !pl || q.includes(pl.id) || (running && a.pid === pl.id);
  $('#q-start').hidden = running || !q.length;
}
async function saveQueue(q) {
  try { ctl.auto = await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv: gameSrv(), queue: q }) }); } catch (e) { toast('Warteschlange: ' + e.message); }
  renderAuto();
}
$('#q-add').addEventListener('click', () => {
  const pl = selPl();
  const q = ((ctl.auto && ctl.auto.queue) || []).slice();
  if (pl && !q.includes(pl.id)) { q.push(pl.id); saveQueue(q); toast(`${pl.name} eingereiht`); }
});
$('#q-list').addEventListener('click', (e) => {
  const li = e.target.closest('[data-q]');
  if (!li) return;
  const i = Number(li.dataset.q), q = ((ctl.auto && ctl.auto.queue) || []).slice();
  if (e.target.closest('[data-qdel]')) q.splice(i, 1);
  else if (e.target.closest('[data-qup]')) [q[i - 1], q[i]] = [q[i], q[i - 1]];
  else return;
  saveQueue(q);
});
$('#q-start').addEventListener('click', async () => {
  if (!confirm('Warteschlange starten?\n\nDer Autobau baut eine Schematic nach der anderen, jeweils mit Material aus deinen Kisten.')) return;
  try { ctl.auto = await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv: gameSrv(), startQueue: true }) }); } catch (e) { toast('Warteschlange: ' + e.message); }
  renderAuto();
});

/* Farms: area on the map, blocks with amounts, where to put a full inventory */
const farmUi = { box: null, dim: null, blocks: [], amounts: {}, rect: null, timer: null, auto: null, mode: 'farm', flat: null, inv: null };
const FARM_RUN = ['fplan', 'mine', 'deposit'];

function farmArea() {
  const b = farmUi.box;
  if (!b) return null;
  const snap = $('#fm-snap').checked;
  const a = snap ? { x0: Math.floor(b.x0 / 16) * 16, z0: Math.floor(b.z0 / 16) * 16, x1: Math.floor(b.x1 / 16) * 16 + 15, z1: Math.floor(b.z1 / 16) * 16 + 15 } : { ...b };
  a.y0 = Number($('#fm-y0').value); a.y1 = Number($('#fm-y1').value);
  return a;
}
function drawFarmArea() {
  const a = (farmUi.auto && farmUi.auto.farm && FARM_RUN.includes(farmUi.auto.phase)) ? farmUi.auto.farm.area : farmArea();
  const visible = farmRunning() || (!$('#side').hidden && sideTab === 'farm') || sel.on;
  const show = a && visible && (farmUi.dim || state.dim) === state.dim;
  // during a 3D selection the frame shows the selection
  if (view3d && view3d.setArea && !farmUi.pick3d && !(sel.on && mode3d)) view3d.setArea(show ? a : null);
  if (!show) { if (farmUi.rect) { farmUi.rect.remove(); farmUi.rect = null; } return; }
  const bounds = L.latLngBounds(toLatLng(a.x0, a.z0), toLatLng(a.x1 + 1, a.z1 + 1));
  if (farmUi.rect) farmUi.rect.setBounds(bounds);
  else farmUi.rect = L.rectangle(bounds, { color: '#f5a524', weight: 2, dashArray: '6 4', fillOpacity: 0.08, interactive: false }).addTo(map);
}
function setFarmBox(box, y0, y1) {
  farmUi.box = box;
  farmUi.dim = state.dim;
  $('#fm-y0').value = y0;
  $('#fm-y1').value = y1;
  farmAreaChanged();
}
let farmScanT = null;
function farmAreaChanged() {
  const a = farmArea();
  if (!a) { $('#fm-area').textContent = 'noch keiner'; return; }
  const cw = (a.x1 - a.x0 + 1) / 16, cl = (a.z1 - a.z0 + 1) / 16;
  $('#fm-area').textContent = `${a.x0} ${a.z0} bis ${a.x1} ${a.z1}` + ($('#fm-snap').checked ? ` · ${cw * cl} Chunks` : '');
  drawFarmArea();
  fillFarmChests();
  if ($('#fl-level').value === '') $('#fl-level').value = defaultLevel(a);
  clearTimeout(farmScanT);
  farmScanT = setTimeout(farmUi.mode === 'farm' ? scanFarm : scanFlat, 300);
}
async function scanFarm() {
  const a = farmArea();
  if (!a) return;
  $('#fm-blocks-hint').hidden = false;
  $('#fm-blocks-hint').textContent = 'Zählt Blöcke …';
  try {
    const r = await api('/api/farm/scan?' + new URLSearchParams({ srv: state.server, dim: farmUi.dim || state.dim, ...a }));
    farmUi.blocks = r.blocks;
    $('#fm-blocks-hint').textContent = r.chunks ? '' : 'In diesem Bereich ist nichts aufgezeichnet. Einmal mit der Mod hinfliegen.';
    $('#fm-blocks-hint').hidden = !!r.chunks;
  } catch (e) { farmUi.blocks = []; $('#fm-blocks-hint').textContent = e.message; }
  $('#fm-filter').hidden = !farmUi.blocks.length;
  renderFarmBlocks();
}
function renderFarmBlocks() {
  const f = $('#fm-filter').value.trim().toLowerCase().replace(/ /g, '_');
  const mined = (farmUi.auto && farmUi.auto.mode === 'farm' && farmUi.auto.farm && farmUi.auto.farm.mined) || {};
  const rows = farmUi.blocks.filter(([id]) => !f || id.includes(f) || farmUi.amounts[id]);
  $('#fm-blocks').innerHTML = rows.map(([id, n]) => {
    const want = farmUi.amounts[id] || '';
    const got = mined[id];
    return `<tr class="${want ? 'on' : ''}"><td>${itemIcon(id, 20)} ${esc(itemName(id))}</td><td class="n muted" title="im Bereich">${fmt(n)}</td>
      <td class="fm-amt">${got != null && want ? `<span class="mono ${got >= want ? 'ok' : ''}">${fmt(got)} /</span>` : ''}<input type="number" min="0" step="64" placeholder="Menge" data-fm="${esc(id)}" value="${want}"></td></tr>`;
  }).join('');
  const sel = Object.entries(farmUi.amounts).filter(([, n]) => n > 0);
  $('#fm-sum').textContent = sel.length ? `· ${sel.length} gewählt` : '';
  renderFarmGo();
}
function fillFarmChests() {
  const a = farmArea(), cur = $('#fm-chest').value;
  const cx = a ? (a.x0 + a.x1) / 2 : 0, cz = a ? (a.z0 + a.z1) / 2 : 0;
  const list = (chests.list || []).filter((c) => c.dim === (farmUi.dim || state.dim) && !String(c.block || '').endsWith('shulker_box'))
    .map((c) => ({ c, d: Math.hypot(c.x - cx, c.z - cz) })).sort((p, q) => p.d - q.d).slice(0, 20);
  $('#fm-chest').innerHTML = '<option value="">Kiste aus dem Inventar aufstellen</option>' +
    list.map(({ c, d }) => `<option value="${esc(c.key)}">${esc(c.title || chestWhere(c))} · ${fmt(d)} m</option>`).join('');
  $('#fm-chest').value = list.some(({ c }) => c.key === cur) ? cur : '';
}
function farmRunning() { const a = farmUi.auto; return !!(a && (a.mode === 'farm' || a.mode === 'flatten') && FARM_RUN.includes(a.phase)); }
function flatReady() {
  const m = farmUi.mode, block = !!$('#fl-block').value.trim();
  if (m === 'clear') return true;
  if (m === 'fill' || m === 'light') return block;
  const fill = $('#fl-fill').checked;
  return $('#fl-level').value !== '' && ($('#fl-clear').checked || fill) && (!fill || block);
}
// Automations: name per mode (start/stop button, confirmations)
const AUTO_NAME = { farm: 'Farmen', clear: 'Abbauen', fill: 'Füllen', flat: 'Ebnen', light: 'Ausleuchten' };
function runningKind() {
  const a = farmUi.auto;
  if (!a) return null;
  return a.mode === 'farm' ? 'farm' : (a.farm && a.farm.flatten && a.farm.flatten.kind) || 'flat';
}
function renderFarmGo() {
  const run = farmRunning();
  const m = farmUi.mode;
  const ready = farmUi.box && (m === 'farm' ? Object.values(farmUi.amounts).some((n) => n > 0) : flatReady());
  $('#fm-go').textContent = run ? `■ ${AUTO_NAME[runningKind()]} stoppen` : `▶ ${AUTO_NAME[m]} starten`;
  $('#fm-go').classList.toggle('danger', run);
  const items = gameChecks(false);
  items.push(farmUi.box ? { ok: true, text: 'Bereich gewählt' } : { ok: false, text: 'Noch kein Bereich gewählt (oben unter 1)' });
  const gameOk = renderChecklist($('#fm-check'), items);
  $('#fm-check').hidden = run;
  $('#fm-go').disabled = !run && !(ready && gameOk);
  $('#fm-go').title = run || ready ? (gameOk ? '' : 'Erst die rot markierten Punkte oben erledigen') : m === 'farm' ? 'Erst Bereich wählen und bei mindestens einem Block eine Menge eintragen'
    : m === 'clear' ? 'Erst einen Bereich wählen' : m === 'fill' ? 'Erst Bereich wählen und einen Füllblock angeben' : m === 'light' ? 'Erst Bereich wählen und Fackeln angeben' : 'Erst Bereich wählen, Höhe eintragen und einen Füllblock angeben';
}
function setFarmMode(m) {
  farmUi.mode = m;
  const farm = m === 'farm';
  $$('.fm-mode button').forEach((b) => b.classList.toggle('on', b.dataset.fmmode === m));
  $$('.fm-hint').forEach((h) => { h.hidden = h.dataset.for !== m; });
  $('#fm-card').hidden = !farm; $('#fl-card').hidden = farm;
  $('#fl-card').dataset.mode = m;
  $('#fl-title').textContent = m === 'clear' ? '2 · Übersicht' : m === 'fill' ? '2 · Füllblock' : m === 'light' ? '2 · Lichtquelle' : '2 · Höhe und Füllblock';
  $('#fl-with').textContent = m === 'light' ? 'Setzen' : 'Füllen mit';
  $('#fl-map-text').textContent = m === 'light' ? 'Auf der Karte zeigen (orange: Chunks, die Fackeln brauchen)' : 'Auf der Karte zeigen (rot abbauen, blau füllen)';
  // torches for lighting up, the fill block for everything else
  const lightBlock = /torch$|lantern$/.test($('#fl-block').value.trim());
  if (m === 'light' && !lightBlock) { farmUi.fillBlock = $('#fl-block').value; $('#fl-block').value = 'torch'; }
  else if (m !== 'light' && lightBlock) $('#fl-block').value = farmUi.fillBlock || '';
  if (m === 'light' && !$('#spawn3d').checked) { $('#spawn3d').checked = true; $('#spawn3d').dispatchEvent(new Event('change')); }
  if (!farm) { loadFlatInventory(); scanFlat(); } else if (farmUi.box) scanFarm();
  renderFarmGo();
}
function defaultLevel(a) {
  const p = state.player;
  if (p && p.active && p.dim === (farmUi.dim || state.dim)) return Math.floor(p.y) - 1;
  return a ? Math.max(a.y0, Math.min(a.y1, 64)) : 64;
}
const FILL_PREF = ['dirt', 'cobblestone', 'cobbled_deepslate', 'netherrack', 'stone', 'andesite', 'diorite', 'granite', 'tuff'];
async function loadFlatInventory() {
  try { farmUi.inv = await api('/api/inventory'); } catch (_) { farmUi.inv = null; }
  const counts = {};
  for (const it of (farmUi.inv && farmUi.inv.items) || []) if (it.slot == null || it.slot < 36) counts[it.id] = (counts[it.id] || 0) + it.count;
  farmUi.invCounts = counts;
  $('#fl-blocks').innerHTML = Object.keys(counts).map((id) => `<option value="${esc(id.replace('minecraft:', ''))}">`).join('');
  if (!$('#fl-block').value) {
    const have = FILL_PREF.find((b) => counts['minecraft:' + b]);
    $('#fl-block').value = farmUi.mode === 'light' ? 'torch' : have || 'dirt';
  }
  renderFlatSum();
}
let flatScanT = null;
async function scanFlat() {
  const a = farmArea();
  const kind = farmUi.mode;
  if (!a || kind === 'farm' || (kind === 'flat' && $('#fl-level').value === '')) { farmUi.flat = null; renderFlatSum(); return; }
  try {
    farmUi.flat = await api('/api/farm/flatscan?' + new URLSearchParams({ srv: state.server, dim: farmUi.dim || state.dim, ...a, kind, level: $('#fl-level').value || 0 }));
  } catch (e) { farmUi.flat = { error: e.message }; }
  renderFlatSum();
}
/* Flatten preview: what gets broken and filled, the material, and while it runs a progress bar.
 * On the map the chunks are tinted (red = break, blue = fill), can be switched off. */
const flBar = (done, total, cls = '') => `<div class="fl-bar ${cls}"><span style="width:${total ? Math.min(100, (100 * done) / total).toFixed(1) : 0}%"></span></div>`;
function flProgress(fl) {
  // stage clear/fill -> [done, total, text]
  if (fl.stage === 'clear') {
    const t = fl.clearTotal || fl.clearLeft || 0, l = fl.clearLeft ?? t;
    return [t - l, t, `${fmt(t - l)} von ${fmt(t)} Blöcken abgetragen`];
  }
  const t = fl.fillTotal || fl.fillLeft || 0, l = fl.fillLeft ?? t;
  if (fl.kind === 'light') return [t - l, t, l ? `noch etwa ${fmt(l)} Fackeln nötig` : 'alles hell'];
  return [t - l, t, `${fmt(t - l)} von ${fmt(t)} Löchern gefüllt`];
}
function renderFlatSum() {
  const a = farmArea(), f = farmUi.flat, run = farmUi.auto && farmUi.auto.mode === 'flatten' && farmRunning() && farmUi.auto.farm;
  let html = '', short = '';
  if (run) {
    const fl = run.flatten;
    const [done, total, text] = flProgress(fl);
    const pct = total ? Math.round((100 * done) / total) : 0;
    short = `· ${pct} %`;
    html = `<div class="fl-row"><span>${fl.kind === 'light' ? 'Ausleuchten' : fl.stage === 'clear' ? 'Abtragen' : 'Auffüllen'}</span><b class="mono">${pct} %</b></div>${flBar(done, total, fl.stage === 'clear' ? 'red' : 'blue')}
      <p class="hint">${text}${fl.fill && fl.stage === 'clear' ? ' · danach wird aufgefüllt' : ''}</p>`;
  } else if (!a) {
    html = '<p class="hint">Erst einen Bereich wählen.</p>';
  } else if (f && f.error) {
    html = `<p class="err">${esc(f.error)}</p>`;
  } else if (f) {
    const m = farmUi.mode;
    const lvl = Number($('#fl-level').value);
    const id = 'minecraft:' + $('#fl-block').value.trim().replace(/^minecraft:/, '');
    const have = (farmUi.invCounts || {})[id] || 0;
    const doClear = m === 'clear' || (m === 'flat' && $('#fl-clear').checked);
    const doFill = m === 'fill' || m === 'light' || (m === 'flat' && $('#fl-fill').checked);
    const rows = [];
    if (doClear) rows.push(`<div class="fl-row"><span class="sw sw-red"></span><span>${m === 'clear' ? `Abbauen (Y ${a.y0} bis ${a.y1})` : `Abtragen (über Y ${lvl})`}</span><b class="mono">${fmt(f.clear)}</b></div>`);
    if (m === 'light') rows.push(`<div class="fl-row"><span class="sw sw-red"></span><span>Spawnstellen im Bereich</span><b class="mono">${fmt(f.spots || 0)}</b></div>`,
      `<div class="fl-row"><span class="sw sw-orange"></span><span>Fackeln dafür (etwa)</span><b class="mono">${fmt(f.fill)}</b></div>`);
    else if (doFill) rows.push(`<div class="fl-row"><span class="sw sw-blue"></span><span>${m === 'fill' ? 'Leere Stellen füllen' : `Löcher füllen (bis Y ${lvl})`}</span><b class="mono">${fmt(f.fill)}</b></div>`);
    if (doFill) {
      const miss = Math.max(0, f.fill - have);
      rows.push(`<div class="fl-row">${itemIcon(id, 20)}<span>${esc(itemName(id))}: ${fmt(have)} dabei</span>
        <b class="mono ${miss ? 'warn' : 'ok'}">${miss ? `fehlen ${fmt(miss)}` : 'reicht'}</b></div>${flBar(Math.min(have, f.fill), f.fill, miss ? 'warn' : 'ok')}`);
      if (miss) rows.push(`<p class="hint warn">Nimm noch ${fmt(miss)} ${esc(itemName(id))} mit (${stacksText(id, miss)}).</p>`);
    }
    if (doClear && f.mined && f.mined.length) {
      rows.push(`<div class="fl-mined" title="Das sammelt Baritone beim Abtragen ein">${f.mined.slice(0, 8).map(([n, c]) =>
        `<span>${itemIcon(n, 16)} <span class="mono">${fmt(c)}</span></span>`).join('')}</div>`);
    }
    const free = Math.ceil((doClear ? f.clear : 0) / 64);
    if (doClear && f.clear) rows.push(`<p class="hint">Fürs Einsammeln brauchst du etwa ${fmt(free)} freie Slots, sonst lagert Baritone zwischendurch in die Kiste ein.</p>`);
    if (m === 'light' && !f.spots) rows.length = 0;
    html = rows.join('') || `<p class="hint">${m === 'light' ? 'Hier spawnt nichts (oder die Aufnahme hat noch kein Licht: einmal neu hinfliegen).' : 'Hier gibt es nichts zu tun.'}</p>`;
    short = [doClear ? `−${fmt(f.clear)}` : '', doFill ? `+${fmt(f.fill)}` : ''].filter(Boolean).join(' ');
  }
  $('#fl-sum').innerHTML = html;
  $('#fl-prev-short').textContent = short;
  drawFlatPreview();
  renderFlatFloat();
  renderFarmGo();
}

// Chunks of the preview on the map
farmUi.flatLayer = L.layerGroup().addTo(map);
function drawFlatPreview() {
  farmUi.flatLayer.clearLayers();
  const f = farmUi.flat;
  const open = !$('#side').hidden && sideTab === 'farm' && farmUi.mode !== 'farm';
  if (!f || !f.chunks || !open || !$('#fl-map').checked || (farmUi.dim || state.dim) !== state.dim) return;
  const a = farmArea();
  if (!a) return;
  const m = farmUi.mode;
  const doClear = m === 'clear' || (m === 'flat' && $('#fl-clear').checked);
  const doFill = m === 'fill' || m === 'light' || (m === 'flat' && $('#fl-fill').checked);
  const max = Math.max(1, ...f.chunks.map((c) => (doClear ? c[2] : 0) + (doFill ? c[3] : 0)));
  for (const [cx, cz, c, fi] of f.chunks) {
    const n = (doClear ? c : 0) + (doFill ? fi : 0);
    if (!n) continue;
    const col = m === 'light' ? '#f5a524' : doClear && c >= fi ? '#e5484d' : '#3b82f6';
    const b = L.latLngBounds(toLatLng(Math.max(cx * 16, a.x0), Math.max(cz * 16, a.z0)), toLatLng(Math.min(cx * 16 + 16, a.x1 + 1), Math.min(cz * 16 + 16, a.z1 + 1)));
    L.rectangle(b, { color: col, weight: 0, fillColor: col, fillOpacity: 0.12 + 0.45 * Math.sqrt(n / max), interactive: false }).addTo(farmUi.flatLayer);
  }
}
$('#fl-map').addEventListener('change', () => { savePref('flMap', $('#fl-map').checked); drawFlatPreview(); });
$('#fl-prev').addEventListener('toggle', () => savePref('flPrevOpen', $('#fl-prev').open));
if (prefs.flMap === false) $('#fl-map').checked = false;
if (prefs.flPrevOpen === false) $('#fl-prev').open = false;

// Small progress window on the map while flattening runs (closable until the next start)
function renderFlatFloat() {
  const a = farmUi.auto, run = a && a.mode === 'flatten' && farmRunning() && a.farm;
  const key = run ? String(a.started || a.farm.area.x0 + ',' + a.farm.area.z0) : null;
  if (!run || farmUi.floatClosed === key) { $('#fl-float').hidden = true; return; }
  const fl = run.flatten;
  const [done, total, text] = flProgress(fl);
  const pct = total ? Math.round((100 * done) / total) : 0;
  $('#fl-float').hidden = false;
  $('#fl-float').dataset.key = key;
  $('#fl-float-title').textContent = AUTO_NAME[fl.kind] || 'Ebnen';
  $('#fl-float-pct').textContent = `${pct} %`;
  $('#fl-float-bar').style.width = `${pct}%`;
  $('#fl-float-bar').className = fl.stage === 'clear' ? 'red' : 'blue';
  $('#fl-float-sub').textContent = text + (a.paused ? ' · pausiert' : '');
}
$('#fl-float-close').addEventListener('click', () => { farmUi.floatClosed = $('#fl-float').dataset.key; $('#fl-float').hidden = true; });
$('#fl-float').addEventListener('click', (e) => { if (!e.target.closest('button')) toggleSide(true, 'farm'); });

function renderFarm() {
  const a = farmUi.auto;
  renderPickLabel();
  renderFarmGo();
  drawFarmArea();
  if (farmUi.mode !== 'farm') renderFlatSum();
  if (!a || (a.mode !== 'farm' && a.mode !== 'flatten')) { $('#fm-info').textContent = ''; $('#fm-log').innerHTML = ''; return; }
  const run = farmRunning();
  const filling = a.mode === 'flatten' && a.phase === 'mine' && a.farm && a.farm.flatten && a.farm.flatten.stage === 'fill';
  if (run && farmUi.mode !== runningKind()) setFarmMode(runningKind());
  $('#fm-info').innerHTML = `<b class="${a.phase === 'error' ? 'bad' : a.phase === 'done' ? 'ok' : a.paused ? 'warn' : ''}">${run && a.paused ? 'pausiert' : filling ? 'füllt auf' : AB_PHASE[a.phase] || a.phase}</b>` +
    (a.info ? ' · ' + esc(a.info) : '') + (a.farm && a.farm.deposits ? ` · ${a.farm.deposits}× eingelagert` : '');
  $('#fm-log').innerHTML = (a.log || []).slice(-8).reverse().map(([t, m]) =>
    `<li><span class="mono muted">${new Date(t).toLocaleTimeString(I18N.locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span> ${esc(m)}</li>`).join('');
  if (run && a.farm && a.mode === 'farm') renderFarmBlocks();
}
async function pollFarm() {
  if (document.hidden || !state.server) return;
  const open = !$('#side').hidden && sideTab === 'farm';
  if (!open && !farmRunning()) { drawFarmArea(); drawFlatPreview(); renderFlatFloat(); return; }
  if (open && (chests.list || []).length !== farmUi.nChests) { farmUi.nChests = (chests.list || []).length; fillFarmChests(); }
  try { farmUi.auto = await api('/api/auto?' + new URLSearchParams({ srv: gameSrv() })); } catch (_) { return; }
  autoBadge();
  renderFarm();
}
setInterval(pollFarm, 2000);

/* Area in 3D: click two corner blocks (with the mouse locked the crosshair counts) */
function pick3dStop() {
  farmUi.pick3d = null;
  if (view3d) view3d.setBlockPick(null);
  $('#fm-pick-hint').hidden = true;
  renderPickLabel();
  drawFarmArea();
}
function pick3dStart() {
  farmUi.pick3d = [];
  $('#fm-pick-hint').hidden = false;
  $('#fm-pick-hint').textContent = 'Klick in 3D auf den ersten Eckblock.';
  renderPickLabel();
  view3d.setBlockPick((b) => {
    if (!farmUi.pick3d) return;
    farmUi.pick3d.push(b);
    if (farmUi.pick3d.length === 1) {
      view3d.setArea({ x0: b.x, x1: b.x, y0: b.y, y1: b.y, z0: b.z, z1: b.z });
      $('#fm-pick-hint').textContent = `Erste Ecke ${b.x} ${b.y} ${b.z}. Jetzt auf die gegenüberliegende Ecke klicken.`;
      return;
    }
    const [p, q] = farmUi.pick3d;
    pick3dStop();
    $('#fm-snap').checked = false;   // picked in 3D: exactly these blocks
    setFarmBox({ x0: Math.min(p.x, q.x), z0: Math.min(p.z, q.z), x1: Math.max(p.x, q.x), z1: Math.max(p.z, q.z) },
      Math.min(p.y, q.y), Math.max(p.y, q.y));
  });
}
function renderPickLabel() {
  $('#fm-pick').textContent = farmUi.pick3d ? 'Abbrechen' : mode3d ? 'In 3D wählen' : 'Auf der Karte wählen';
}
$('#fm-pick').addEventListener('click', () => {
  if (farmUi.pick3d) { pick3dStop(); return; }
  if (mode3d && view3d) { pick3dStart(); return; }
  setSelect(true);
});
$('#sel-farm').addEventListener('click', () => {
  const b = sel.box;
  if (!b) return;
  setFarmBox({ ...b }, Number($('#sel-y0').value), Number($('#sel-y1').value));
  setSelect(false);
  toggleSide(true, 'farm');
});
$('#fm-snap').addEventListener('change', farmAreaChanged);
$('#fm-y0').addEventListener('change', farmAreaChanged);
$('#fm-y1').addEventListener('change', farmAreaChanged);
$('#fm-filter').addEventListener('input', renderFarmBlocks);
$$('.fm-mode button').forEach((b) => b.addEventListener('click', () => setFarmMode(b.dataset.fmmode)));
$('#fl-level').addEventListener('input', () => { clearTimeout(flatScanT); flatScanT = setTimeout(scanFlat, 300); renderFarmGo(); });
$('#fl-here').addEventListener('click', () => {
  const p = state.player;
  if (!p || !p.active) { toast('Spiel nicht verbunden'); return; }
  $('#fl-level').value = Math.floor(p.y) - 1;
  scanFlat();
});
for (const id of ['#fl-clear', '#fl-fill']) $(id).addEventListener('change', renderFlatSum);
$('#fl-block').addEventListener('input', renderFlatSum);
$('#fm-blocks').addEventListener('change', (e) => {
  const id = e.target.dataset && e.target.dataset.fm;
  if (!id) return;
  const n = Math.max(0, Math.floor(Number(e.target.value) || 0));
  if (n) farmUi.amounts[id] = n; else delete farmUi.amounts[id];
  renderFarmBlocks();
});
$('#fm-go').addEventListener('click', async () => {
  const srv = gameSrv();
  try {
    if (farmRunning()) {
      farmUi.auto = await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv }) });
    } else {
      const a = farmArea();
      if (farmUi.mode !== 'farm') {
        const kind = farmUi.mode;
        const level = Number($('#fl-level').value), clear = $('#fl-clear').checked, fill = $('#fl-fill').checked;
        const block = $('#fl-block').value.trim();
        const bname = itemName('minecraft:' + block.replace(/^minecraft:/, ''));
        const chest = $('#fm-chest').value;
        const what = kind === 'light' ? `${bname} auf die Spawnstellen setzen, bis im Bereich nichts mehr spawnt (Y ${a.y0} bis ${a.y1})`
          : kind === 'clear' ? `alles im Bereich abbauen (Y ${a.y0} bis ${a.y1})`
          : kind === 'fill' ? `jede leere Stelle im Bereich mit ${bname} füllen (Y ${a.y0} bis ${a.y1})`
          : [clear ? `alles über Y ${level} abtragen` : '', fill ? `Löcher bis Y ${level} mit ${bname} füllen` : ''].filter(Boolean).join(' und ');
        const full = kind === 'fill' || kind === 'light' ? '' : `\n\nVolles Inventar: ${chest ? 'zur gewählten Kiste bringen' : 'Kiste aus dem Inventar aufstellen und einpacken'}.`;
        if (!confirm(`${AUTO_NAME[kind]} starten?\n\nBaritone wird ${what}.${full}\n\nAbbrechen jederzeit mit „■ ${AUTO_NAME[kind]} stoppen“.`)) return;
        farmUi.auto = await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv, farm: { area: a, dim: farmUi.dim || state.dim, chest: chest || null, placeChest: true, flatten: { kind, level, clear, fill, block } } }) });
        renderFarm();
        return;
      }
      const targets = Object.fromEntries(Object.entries(farmUi.amounts).filter(([, n]) => n > 0));
      const what = Object.entries(targets).map(([id, n]) => `${fmt(n)}× ${itemName(id)}`).join(', ');
      const chest = $('#fm-chest').value;
      if (!confirm(`Farmen starten?\n\n${what}\n\nBaritone baut im markierten Bereich ab und sammelt alles ein. Volles Inventar: ${chest ? 'zur gewählten Kiste bringen' : 'Kiste aus dem Inventar aufstellen und einpacken'}.\n\nAbbrechen jederzeit mit „■ Farmen stoppen“.`)) return;
      farmUi.auto = await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv, farm: { area: a, dim: farmUi.dim || state.dim, targets, chest: chest || null, placeChest: true } }) });
    }
  } catch (e) { toast('Farmen: ' + e.message); }
  renderFarm();
});
farmAreaChanged();

// Mob-spawn legend: "Bereich ausleuchten" opens the automation, area from the map
$('#spawn-light').addEventListener('click', () => {
  if (farmRunning()) { toggleSide(true, 'farm'); return; }
  setFarmMode('light');
  toggleSide(true, 'farm');
  if (!farmUi.box) setSelect(true);
});
