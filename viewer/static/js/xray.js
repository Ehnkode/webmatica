/* Webmatica map UI: X-ray in 3D – hide everything except the searched blocks. */
import './farm.js';   // runs before this file (load order, see index.html)
import { $, api, esc, fmt, fromLatLng, map, prefs, savePref, state, toast, toLatLng } from './core.js';
import { flash } from './sidebar.js';
import { baritone } from './places.js';
import { mode3d, openPop, set3d, setFollow, view3d } from './player.js';
import { itemIcon } from './chests.js';

// Ready-made searches: label -> block ids. Typing the label (or a part of it) finds them too.
const XRAY_PRESETS = [
  { label: 'Diamanterz', names: ['diamond_ore', 'deepslate_diamond_ore'], color: '#4ee6e0' },
  { label: 'Antiker Schutt', names: ['ancient_debris'], color: '#d0784f' },
  { label: 'Spawner', names: ['spawner', 'trial_spawner'], color: '#b07cff' },
  { label: 'Truhen', names: ['chest', 'trapped_chest', 'barrel'], color: '#f5a524' },
  { label: 'Shulkerkisten', names: ['shulker_box', 'white_shulker_box', 'orange_shulker_box', 'magenta_shulker_box',
    'light_blue_shulker_box', 'yellow_shulker_box', 'lime_shulker_box', 'pink_shulker_box', 'gray_shulker_box',
    'light_gray_shulker_box', 'cyan_shulker_box', 'purple_shulker_box', 'blue_shulker_box', 'brown_shulker_box',
    'green_shulker_box', 'red_shulker_box', 'black_shulker_box'], color: '#ff6fb5', hidden: true },
  { label: 'Smaragderz', names: ['emerald_ore', 'deepslate_emerald_ore'], color: '#3ddc84', hidden: true },
  { label: 'Golderz', names: ['gold_ore', 'deepslate_gold_ore', 'nether_gold_ore'], color: '#f8d34a', hidden: true },
  { label: 'Eisenerz', names: ['iron_ore', 'deepslate_iron_ore'], color: '#e0b494', hidden: true },
  { label: 'Endportal', names: ['end_portal_frame', 'end_portal'], color: '#9fe870', hidden: true },
];
const XRAY_COLORS = ['#4ee6e0', '#f5a524', '#b07cff', '#ff6fb5', '#3ddc84', '#f8d34a', '#ff5d5d', '#6aa8ff'];
// The server takes at most 64 block ids at once (shulker boxes alone are 17)
const XRAY_MAX_NAMES = 64;

const xray = {
  on: !!prefs.xrayOn,
  groups: Array.isArray(prefs.xray) ? prefs.xray : [],    // [{ label, names, color }]
  all: null,                                                // all block ids for the suggestions
  counts: null,
  // 2D map: dots for the finds around the map centre
  layer: L.layerGroup().addTo(map),
  pts: [], names: [], visited: new Set(), timer: null, seq: 0,
};

const xrayId = (n) => (n.includes(':') ? n : 'minecraft:' + n);
const xrayPretty = (id) => id.replace(/^minecraft:/, '').replace(/_/g, ' ');

function xraySave() {
  savePref('xray', xray.groups);
  savePref('xrayOn', xray.on);
}

/* Sends the current search to the 3D view (only while X-ray is on). */
function xrayApply() {
  $('#btn-xray').classList.toggle('on', xray.on && xray.groups.length > 0);
  $('#xray-on').checked = xray.on;
  const names = [], colors = [];
  if (xray.on) {
    for (const g of xray.groups) {
      for (const n of g.names) {
        const id = xrayId(n);
        if (!names.includes(id)) { names.push(id); colors.push(g.color); }
      }
    }
  }
  document.body.classList.toggle('xray-on', xray.on && names.length > 0);
  xray2dSoon(0);
  if (!view3d) return;
  view3d.setXray(names.slice(0, XRAY_MAX_NAMES), colors.slice(0, XRAY_MAX_NAMES));
  if (names.length > XRAY_MAX_NAMES) toast(`X-Ray: höchstens ${XRAY_MAX_NAMES} Blöcke gleichzeitig, der Rest wird ignoriert.`);
}

function xrayRender() {
  const box = $('#xray-sel');
  const counted = xray.counts && xray.on;
  box.innerHTML = xray.groups.map((g, i) => {
    const n = counted ? g.names.reduce((a, id) => a + (xray.counts.get(xrayId(id)) || 0), 0) : null;
    return `<span class="xray-chip" style="--c:${esc(g.color)}"><span class="sw"></span>${esc(g.label)}` +
      `${n === null ? '' : ` <b class="mono">${fmt(n)}</b>`}<button type="button" data-i="${i}" title="Entfernen">✕</button></span>`;
  }).join('') || '<span class="hint">Noch nichts gesucht. Wähle oben einen Block oder tippe einen Namen.</span>';
  $('#xray-presets').innerHTML = XRAY_PRESETS.filter((p) => !p.hidden).map((p) => {
    const on = xray.groups.some((g) => g.label === p.label);
    return `<button type="button" class="chip${on ? ' on' : ''}" data-preset="${esc(p.label)}" style="--c:${p.color}"><span class="sw"></span>${esc(p.label)}</button>`;
  }).join('');
  const total = counted ? Array.from(xray.counts.values()).reduce((a, b) => a + b, 0) : 0;
  $('#xray-next').disabled = !counted || !total;
}

function xrayAdd(group) {
  const i = xray.groups.findIndex((g) => g.label === group.label);
  if (i >= 0) { xray.groups.splice(i, 1); }
  else {
    const used = new Set(xray.groups.map((g) => g.color));
    if (!group.color || used.has(group.color)) group.color = XRAY_COLORS.find((c) => !used.has(c)) || XRAY_COLORS[xray.groups.length % XRAY_COLORS.length];
    xray.groups.push(group);
    xray.on = true;
  }
  xraySave();
  xrayApply();
  xrayRender();
}

/* Text from the search field -> a group: preset label, exact id or name, or every id containing the text. */
function xrayResolve(text) {
  const t = text.trim().toLowerCase();
  if (!t) return null;
  const preset = XRAY_PRESETS.find((p) => p.label.toLowerCase() === t) || XRAY_PRESETS.find((p) => p.label.toLowerCase().startsWith(t));
  if (preset) return { label: preset.label, names: preset.names.slice(), color: preset.color };
  const all = xray.all || [];
  const key = t.replace(/^minecraft:/, '').replace(/\s+/g, '_');
  const exact = all.find((id) => id === 'minecraft:' + key);
  if (exact) return { label: xrayPretty(exact), names: [exact] };
  const some = all.filter((id) => id.includes(key));
  if (!some.length) return null;
  if (some.length > XRAY_MAX_NAMES) return { tooMany: some.length };
  return { label: some.length === 1 ? xrayPretty(some[0]) : `„${text.trim()}“ (${some.length})`, names: some };
}

async function xrayLoadNames() {
  if (xray.all) return;
  try {
    xray.all = await api('/api/xray/names');
    $('#xray-list').innerHTML = XRAY_PRESETS.map((p) => `<option value="${esc(p.label)}">`).join('') +
      xray.all.map((id) => `<option value="${esc(xrayPretty(id))}">`).join('');
  } catch (_) { xray.all = null; }
}

// Stats from the 3D view: finds per block id in the loaded chunks
function xrayStats(s) {
  xray.counts = new Map();
  if (s && s.on && s.names) {
    s.names.forEach((id, k) => xray.counts.set(id, s.counts[k] || 0));
  } else if (s && s.on && view3d) {
    const names = [];
    for (const g of xray.groups) for (const n of g.names) { const id = xrayId(n); if (!names.includes(id)) names.push(id); }
    const sorted = names.slice(0, XRAY_MAX_NAMES).sort();
    sorted.forEach((id, k) => xray.counts.set(id, s.counts[k] || 0));
  }
  xrayRender();
}

function xrayToggle(on = !xray.on) {
  if (on && !xray.groups.length) { openPop('pop-xray'); xrayLoadNames(); $('#xray-input').focus(); return; }
  xray.on = on;
  xraySave();
  xrayApply();
  xrayRender();
  toast(xray.on ? 'X-Ray an' : 'X-Ray aus');
}

function xrayNext() {
  if (!xray.on) return;
  if (!mode3d) { xray2dNext(); return; }
  if (!view3d) return;
  const f = view3d.xrayNext();
  if (!f) { toast('X-Ray: in Sichtweite nichts gefunden'); return; }
  toast(`${xrayPretty(f.name)} bei ${f.x} ${f.y} ${f.z}`);
}

$('#btn-xray').addEventListener('click', xrayLoadNames);
$('#xray-on').addEventListener('change', (e) => xrayToggle(e.target.checked));
$('#xray-presets').addEventListener('click', (e) => {
  const b = e.target.closest('[data-preset]');
  if (!b) return;
  const p = XRAY_PRESETS.find((x) => x.label === b.dataset.preset);
  xrayAdd({ label: p.label, names: p.names.slice(), color: p.color });
});
$('#xray-sel').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-i]');
  if (!b) return;
  xray.groups.splice(Number(b.dataset.i), 1);
  xraySave();
  xrayApply();
  xrayRender();
});
$('#xray-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  await xrayLoadNames();
  const input = $('#xray-input');
  const g = xrayResolve(input.value);
  if (!g) { toast('X-Ray: keinen Block gefunden für „' + input.value.trim() + '“'); return; }
  if (g.tooMany) { toast(`X-Ray: ${g.tooMany} Blöcke passen, bitte genauer suchen`); return; }
  if (xray.groups.some((x) => x.label === g.label)) { input.value = ''; return; }
  input.value = '';
  xrayAdd(g);
});
$('#xray-next').addEventListener('click', xrayNext);
xrayRender();
xrayApply();

// ------------------------------------------------------------------ 2D map

function xrayWanted() {
  const names = [], colors = [];
  for (const g of xray.groups) {
    for (const n of g.names) { const id = xrayId(n); if (!names.includes(id)) { names.push(id); colors.push(g.color); } }
  }
  return { names: names.slice(0, XRAY_MAX_NAMES), colors: colors.slice(0, XRAY_MAX_NAMES) };
}

function xray2dSoon(ms = 300) {
  clearTimeout(xray.timer);
  xray.timer = setTimeout(xray2dLoad, ms);
}

/* Finds in the visible area (at most 24 chunks around the centre) as coloured dots;
 * the map underneath is dimmed so they stand out. */
async function xray2dLoad() {
  const seq = ++xray.seq;
  const { names, colors } = xrayWanted();
  if (mode3d || !xray.on || !names.length || !state.server) { xray.layer.clearLayers(); xray.pts = []; return; }
  const c = fromLatLng(map.getCenter());
  const b = map.getBounds();
  const half = Math.max(Math.abs(b.getEast() - b.getWest()), Math.abs(b.getNorth() - b.getSouth())) / 2;
  const r = Math.max(2, Math.min(24, Math.ceil(half / 16)));
  try {
    const d = await api('/api/xray/find?' + new URLSearchParams({ srv: state.server, dim: state.dim, x: c.x, z: c.z, r, b: names.join(',') }));
    if (seq !== xray.seq) return;
    const color = {};
    names.forEach((n, i) => { color[n] = colors[i]; });
    xray.layer.clearLayers();
    xray.names = d.names;
    xray.pts = [];
    for (let i = 0; i < d.pts.length; i += 4) xray.pts.push([d.pts[i], d.pts[i + 1], d.pts[i + 2], d.pts[i + 3]]);
    // Item icons with a ring in the search colour; at most XRAY_2D_MAX (nearest to the centre) as map markers
    const near = xray.pts.slice().sort((a, b2) => ((a[0] - c.x) ** 2 + (a[2] - c.z) ** 2) - ((b2[0] - c.x) ** 2 + (b2[2] - c.z) ** 2))
      .slice(0, XRAY_2D_MAX);
    const icons = {};
    for (const a of near) {
      const id = d.names[a[3]];
      const icon = icons[id] || (icons[id] = L.divIcon({ className: 'xray-pin', iconSize: [22, 22], iconAnchor: [11, 11],
        html: `<span class="xray-pin-ring" style="--c:${esc(color[id] || '#fff')}">${itemIcon(id, 16)}</span>` }));
      L.marker(toLatLng(a[0] + 0.5, a[2] + 0.5), { icon, riseOnHover: true, keyboard: false })
        .bindTooltip(`${esc(xrayPretty(id))} · ${a[0]} ${a[1]} ${a[2]}`, { direction: 'top', offset: [0, -10] })
        .on('click', () => xrayPopup(a, id))
        .addTo(xray.layer);
    }
    xrayStats({ on: true, counts: d.counts, names: d.names });
    if (half / 16 > 24 && !xray.zoomHint) { xray.zoomHint = true; toast('X-Ray zeigt auf der Karte die Funde im Umkreis von 384 Blöcken um die Mitte'); }
  } catch (e) {
    if (seq === xray.seq) toast('X-Ray: ' + e.message);
  }
}

const XRAY_2D_MAX = 1500;

/* Click on a find: where it is, and Baritone or 3D from there */
function xrayPopup(a, id) {
  const [x, y, z] = a;
  const d = document.createElement('div');
  d.className = 'xray-pop';
  d.innerHTML = `<div class="xray-pop-head">${itemIcon(id, 20)}<b>${esc(xrayPretty(id))}</b></div>
    <div class="mono muted">${x} ${y} ${z}</div>
    <button data-x="goto" class="primary">Mit Baritone hingehen</button>
    <button data-x="3d">In 3D ansehen</button>
    <button data-x="copy">Koordinaten kopieren</button>`;
  d.addEventListener('click', async (e) => {
    const b = e.target.closest('button[data-x]');
    if (!b) return;
    map.closePopup();
    if (b.dataset.x === 'goto') baritone({ kind: 'goto', x, y: y + 1, z });
    else if (b.dataset.x === '3d') { await set3d(true); if (view3d) view3d.setCamera(x + 0.5, y + 4, z + 6.5, 180, 30); }
    else { try { await navigator.clipboard.writeText(`${x} ${y} ${z}`); toast('Kopiert: ' + `${x} ${y} ${z}`); } catch (_) { toast(`${x} ${y} ${z}`); } }
  });
  L.popup({ closeButton: false, className: 'ctx', offset: [0, -8] }).setLatLng(toLatLng(x + 0.5, z + 0.5)).setContent(d).openOn(map);
}

function xray2dNext() {
  if (!xray.pts.length) { toast('X-Ray: hier nichts gefunden'); return; }
  const c = fromLatLng(map.getCenter());
  const d2 = (a) => (a[0] - c.x) ** 2 + (a[2] - c.z) ** 2;
  let left = xray.pts.filter((a) => !xray.visited.has(a.join(',')));
  if (!left.length) { xray.visited.clear(); left = xray.pts; }
  let best = left[0];
  for (const a of left) if (d2(a) < d2(best)) best = a;
  xray.visited.add(best.join(','));
  setFollow(false);
  map.setView(toLatLng(best[0] + 0.5, best[2] + 0.5), Math.max(map.getZoom(), 2));
  flash(best[0], best[2]);
  xrayPopup(best, xray.names[best[3]]);
  toast(`${xrayPretty(xray.names[best[3]])} bei ${best[0]} ${best[1]} ${best[2]}`);
}

map.on('moveend', () => { if (xray.on && !mode3d) xray2dSoon(); });

export { xray, xray2dSoon, xrayApply, xrayNext, xrayStats, xrayToggle };
