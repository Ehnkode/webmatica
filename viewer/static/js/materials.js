/* Webmatica map UI: Schematics: material list, comparison with the world, shulker packing. */
'use strict';

// --- Material list and comparison

$$('.sp-tabs button').forEach((b) => b.addEventListener('click', () => {
  $$('.sp-tabs button').forEach((x) => x.classList.toggle('active', x === b));
  $$('.sp-pane').forEach((p) => p.classList.toggle('active', p.dataset.sppane === b.dataset.sptab));
  loadPaneData();
}));
const activeSpTab = () => ($('.sp-tabs button.active') || { dataset: { sptab: 'mats' } }).dataset.sptab;

async function loadPaneData() {
  const pl = selPl();
  if (!pl || pl.error) { $('#mat-list').innerHTML = ''; $('#mat-sum').textContent = ''; $('#chk-out').innerHTML = ''; return; }
  if (activeSpTab() === 'mats' && !schem.mats) {
    const id = pl.id;
    $('#mat-sum').textContent = 'Wird gezählt …';
    try {
      const m = await api(`/api/schematics/${encodeURIComponent(id)}/materials`);
      if (schem.sel !== id) return;
      schem.mats = m;
    } catch (e) { $('#mat-sum').textContent = 'Fehler: ' + e.message; return; }
  }
  renderMats();
  renderCheck();
  // Compare in the background so "Missing" doesn't count blocks already built
  if (activeSpTab() === 'mats' && schem.mats && !schem.check && !schem.checking) {
    schem.checking = pl.id;
    runCheck(true).finally(() => { schem.checking = null; });
  }
}

const STACK16 = /(_sign|_banner|ender_pearl|snowball|egg|honey_bottle|armor_stand|bucket)$/;
const STACK1 = /(shulker_box|_bed|_bucket|minecart|_boat|_raft|cake|potion)$/;
const stackOf = (item) => (STACK1.test(item) ? 1 : STACK16.test(item) ? 16 : 64);
function stacksText(item, n) {
  const s = stackOf(item);
  const st = Math.floor(n / s), rest = n % s;
  const parts = [];
  if (st) parts.push(`${st}×${s}`);
  if (rest) parts.push(`${rest}`);
  const sh = n / (27 * s);
  return parts.join(' + ') + (sh >= 0.5 ? ` · ${sh.toFixed(1).replace('.', ',')} Shulker` : '');
}
const itemName = (it) => it.replace('minecraft:', '').replace(/_/g, ' ');

/* Missing items: what still has to be placed, minus inventory (including shulkers in it), minus remembered chests */
function missingList() {
  const m = schem.mats;
  if (!m) return null;
  const built = !!(schem.check && schem.check.missingItems);
  const base = built ? schem.check.missingItems : m.items;
  const inv = invCounts();
  const rows = [];
  for (const [it, n] of base) {
    const id = fullId(it);
    const have = Math.min(n, inv.get(id) || 0);
    const box = Math.min(n - have, inChests(id).total);
    const miss = n - have - box;
    if (have + box < n || box) rows.push({ it, n, have, box, miss });
  }
  rows.sort((a, b) => b.miss - a.miss || b.box - a.box);
  return { built, rows, missing: rows.filter((r) => r.miss > 0), fetch: rows.filter((r) => !r.miss && r.box) };
}
function renderMissing() {
  const el = $('#mat-miss');
  const L = missingList();
  if (!L) { el.innerHTML = ''; return; }
  const src = L.built ? 'noch nicht gebaut' : 'ganze Schematic (Abgleich läuft …)';
  const minus = [myInv ? 'Inventar' : null, chests.list.length ? `${chests.list.length} gemerkte Kisten` : null].filter(Boolean).join(' und ') || 'nichts (Inventar und Kisten unbekannt)';
  const total = L.missing.reduce((a, r) => a + r.miss, 0);
  const sh = L.missing.reduce((a, r) => a + r.miss / (27 * stackOf(r.it)), 0);
  if (!L.missing.length) {
    spBadge('mats', 'alles da', 'good');
    el.innerHTML = `<div class="card-title">Fehlende Items</div><p class="ok-hint">Nichts fehlt: alles ist im Inventar oder in deinen Kisten${L.fetch.length ? `, ${L.fetch.length} ${L.fetch.length === 1 ? 'Sorte muss' : 'Sorten müssen'} noch aus Kisten geholt werden (Tab „Bauen“ → Autobau)` : ''}.</p>
      <p class="hint">Gezählt: ${src}, abgezogen: ${minus}.</p>`;
    return;
  }
  spBadge('mats', `${L.missing.length} fehlen`, 'warn');
  el.innerHTML = `<div class="card-title">Fehlende Items <span class="muted">${L.missing.length} Sorten · ${fmt(total)} Items · etwa ${Math.max(1, Math.ceil(sh))} Shulker</span>
      <button id="miss-copy" title="Liste als Text kopieren">Kopieren</button></div>
    <p class="hint">Gezählt: ${src}, abgezogen: ${minus}. Diese Items hast du nirgends, sie müssen gefarmt oder gekauft werden.${L.fetch.length ? ` ${L.fetch.length} weitere Sorten liegen komplett in Kisten.` : ''}</p>
    <table class="mats miss"><tbody>${L.missing.map((r) => `<tr><td>${itemIcon(r.it, 20)} ${esc(itemName(r.it))}</td>
      <td class="n miss-n">${fmt(r.miss)}</td><td class="s">${r.miss >= stackOf(r.it) ? stacksText(r.it, r.miss) : ""}</td>
      <td class="s">${r.have || r.box ? `<span class="muted" title="von ${fmt(r.n)} benötigt">${[r.have ? fmt(r.have) + ' dabei' : '', r.box ? fmt(r.box) + ' in Kisten' : ''].filter(Boolean).join(', ')}</span>` : ''}</td></tr>`).join('')}</tbody></table>`;
}
$('#mat-miss').addEventListener('click', (e) => {
  if (!e.target.closest('#miss-copy')) return;
  const L = missingList();
  if (!L) return;
  const txt = L.missing.map((r) => `${itemName(r.it)}: ${r.miss} (${stacksText(r.it, r.miss)})`).join('\n');
  navigator.clipboard.writeText(txt).then(() => toast('Liste kopiert'), () => toast('Kopieren nicht möglich'));
});

function renderMats() {
  const m = schem.mats;
  if (!m) return;
  renderMissing();
  const f = $('#mat-filter').value.trim().toLowerCase();
  const done = new Set(prefs['done:' + schem.sel] || []);
  const rows = m.items.filter(([it]) => !f || it.includes(f.replace(/ /g, '_')));
  const total = m.items.reduce((a, [, n]) => a + n, 0);
  const shulkers = m.items.reduce((a, [it, n]) => a + n / (27 * stackOf(it)), 0);
  $('#mat-sum').textContent = `${fmt(total)} Items, ${m.items.length} Sorten, etwa ${Math.ceil(shulkers)} Shulker voll`;
  $('#mat-list').innerHTML = rows.map(([it, n]) => `
    <tr class="${done.has(it) ? 'done' : ''}" data-item="${esc(it)}"><td><input type="checkbox" ${done.has(it) ? 'checked' : ''} title="Abhaken"></td>
    <td>${itemIcon(it, 20)} ${esc(itemName(it))}</td><td class="n">${fmt(n)}</td><td class="s">${stacksText(it, n)}</td>${chestCell(it)}</tr>`).join('');
}
/* "In chests" cell for material and packing lists */
function chestCell(it) {
  if (!chests.list.length) return '';
  const k = inChests(it.includes(':') ? it : 'minecraft:' + it);
  if (!k.total) return '<td class="s muted">–</td>';
  const tip = k.where.slice(0, 4).map(([n, c]) => `${n} in ${chestWhere(c)}`).join('\n');
  return `<td class="s chest-have" title="${esc(tip)}">${fmt(k.total)} in Kisten</td>`;
}
$('#mat-filter').addEventListener('input', renderMats);
$('#mat-list').addEventListener('change', (e) => {
  const tr = e.target.closest('tr[data-item]');
  if (!tr) return;
  const key = 'done:' + schem.sel;
  const done = new Set(prefs[key] || []);
  if (e.target.checked) done.add(tr.dataset.item); else done.delete(tr.dataset.item);
  savePref(key, Array.from(done));
  renderMats();
});
$('#mat-csv').addEventListener('click', () => {
  const m = schem.mats, pl = selPl();
  if (!m || !pl) return;
  const csv = 'Item;Anzahl;Stacks\n' + m.items.map(([it, n]) => `${itemName(it)};${n};${stacksText(it, n)}`).join('\n');
  const url = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv' }));
  const a = Object.assign(document.createElement('a'), { href: url, download: `${pl.name}-materialien.csv` });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
});

async function runCheck(quiet) {
  const pl = selPl();
  if (!pl) return;
  const btn = $('#chk-run');
  if (!quiet) { btn.disabled = true; btn.textContent = 'Gleiche ab …'; }
  try {
    if (pl._dirty) await flushSchems();
    const c = await api(`/api/schematics/${encodeURIComponent(pl.id)}/verify`);
    if (selPl() !== pl) return;
    schem.check = c;
    pl._pct = Math.floor((c.stats.ok / Math.max(1, c.stats.total)) * 100);
    if (view3d && $('#chk-show').checked) view3d.setVerify(pl.id, c.points);
    renderCheck();
    renderMats();
    renderPlaced();
  } catch (e) { if (!quiet) $('#chk-out').innerHTML = `<p class="err">${esc(e.message)}</p>`; }
  finally { if (!quiet) { btn.disabled = false; btn.textContent = 'Jetzt abgleichen'; } }
}
$('#chk-run').addEventListener('click', () => runCheck(false));
/* Live: re-compare every 3 s while building (the mod writes changes after ~1.5 s) */
let chkBusy = false;
setInterval(async () => {
  if (!$('#chk-live').checked || !selPl() || chkBusy || document.hidden) return;
  chkBusy = true;
  try { await runCheck(true); } finally { chkBusy = false; }
}, 3000);
$('#chk-live').addEventListener('change', (e) => { if (e.target.checked) runCheck(true); });
$('#chk-out').addEventListener('click', (e) => {
  const tr = e.target.closest('tr[data-goto]');
  if (!tr) return;
  const [x, y, z] = tr.dataset.goto.split(',').map(Number);
  if (mode3d && view3d) view3d.flyTo(x + 0.5, y + 0.5, z + 0.5);
  else map.setView(toLatLng(x + 0.5, z + 0.5), Math.max(map.getZoom(), 3));
});
$('#chk-show').addEventListener('change', (e) => {
  if (view3d && schem.check) view3d.setVerify(schem.sel, e.target.checked ? schem.check.points : null);
});

/* Distribute missing items into shulkers: full stacks, largest amounts first, 27 slots per box. */
function packShulkers(items) {
  const out = [];
  let cur = [], free = 27;
  for (const [it, n] of items.slice().sort((a, b) => b[1] - a[1])) {
    let left = Math.ceil(n / stackOf(it)), rest = n;
    while (left > 0) {
      if (!free) { out.push(cur); cur = []; free = 27; }
      const take = Math.min(left, free);
      const cnt = Math.min(rest, take * stackOf(it));
      cur.push([it, cnt]);
      left -= take; free -= take; rest -= cnt;
    }
  }
  if (cur.length) out.push(cur);
  return out;
}
function stacksShort(item, n) {
  const s = stackOf(item), st = Math.floor(n / s), r = n % s;
  return st ? (r ? `${st}×${s}+${r}` : `${st}×${s}`) : `${r}`;
}

function renderCheck() {
  const c = schem.check;
  if (!c) { $('#chk-out').innerHTML = ''; return; }
  const s = c.stats;
  const tot = Math.max(1, s.total);
  const pct = (n) => (n / tot) * 100;
  const keys = [['ok', 'richtig'], ['rotated', 'falsch gedreht'], ['wrong', 'falscher Block'], ['missing', 'fehlt'], ['unknown', 'nicht aufgezeichnet']];
  const colors = { ok: '#22a06b', rotated: '#ffe066', wrong: '#e5484d', missing: '#3e9bf5', unknown: '#555b66' };
  spBadge('check', `${Math.floor(pct(s.ok))} % fertig`, s.ok >= s.total ? 'good' : '');
  $('#chk-out').innerHTML = `
    <div class="bar-chk">${keys.map(([k]) => `<span style="width:${pct(s[k])}%;background:${colors[k]}"></span>`).join('')}</div>
    <div class="chk-stats">
      <b>${Math.floor(pct(s.ok))} % fertig</b><span class="mono">${fmt(s.ok)} / ${fmt(s.total)}</span>
      ${keys.slice(1).map(([k, l]) => `<span class="k-${k}">${l}</span><span class="mono">${fmt(s[k])}</span>`).join('')}
      <span class="k-extra">muss weg</span><span class="mono">${fmt(s.extra)}</span>
    </div>
    ${s.wrong + s.rotated + s.extra ? `<div class="sp-row chk-fix"><button id="chk-fix" title="Baritone baut diese Blöcke ab und sammelt sie ein. Danach setzt der Autobau oder der Printer die richtigen.">Falsche Blöcke abbauen (${fmt(s.wrong + s.rotated)})</button>
      ${s.extra ? `<label class="check" title="Blöcke, wo die Schematic Luft hat"><input type="checkbox" id="chk-fix-extra"${schem.fixExtra ? ' checked' : ''}> auch „muss weg“ (${fmt(s.extra)})</label>` : ''}</div>` : ''}
    ${c.todo && c.todo.length ? `<h4>Als Nächstes bauen <span class="muted">(${fmt(c.todoTotal)} offen, unten zuerst${state.player && state.player.active ? ', dann nächste zu dir' : ''})</span></h4>
      <table class="mats todo"><tbody>${c.todo.slice(0, 80).map((t) => `<tr data-goto="${t[0]},${t[1]},${t[2]}" title="Hinfliegen"><td class="mono">${t[0]} ${t[1]} ${t[2]}</td>
      <td>${esc(itemName(t[3]))}</td><td class="k-${t[4]}">${t[4] === 'missing' ? 'fehlt' : t[4] === 'wrong' ? 'statt ' + esc(itemName(t[5] || '')) : 'drehen'}</td></tr>`).join('')}</tbody></table>` : ''}
    ${(() => {
      const need = stillNeeded(c.missingItems);
      if (!c.missingItems.length) return '';
      const tour = planTour(need);
      const carried = invCounts();
      return `<h4>Noch zu besorgen <span class="muted">${myInv ? '(abzüglich deinem Inventar)' : '(Inventar unbekannt: Spiel nicht verbunden)'}</span></h4>
      <table class="mats"><tbody>${c.missingItems.map(([it, n]) => { const full = fullId(it), have = carried.get(full) || 0;
        return `<tr class="${have >= n ? 'done' : ''}"><td>${itemIcon(it, 20)} ${esc(itemName(it))}</td><td class="n">${fmt(n)}</td>
        <td class="s ${have ? 'inv-have' : 'muted'}">${have ? fmt(Math.min(have, n)) + ' dabei' : '–'}</td>${chestCell(it)}</tr>`; }).join('')}</tbody></table>
      ${need.length ? `<h4>Packliste <span class="muted">(${packShulkers(need).length} Shulker)</span></h4>
      <ol class="pack">${packShulkers(need).map((sh) => `<li>${sh.map(([it, n]) => `${itemIcon(it, 16)} ${esc(itemName(it))} <span class="mono">${stacksShort(it, n)}</span>`).join(' · ')}</li>`).join('')}</ol>` : '<p class="hint ok-hint">Du hast alles dabei. Los geht\'s!</p>'}
      ${tour.stops.length ? `<h4>Holtour <span class="muted">(${tour.stops.length} Kisten, nächste zuerst)</span> <button data-tour-hl title="Alle Kisten der Tour im Spiel mit Lichtsäulen markieren">Im Spiel zeigen</button></h4>
      <ol class="tour">${tour.stops.map((st, i) => `<li data-tour="${i}"><div><b>${esc(st.chest.title || chestWhere(st.chest))}</b>
        <span class="muted">${st.dist != null ? Math.round(st.dist) + ' m' : ''}</span></div>
        <div class="take">${st.take.map(([it, n]) => `${itemIcon(it, 16)} ${esc(prettyItem(it))} <span class="mono">${stacksShort(chestKey(it), n)}</span>`).join(' · ')}</div>
        <div class="btns"><button data-tour-fetch title="Baritone läuft hin und nimmt genau diese Items">Holen</button><button data-tour-open>Öffnen</button>${st.chest.dim === state.dim ? '<button data-tour-go title="Baritone läuft hin">Hinlaufen</button>' : ''}</div></li>`).join('')}</ol>` : ''}
      ${tour.missing.length ? `<p class="hint">In keiner gemerkten Kiste: ${tour.missing.map(([it, n]) => `${esc(prettyItem(it))} (${fmt(n)})`).join(', ')}</p>` : ''}`;
    })()}
    ${s.unknown ? '<p class="hint">Teile sind nicht aufgezeichnet: einmal mit der Mod hinfliegen, dann neu abgleichen.</p>' : ''}`;
}
