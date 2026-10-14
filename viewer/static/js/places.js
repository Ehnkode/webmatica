/* Webmatica map UI: Waypoints, Baritone commands, activity indicator, signs and stashes. */
import './sidebar.js';   // runs before this file (load order, see index.html)
import { $, api, esc, fromLatLng, map, overlay, state, toast, toLatLng } from './core.js';
import { fillGotoList, toggleSide } from './sidebar.js';
import { setFollow, view3d } from './player.js';
import { jsonPost, selectSchem } from './schematics.js';
import { itemPin, itemsMeta } from './chests.js';
import { AB_PHASE } from './automation.js';
import { farmRunning } from './farm.js';

// ------------------------------------------------------------------ Waypoints

async function loadWaypoints() {
  try { state.waypoints = await api('/api/waypoints'); } catch (_) { state.waypoints = []; }
  drawWaypoints();
}

async function saveWaypoints() {
  state.waypoints = await api('/api/waypoints', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(state.waypoints),
  });
  drawWaypoints();
}

function drawWaypoints() {
  fillGotoList();
  overlay.waypoints.clearLayers();
  if (view3d) view3d.setWaypoints(state.waypoints);
  const here = state.waypoints.filter((w) => w.server === state.server && w.dim === state.dim);
  for (const w of here) {
    const icon = L.divIcon({ className: '', html: `<div class="wp-pin" style="background:${esc(w.color)}"></div>`, iconSize: [14, 14], iconAnchor: [7, 7] });
    L.marker(toLatLng(w.x + 0.5, w.z + 0.5), { icon, title: w.name })
      .bindTooltip(esc(w.name), { permanent: true, direction: 'right', offset: [8, 0], className: 'wp-label' })
      .addTo(overlay.waypoints);
  }
  const list = $('#wp-list');
  list.innerHTML = here.length ? here.map((w) => `
    <li data-id="${esc(w.id)}">
      <span class="swatch" style="background:${esc(w.color)}"></span>
      <div class="main"><div class="title">${esc(w.name)}</div><div class="sub mono">${w.x} ${w.y} ${w.z}</div></div>
      <button data-del title="Löschen">✕</button>
    </li>`).join('') : '<li class="empty">Noch keine Wegpunkte in dieser Dimension.</li>';
}

$('#wp-list').addEventListener('click', async (e) => {
  const li = e.target.closest('li[data-id]');
  if (!li) return;
  const w = state.waypoints.find((x) => x.id === li.dataset.id);
  if (!w) return;
  if (e.target.closest('[data-del]')) {
    if (!confirm(`Wegpunkt „${w.name}“ löschen?`)) return;
    state.waypoints = state.waypoints.filter((x) => x !== w);
    await saveWaypoints();
    return;
  }
  setFollow(false);
  map.setView(toLatLng(w.x, w.z), Math.max(map.getZoom(), 0));
});

const WP_COLORS = ['#e5484d', '#3e9bf5', '#f5a524', '#22a06b', '#a970ff', '#ffffff'];
map.on('contextmenu', (e) => {
  const { x, z } = fromLatLng(e.latlng);
  const d = document.createElement('div');
  d.className = 'ctx-pop';
  d.innerHTML = `<div class="muted mono">${x} ${z}</div>
    <button data-ctx="wp">Wegpunkt setzen</button>
    <button data-ctx="goto">Hierhin laufen (Baritone)</button>
    <button data-ctx="stop">Baritone stoppen</button>`;
  d.addEventListener('click', async (ev) => {
    const b = ev.target.closest('[data-ctx]');
    if (!b) return;
    map.closePopup();
    if (b.dataset.ctx === 'goto') return baritone({ kind: 'goto', x, z });
    if (b.dataset.ctx === 'stop') return baritone({ kind: 'stop' });
    let y = 64;
    try {
      const c = await api('/api/column?' + new URLSearchParams({ srv: state.server, dim: state.dim, layer: state.layer, x, z }));
      if (c.y !== undefined) y = c.y + 1;
    } catch (_) { /* height unknown */ }
    const name = prompt(`Wegpunkt bei ${x} ${y} ${z}`, 'Wegpunkt');
    if (!name) return;
    state.waypoints.push({ name, server: state.server, dim: state.dim, x, y, z, color: WP_COLORS[state.waypoints.length % WP_COLORS.length] });
    await saveWaypoints();
    toast('Wegpunkt gespeichert');
  });
  L.popup({ closeButton: false, className: 'ctx' }).setLatLng(e.latlng).setContent(d).openOn(map);
});

/* Baritone: drop off a command, the mod picks it up and reports back */
async function baritone(cmd) {
  const p = state.player;
  if (!p || !p.active) { toast('Spieler ist nicht online, Baritone kann nicht starten'); return; }
  if (cmd.kind === 'goto' && p.dim !== state.dim) { toast('Du bist gerade in einer anderen Dimension'); return; }
  try {
    const c = await jsonPost('/api/commands', { srv: p.server || state.server, ...cmd });
    toast({ goto: `Baritone läuft nach ${cmd.x} ${cmd.z} …`, stop: 'Baritone wird gestoppt …', litematica: 'Baritone fängt an zu bauen …',
      clear: 'Baritone räumt den Bauplatz frei …', pause: 'Baritone pausiert …', resume: 'Baritone macht weiter …',
      highlight: 'Kisten werden im Spiel markiert …', fetch: 'Baritone holt die Items …', store: 'Baritone lagert die Items ein …' }[cmd.kind] || 'Baritone …');
    const long = cmd.kind === 'fetch' || cmd.kind === 'store';
    if (long || cmd.kind === 'goto' || cmd.kind === 'litematica' || cmd.kind === 'clear') {
      act.manual = { kind: cmd.kind, since: Date.now(), text: { goto: `läuft nach ${cmd.x} ${cmd.z}`, fetch: 'holt Items aus einer Kiste',
        litematica: 'baut mit Baritone', clear: 'räumt den Bauplatz frei', store: 'lagert ein' }[cmd.kind] };
      renderActivity();
    }
    const tries = long ? 400 : 12;   // Fetching takes time: walk there, open, take
    for (let i = 0; i < tries; i++) {
      await new Promise((r) => setTimeout(r, 500));
      const last = await api('/api/commands/last?' + new URLSearchParams({ id: c.id }));
      if (last && last.id === c.id) {
        toast(`Baritone: ${last.msg}`);
        if (long && act.manual && act.manual.since && cmd.kind === act.manual.kind) { act.manual = null; renderActivity(); }
        return;
      }
    }
    toast('Keine Antwort aus dem Spiel (ist die Mod aktiv?)');
  } catch (e) { toast('Baritone: ' + e.message); }
}

/* Activity at the top center: what the game is doing right now, with a stop for everything */
var act = { auto: null, online: false, manual: null };
async function pollActivity() {
  if (document.hidden || !state.server) return;
  const srv = (state.player && state.player.server) || state.server;
  try {
    const [a, c] = await Promise.all([api('/api/auto?' + new URLSearchParams({ srv })), api('/api/control/state?' + new URLSearchParams({ srv }))]);
    act.auto = a; act.online = !!(c && c.online);
  } catch (_) { act.online = false; }
  if (act.manual && Date.now() - act.manual.since > 180000) act.manual = null;
  renderActivity();
}
/* Dot on the automation button while anything is playing for you */
function autoBadge() {
  let on = autoRunning(act.auto) || !!act.manual;
  try { on = on || farmRunning(); } catch (_) { /* not loaded yet */ }
  $('#auto-badge').hidden = !on;
}
const autoRunning = (a) => a && ['plan', 'fetch', 'approach', 'build', 'unpack', 'store', 'return'].includes(a.phase);
/* Only show when something is really running or has just finished/been cancelled; otherwise hidden */
function renderActivity() {
  const el = $('#activity');
  const a = act.auto;
  let text = null, kind = 'run';
  if (autoRunning(a)) {
    text = `<b>${a.mode === 'store' ? 'Einlagern' : 'Autobau'}</b> ${esc(a.info || AB_PHASE[a.phase] || '')}`;
  } else if (act.manual) {
    text = `<b>Baritone</b> ${esc(act.manual.text)}`;
  } else if (a && (a.phase === 'error' || a.phase === 'done') && a.log && a.log.length) {
    const t = a.log[a.log.length - 1][0];
    if (Date.now() - t < 60000 && t !== act.dismissed) {
      kind = a.phase === 'error' ? 'bad' : 'ok';
      text = `<b>${a.mode === 'store' ? 'Einlagern' : 'Autobau'} ${a.phase === 'error' ? 'angehalten' : 'fertig'}</b> ${esc(a.info || '')}`;
      act.lastT = t;
    }
  }
  el.hidden = !text;
  autoBadge();
  if (!text) return;
  el.className = `panel activity ${kind}`;
  $('#act-text').innerHTML = text;
  $('#act-stop').hidden = kind !== 'run';
  $('#act-x').hidden = kind === 'run';
}
$('#act-x').addEventListener('click', () => { act.dismissed = act.lastT; renderActivity(); });
$('#act-stop').addEventListener('click', async () => {
  const srv = (state.player && state.player.server) || state.server;
  try { await jsonPost('/api/commands', { srv, kind: 'stop' }); toast('Alles gestoppt'); } catch (e) { toast('Stopp: ' + e.message); }
  act.manual = null;
  pollActivity();
});
$('#act-text').addEventListener('click', () => {
  const a = act.auto;
  if (a && a.mode === 'store') { toggleSide(true, 'chests'); return; }
  if (a && a.pid) {
    selectSchem(a.pid);
    const b = $('.sp-tabs button[data-sptab="build"]');
    if (b) b.click();
  }
});
setInterval(pollActivity, 1500);

// ------------------------------------------------------------------ Signs + stashes

async function loadMarkers() {
  try {
    state.markers = await api('/api/markers?' + new URLSearchParams({ srv: state.server, dim: state.dim, layer: state.layer }));
  } catch (_) { state.markers = { signs: [], stashes: [] }; }
  drawMarkers();
}

function drawMarkers() {
  overlay.signs.clearLayers();
  overlay.stashes.clearLayers();
  const filter = $('#sign-filter').value.trim().toLowerCase();
  const signs = state.markers.signs.filter((s) => !filter || s.lines.join(' ').toLowerCase().includes(filter));
  if ($('#show-signs').checked) {
    for (const s of signs) {
      const icon = itemsMeta ? itemPin(s.block || 'minecraft:oak_sign', '#c9a36b', 16)
        : L.divIcon({ className: '', html: '<div class="sign-pin"></div>', iconSize: [12, 12], iconAnchor: [6, 6] });
      L.marker(toLatLng(s.x + 0.5, s.z + 0.5), { icon })
        .bindPopup(`<div class="sign-text">${esc(s.lines.join('\n'))}</div><div class="muted mono">${s.x} ${s.y} ${s.z}</div>`)
        .addTo(overlay.signs);
    }
  }
  $('#sign-list').innerHTML = signs.length ? signs.slice(0, 500).map((s) => `
    <li data-x="${s.x}" data-z="${s.z}"><div class="main"><div class="title">${esc(s.lines.join(' · '))}</div>
    <div class="sub mono">${s.x} ${s.y} ${s.z}</div></div></li>`).join('')
    : '<li class="empty">Keine Schilder gefunden.</li>';

  const min = Number($('#stash-min').value) || 1;
  const stashes = state.markers.stashes.filter((s) => s.count >= min).sort((a, b) => b.count - a.count);
  if ($('#show-stashes').checked) {
    for (const s of stashes) {
      const r = Math.min(26, 6 + Math.sqrt(s.count) * 2);
      L.circleMarker(toLatLng(s.cx * 16 + 8, s.cz * 16 + 8), { radius: r, color: '#f5a524', weight: 2, fillOpacity: 0.25 })
        .bindPopup(stashText(s)).addTo(overlay.stashes);
    }
  }
  $('#stash-list').innerHTML = stashes.length ? stashes.slice(0, 500).map((s) => `
    <li data-x="${s.cx * 16 + 8}" data-z="${s.cz * 16 + 8}"><div class="main"><div class="title">${s.count} Behälter</div>
    <div class="sub">${esc(Object.entries(s.types).map(([k, v]) => `${v}× ${k}`).join(', '))}</div>
    <div class="sub mono">Chunk ${s.cx} ${s.cz} · Block ${s.cx * 16 + 8} ${s.cz * 16 + 8}</div></div></li>`).join('')
    : '<li class="empty">Nichts über der Schwelle.</li>';
}

function stashText(s) {
  return `<b>${s.count} Behälter</b><br>${esc(Object.entries(s.types).map(([k, v]) => `${v}× ${k}`).join(', '))}<br><span class="muted mono">${s.cx * 16} ${s.cz * 16} … ${s.cx * 16 + 15} ${s.cz * 16 + 15}</span>`;
}

['#sign-filter', '#stash-min'].forEach((s) => $(s).addEventListener('input', drawMarkers));
['#show-signs', '#show-stashes'].forEach((s) => $(s).addEventListener('change', drawMarkers));
['#sign-list', '#stash-list'].forEach((s) => $(s).addEventListener('click', (e) => {
  const li = e.target.closest('li[data-x]');
  if (!li) return;
  setFollow(false);
  map.setView(toLatLng(Number(li.dataset.x), Number(li.dataset.z)), Math.max(map.getZoom(), 1));
}));

export { autoBadge, baritone, drawMarkers, drawWaypoints, loadMarkers, loadWaypoints };
