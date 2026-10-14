/* Webmatica map UI: Inventory, pickup tour, chest inventory and the chest window. */
import './materials.js';   // runs before this file (load order, see index.html)
import { $, $$, api, esc, fmt, fromLatLng, map, prefs, savePref, state, toast, toLatLng } from './core.js';
import { baritone, drawMarkers } from './places.js';
import { mode3d, setFollow, view3d } from './player.js';
import { jsonPost, schem, selPl } from './schematics.js';
import { renderCheck, renderMats, renderMissing } from './materials.js';
import { highlightChests } from './automation.js';

// --- Inventory, pickup tour, Baritone buttons for building

var myInv = null;    // { items, t } from the player
const fullId = (it) => (it.includes(':') ? it : 'minecraft:' + it);
function invCounts() {
  const m = new Map();
  for (const it of (myInv && myInv.items) || []) {
    m.set(it.id, (m.get(it.id) || 0) + it.count);
    for (const x of it.contents || []) m.set(x.id, (m.get(x.id) || 0) + x.count);
  }
  return m;
}
/* Missing items minus what you already carry */
function stillNeeded(items) {
  const have = invCounts();
  const out = [];
  for (const [it, n] of items) {
    const rest = n - (have.get(fullId(it)) || 0);
    if (rest > 0) out.push([it, rest]);
  }
  return out;
}
/* Pickup tour: few chests that cover as much as possible (greedy), then nearest first */
function planTour(need) {
  const rest = new Map(need.map(([it, n]) => [fullId(it), n]));
  const pool = chests.list.map((c) => {
    const m = new Map();
    for (const [id, n] of chestItems(c)) m.set(id, (m.get(id) || 0) + n);
    return { chest: c, have: m };
  });
  const picked = [];
  while (pool.length) {
    let best = null, bestGain = 0;
    for (const p of pool) {
      let g = 0;
      for (const [id, n] of rest) g += Math.min(n, p.have.get(id) || 0);
      if (g > bestGain) { best = p; bestGain = g; }
    }
    if (!best) break;
    pool.splice(pool.indexOf(best), 1);
    const take = [];
    for (const [id, n] of rest) {
      const k = Math.min(n, best.have.get(id) || 0);
      if (k) { take.push([id, k]); rest.set(id, n - k); }
    }
    for (const [id, n] of Array.from(rest)) if (n <= 0) rest.delete(id);
    picked.push({ chest: best.chest, take });
  }
  // Order: ender chest first (reachable anywhere), then always the nearest
  const p = state.player && state.player.active && state.player.dim === state.dim ? state.player : null;
  let cur = p ? [p.x, p.z] : null;
  const stops = [];
  const left = picked.slice();
  const ender = left.findIndex((s) => s.chest.dim === 'ender');
  if (ender >= 0) stops.push(left.splice(ender, 1)[0]);
  while (left.length) {
    let bi = 0, bd = Infinity;
    left.forEach((s, i) => {
      const d = cur && s.chest.dim === state.dim ? Math.hypot(s.chest.x - cur[0], s.chest.z - cur[1]) : 1e9 + i;
      if (d < bd) { bd = d; bi = i; }
    });
    const s = left.splice(bi, 1)[0];
    s.dist = bd < 1e9 ? bd : null;
    if (s.chest.dim === state.dim) cur = [s.chest.x, s.chest.z];
    stops.push(s);
  }
  return { stops, missing: Array.from(rest.entries()) };
}

$('#chk-out').addEventListener('click', async (e) => {
  const li = e.target.closest('li[data-tour]');
  if (li && schem.check) {
    const tour = planTour(stillNeeded(schem.check.missingItems));
    const st = tour.stops[Number(li.dataset.tour)];
    if (!st) return;
    if (e.target.closest('[data-tour-open]')) openInv(st.chest, st.take[0] && st.take[0][0]);
    if (e.target.closest('[data-tour-go]')) baritone({ kind: 'goto', x: st.chest.x, y: st.chest.y + 1, z: st.chest.z });
    if (e.target.closest('[data-tour-fetch]')) baritone({ kind: 'fetch', x: st.chest.x, y: st.chest.y, z: st.chest.z, items: st.take.map(([id, n]) => ({ id: fullId(id), count: n })) });
  }
  if (e.target.closest('[data-tour-hl]') && schem.check) highlightChests(planTour(stillNeeded(schem.check.missingItems)).stops.map((s) => s.chest));
});

async function loadInventory() {
  try {
    const inv2 = await api('/api/inventory');
    const changed = JSON.stringify(inv2 && inv2.items) !== JSON.stringify(myInv && myInv.items);
    myInv = inv2;
    if (changed && schem.check) renderCheck();
    if (changed && schem.mats) renderMissing();
  } catch (_) { /* ignore */ }
}
setInterval(() => { if (selPl() && !document.hidden) loadInventory(); }, 3000);
loadInventory();

// ------------------------------------------------------------------ Chest inventory

var chests = { list: [], layer: L.layerGroup().addTo(map), mode: prefs.ctMode || 'items', selItem: null };
var itemsMeta = null;   // Item images: { size, cols, index }
const chestKey = (it) => it.replace('minecraft:', '');
const prettyItem = (it) => chestKey(it).replace(/_/g, ' ');

(async () => {
  try {
    itemsMeta = await api('/api/items.json');
    document.documentElement.style.setProperty('--items-url', `url(/api/items.png?v=${Object.keys(itemsMeta.index).length})`);
    renderChests();
    fillSwitchIcons();
    drawChests();
    if (typeof drawMarkers === 'function') drawMarkers();
  } catch (_) { itemsMeta = null; }
})();

/* Item image as HTML (32 px, or small with size) */
function itemIcon(id, size = 32) {
  const i = itemsMeta && itemsMeta.index[id.includes(':') ? id : 'minecraft:' + id];
  if (i == null) return `<span class="ico none" style="width:${size}px;height:${size}px"></span>`;
  const k = size / itemsMeta.size;
  const x = (i % itemsMeta.cols) * size, y = Math.floor(i / itemsMeta.cols) * size;
  return `<span class="ico" style="width:${size}px;height:${size}px;background-position:-${x}px -${y}px;background-size:${itemsMeta.cols * size}px ${itemsMeta.rows * size}px"></span>`;
}

/* Map pin: item picture in a small frame (ring colour = kind), falls back to a dot without item pictures */
const pinIcons = {};
function itemPin(id, ring, size = 18, cls = '') {
  const key = [id, ring, size, cls, !!itemsMeta].join('|');
  return pinIcons[key] || (pinIcons[key] = L.divIcon({ className: 'item-pin-wrap', iconSize: [size + 6, size + 6], iconAnchor: [(size + 6) / 2, (size + 6) / 2],
    html: `<span class="item-pin ${cls}" style="--c:${ring}">${itemIcon(id, size)}</span>` }));
}
// Icons next to the map switches (once the item pictures are loaded)
function fillSwitchIcons() { $$('[data-icon]').forEach((e) => { e.innerHTML = itemIcon(e.dataset.icon, 16); }); }

async function loadContainers() {
  if (!state.server) return;
  try { chests.list = await api('/api/containers?' + new URLSearchParams({ srv: state.server })); } catch (_) { chests.list = []; }
  await loadWorldChests();
  try { chests.lids = await api('/api/containers/lid?' + new URLSearchParams({ srv: state.server, dim: state.dim })); } catch (_) { chests.lids = []; }
  renderChests();
  drawChests();
  if (schem.mats) renderMats();
  if (inv.stack.length) refreshInv();
}

/* Chests from the recording (including never opened ones), around you or your chests */
chests.world = [];
chests.worldAt = 0;
chests.lids = [];   // Chests someone else opened within view distance (lid event)
async function loadWorldChests() {
  if (Date.now() - chests.worldAt < 25000) return;
  chests.worldAt = Date.now();
  const p = state.player;
  let x, z;
  if (p && p.active && p.dim === state.dim && (!p.server || p.server === state.server)) { x = p.x; z = p.z; }
  else {
    const mine = chests.list.filter((c) => c.dim === state.dim);
    if (mine.length) { x = mine.reduce((a, c) => a + c.x, 0) / mine.length; z = mine.reduce((a, c) => a + c.z, 0) / mine.length; }
    else { const c = fromLatLng(map.getCenter()); x = c.x; z = c.z; }
  }
  try { chests.world = await api('/api/containers/world?' + new URLSearchParams({ srv: state.server, dim: state.dim, x: Math.round(x), z: Math.round(z), r: 10 })); }
  catch (_) { chests.world = []; }
}
function unopenedChests() {
  const known = new Set(chests.list.filter((c) => c.dim === state.dim).map((c) => `${c.x},${c.y},${c.z}`));
  return chests.world.filter((w) => !known.has(`${w.x},${w.y},${w.z}`) && !(w.other && known.has(w.other.join(','))));
}
function renderKnown() {
  const un = unopenedChests();
  const opened = chests.list.length;
  const lids = chests.lids.filter((l) => l.opens > 0).length;
  $('#ct-known').innerHTML = `<b>${opened}</b> Kisten mit Inhalt gemerkt` + (lids ? ` · <b style="color:#c77dff">${lids}</b> von anderen benutzt` : '') + (un.length ? ` · <b>${un.length}</b> in der Nähe noch nie geöffnet
    <button data-hl-unopened title="Die nächsten 64 davon im Spiel mit Lichtsäulen markieren">Im Spiel zeigen</button>` : chests.world.length ? ' · alle Kisten in der Nähe sind bekannt' : '');
}
$('#ct-known').addEventListener('click', (e) => {
  if (!e.target.closest('[data-hl-unopened]')) return;
  const p = state.player;
  const un = unopenedChests().map((w) => ({ ...w, dim: state.dim }));
  if (p) un.sort((a, b) => Math.hypot(a.x - p.x, a.z - p.z) - Math.hypot(b.x - p.x, b.z - p.z));
  highlightChests(un.slice(0, 64));
});

/* All items of a chest, shulker contents included: [[item, count, in-which-shulker]] */
function chestItems(c) {
  const out = [];
  for (const it of c.items) {
    out.push([it.id, it.count, null]);
    for (const inner of it.contents || []) out.push([inner.id, inner.count, it.name || prettyItem(it.id)]);
  }
  return out;
}
function chestWhere(c) {
  if (c.dim === 'ender') return 'Enderkiste';
  const what = prettyItem(c.block || 'kiste');
  return `${what} bei ${c.x} ${c.y} ${c.z}${c.dim !== state.dim ? ' (' + c.dim.replace('the_', '') + ')' : ''}`;
}
function agoText(ms) {
  const s = (Date.now() - ms) / 1000;
  if (s < 90) return 'gerade eben';
  if (s < 5400) return `vor ${Math.round(s / 60)} min`;
  if (s < 129600) return `vor ${Math.round(s / 3600)} h`;
  return `vor ${Math.round(s / 86400)} Tagen`;
}
/* How many of item are in chests, and where the most are */
function inChests(item) {
  let total = 0;
  const where = [];
  for (const c of chests.list) {
    let n = 0;
    for (const [id, k] of chestItems(c)) if (id === item) n += k;
    if (n) { total += n; where.push([n, c]); }
  }
  where.sort((a, b) => b[0] - a[0]);
  return { total, where };
}
/* Total list of all items across all chests */
function allItems() {
  const m = new Map();
  for (const c of chests.list) for (const [id, n] of chestItems(c)) m.set(id, (m.get(id) || 0) + n);
  return Array.from(m.entries()).sort((a, b) => b[1] - a[1]);
}

function renderChests() {
  renderKnown();
  const f = $('#ct-filter').value.trim().toLowerCase().replace(/ /g, '_');
  $$('.ct-mode button').forEach((b) => b.classList.toggle('on', b.dataset.ctmode === chests.mode));
  if (!chests.list.length) {
    $('#ct-sum').textContent = 'Noch keine Kiste gemerkt. Öffne im Spiel eine Kiste.';
    $('#ct-grid').innerHTML = $('#ct-where').innerHTML = $('#ct-list').innerHTML = '';
    return;
  }
  if (chests.mode === 'items') {
    $('#ct-list').innerHTML = '';
    const all = allItems().filter(([id]) => !f || chestKey(id).includes(f));
    const total = all.reduce((a, [, n]) => a + n, 0);
    $('#ct-sum').textContent = `${fmt(total)} Items, ${all.length} Sorten in ${chests.list.length} Kisten`;
    $('#ct-grid').innerHTML = all.map(([id, n]) => `<div class="cell ${id === chests.selItem ? 'sel' : ''}" data-item="${esc(id)}" title="${esc(prettyItem(id))}: ${fmt(n)}">${itemIcon(id)}<span class="n">${n >= 10000 ? Math.round(n / 1000) + 'k' : n}</span></div>`).join('');
    renderWhere();
    return;
  }
  $('#ct-grid').innerHTML = $('#ct-where').innerHTML = '';
  const rows = chests.list.filter((c) => !f || chestItems(c).some(([id]) => chestKey(id).includes(f)))
    .sort((a, b) => b.t - a.t).slice(0, 300);
  $('#ct-sum').textContent = `${rows.length} von ${chests.list.length} Kisten`;
  $('#ct-list').innerHTML = rows.map((c) => {
    const items = chestItems(c).filter((e) => !e[2]).sort((a, b) => b[1] - a[1]);
    return `<li data-ckey="${esc(c.key)}"><div class="main"><div class="title">${esc(c.title || chestWhere(c))}</div>
      <div class="sub">${esc(chestWhere(c))} · ${agoText(c.t)}</div>
      <div class="mini-items">${items.slice(0, 8).map(([id]) => itemIcon(id, 20)).join('')}${items.length > 8 ? ` <span class="muted">+${items.length - 8}</span>` : ''}</div></div>
      <button data-cdel title="Vergessen">✕</button></li>`;
  }).join('');
}

/* Locations of the selected item */
function renderWhere() {
  const id = chests.selItem;
  if (!id) { $('#ct-where').innerHTML = ''; return; }
  const k = inChests(id);
  $('#ct-where').innerHTML = `<h4>${itemIcon(id, 20)} ${esc(prettyItem(id))} · ${fmt(k.total)} <button data-hl-all title="Alle diese Kisten im Spiel mit Lichtsäulen markieren">Im Spiel zeigen</button></h4><ul class="list">${k.where.map(([n, c]) => {
    const inside = chestItems(c).filter((e) => e[0] === id && e[2]).map((e) => e[2]);
    return `<li data-ckey="${esc(c.key)}"><div class="main"><div class="title">${fmt(n)} in ${esc(c.title || chestWhere(c))}</div>
      <div class="sub">${esc(chestWhere(c))}${inside.length ? ' · in Shulker „' + esc(Array.from(new Set(inside)).join('“, „')) + '“' : ''} · ${agoText(c.t)}</div></div></li>`;
  }).join('')}</ul>`;
}

$('#ct-filter').addEventListener('input', renderChests);
$$('.ct-mode button').forEach((b) => b.addEventListener('click', () => { chests.mode = b.dataset.ctmode; savePref('ctMode', chests.mode); renderChests(); }));
$('#ct-grid').addEventListener('click', (e) => {
  const c = e.target.closest('.cell[data-item]');
  if (!c) return;
  chests.selItem = chests.selItem === c.dataset.item ? null : c.dataset.item;
  renderChests();
});
async function onChestClick(e) {
  if (e.target.closest('[data-hl-all]') && chests.selItem) { highlightChests(inChests(chests.selItem).where.map((w) => w[1])); return; }
  const li = e.target.closest('li[data-ckey]');
  if (!li) return;
  const c = chests.list.find((x) => x.key === li.dataset.ckey);
  if (!c) return;
  if (e.target.closest('[data-cdel]')) {
    await jsonPost('/api/containers/delete', { key: c.key });
    loadContainers();
    return;
  }
  openInv(c, chests.selItem);
}
$('#ct-list').addEventListener('click', onChestClick);
$('#ct-where').addEventListener('click', onChestClick);
$('#ct-csv').addEventListener('click', () => {
  const rows = ['Item;Anzahl;Fundorte'];
  for (const [id, n] of allItems()) rows.push(`${prettyItem(id)};${n};${inChests(id).where.slice(0, 5).map(([k, c]) => `${k} ${chestWhere(c)}`).join(' | ')}`);
  const url = URL.createObjectURL(new Blob(['﻿' + rows.join('\n')], { type: 'text/csv' }));
  const a = Object.assign(document.createElement('a'), { href: url, download: `${state.server}-kisten.csv` });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
});

function drawChests() {
  chests.layer.clearLayers();
  if (!$('#show-chests').checked) return;
  for (const c of chests.list) {
    if (c.dim !== state.dim) continue;
    const n = chestItems(c).reduce((a, e) => a + e[1], 0);
    L.marker(toLatLng(c.x + 0.5, c.z + 0.5), { icon: itemPin(c.block || 'minecraft:chest', '#f0b35a'), riseOnHover: true, keyboard: false })
      .bindTooltip(`${esc(c.title || chestWhere(c))}<br>${fmt(n)} Items · ${agoText(c.t)}`)
      .on('click', () => openInv(c)).addTo(chests.layer);
  }
  for (const w of unopenedChests()) {
    L.marker(toLatLng(w.x + 0.5, w.z + 0.5), { icon: itemPin(w.block || 'minecraft:chest', '#8a8f98', 14, 'dim'), keyboard: false })
      .bindTooltip(`${esc(prettyItem(w.block))} bei ${w.x} ${w.y} ${w.z}<br>noch nie geöffnet`).addTo(chests.layer);
  }
  // Opened by someone else (purple ring)
  for (const l of chests.lids) {
    if (l.dim !== state.dim || !l.opens) continue;
    const who = l.open ? '<b>gerade offen</b>' : `zuletzt ${agoText(l.last)}`;
    L.circleMarker(toLatLng(l.x + 0.5, l.z + 0.5), { radius: 8, color: l.open ? '#ff4fd8' : '#c77dff', weight: 2.5, fill: false })
      .bindTooltip(`${esc(prettyItem(l.block))} bei ${l.x} ${l.y} ${l.z}<br>von jemand anderem geöffnet: ${l.opens}× · ${who}`).addTo(chests.layer);
  }
}
$('#show-chests').addEventListener('change', drawChests);
setInterval(() => { if (state.server && !document.hidden) loadContainers(); }, 10000);

// --- Chest window (like in game)

var inv = { stack: [], hl: null };   // stack: [{ chest, title, slots, items }]

function openInv(c, highlight) {
  inv.hl = highlight || null;
  inv.pick = new Set();
  inv.stack = [{ chest: c, title: c.title || chestWhere(c), slots: c.slots || 27, items: c.items }];
  renderInv();
  $('#inv-win').hidden = false;
  if (c.dim === state.dim && c.dim !== 'ender') {
    if (mode3d && view3d) view3d.flyTo(c.x + 0.5, c.y + 0.5, c.z + 0.5);
  }
}
function refreshInv() {
  const top = inv.stack[0];
  const c = chests.list.find((x) => x.key === top.chest.key);
  if (!c || inv.stack.length > 1) return;
  top.chest = c; top.items = c.items;
  renderInv();
}
function renderInv() {
  const w = inv.stack[inv.stack.length - 1];
  const c = inv.stack[0].chest;
  $('#inv-title').textContent = w.title;
  $('#inv-back').hidden = inv.stack.length < 2;
  const n = Math.max(9, Math.ceil(Math.max(w.slots, 1) / 9) * 9);
  const cols = w.slots === 5 ? 5 : w.slots === 9 && /dispenser|dropper|crafter/.test(c.block || '') ? 3 : 9;
  const grid = $('#inv-grid');
  grid.style.gridTemplateColumns = `repeat(${cols}, var(--slot, 40px))`;
  const slots = new Array(w.slots === 5 || cols === 3 ? w.slots : n).fill(null);
  let next = 0;
  for (const it of w.items) {
    let i = Number.isInteger(it.slot) && it.slot < slots.length && !slots[it.slot] ? it.slot : -1;
    if (i < 0) { while (next < slots.length && slots[next]) next++; i = next; }
    if (i >= slots.length) slots.push(it); else slots[i] = it;
  }
  grid.innerHTML = slots.map((it, i) => {
    if (!it) return '<div class="slot"></div>';
    const sh = (it.contents || []).length > 0;
    const hit = inv.hl && (it.id === inv.hl || (it.contents || []).some((x) => x.id === inv.hl));
    const pk = inv.stack.length === 1 && inv.pick && inv.pick.has(i);
    return `<div class="slot has ${sh ? 'shulker' : ''} ${hit ? 'hit' : ''} ${pk ? 'pick' : ''}" data-i="${i}">${itemIcon(it.id)}${it.count > 1 ? `<span class="n">${it.count}</span>` : ''}</div>`;
  }).join('');
  inv.slots = slots;
  $('#inv-info').textContent = c.dim === 'ender' ? `Enderkiste · ${agoText(c.t)}` : `${c.x} ${c.y} ${c.z} · ${agoText(c.t)}`;
  $('#inv-goto').hidden = c.dim === 'ender' || c.dim !== state.dim;
  const top = inv.stack.length === 1;
  const npick = top && inv.pick ? inv.pick.size : 0;
  $('#inv-fetch').hidden = !npick;
  $('#inv-fetch').textContent = `Holen (${npick} Stack${npick === 1 ? '' : 's'})`;
  $('#inv-all').hidden = !top;
}
function closeInv() { $('#inv-win').hidden = true; $('#inv-tip').hidden = true; inv.stack = []; }
$('#inv-close').addEventListener('click', closeInv);
$('#inv-win').addEventListener('click', (e) => { if (e.target.id === 'inv-win') closeInv(); });
$('#inv-back').addEventListener('click', () => { inv.stack.pop(); renderInv(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#inv-win').hidden) { if (inv.stack.length > 1) { inv.stack.pop(); renderInv(); } else closeInv(); } });
$('#inv-goto').addEventListener('click', () => {
  const c = inv.stack[0].chest;
  closeInv();
  if (mode3d && view3d) view3d.flyTo(c.x + 0.5, c.y + 0.5, c.z + 0.5);
  else { setFollow(false); map.setView(toLatLng(c.x + 0.5, c.z + 0.5), Math.max(map.getZoom(), 2)); }
});
$('#inv-grid').addEventListener('click', (e) => {
  const s = e.target.closest('.slot.has');
  if (!s || inv.stack.length !== 1) return;
  const i = Number(s.dataset.i);
  if (inv.pick.has(i)) inv.pick.delete(i); else inv.pick.add(i);
  renderInv();
});
$('#inv-all').addEventListener('click', () => {
  const all = inv.slots.map((it, i) => (it ? i : -1)).filter((i) => i >= 0);
  inv.pick = inv.pick.size === all.length ? new Set() : new Set(all);
  renderInv();
});
$('#inv-fetch').addEventListener('click', () => {
  const c = inv.stack[0].chest;
  const p = state.player;
  if (c.dim !== 'ender' && p && p.active && p.dim !== c.dim) { toast('Die Kiste ist in einer anderen Dimension'); return; }
  const slots = Array.from(inv.pick).map((i) => (Number.isInteger(inv.slots[i].slot) ? inv.slots[i].slot : i));
  baritone({ kind: 'fetch', x: c.x, y: c.y, z: c.z, slots });
  closeInv();
});
$('#inv-grid').addEventListener('dblclick', (e) => {
  const s = e.target.closest('.slot.shulker');
  if (!s) return;
  const it = inv.slots[Number(s.dataset.i)];
  inv.stack.push({ chest: inv.stack[0].chest, title: it.name || prettyItem(it.id), slots: 27, items: it.contents });
  $('#inv-tip').hidden = true;
  renderInv();
});
$('#inv-grid').addEventListener('mousemove', (e) => {
  const s = e.target.closest('.slot.has');
  const tip = $('#inv-tip');
  if (!s) { tip.hidden = true; return; }
  const it = inv.slots[Number(s.dataset.i)];
  const inner = it.contents || [];
  tip.innerHTML = `<div>${it.name ? `<span class="nm">${esc(it.name)}</span>` : esc(prettyItem(it.id))}${it.count > 1 ? ' × ' + it.count : ''}</div>`
    + (inner.length ? inner.slice(0, 6).map((x) => `<div class="row">${itemIcon(x.id, 16)} ${esc(prettyItem(x.id))} × ${x.count}</div>`).join('')
      + (inner.length > 6 ? `<div class="row">und ${inner.length - 6} weitere … (Doppelklick öffnet)</div>` : '<div class="row">Doppelklick öffnet</div>') : '');
  tip.hidden = false;
  tip.style.left = Math.min(window.innerWidth - tip.offsetWidth - 8, e.clientX + 14) + 'px';
  tip.style.top = Math.min(window.innerHeight - tip.offsetHeight - 8, e.clientY + 14) + 'px';
});
$('#inv-grid').addEventListener('mouseleave', () => { $('#inv-tip').hidden = true; });

export {
  agoText, chestKey, chests, chestWhere, fullId, inChests, invCounts, itemIcon, itemPin, itemsMeta,
  loadContainers, myInv, planTour, prettyItem, stillNeeded,
};
