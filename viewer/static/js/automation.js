/* Webmatica map UI: Building automation: Baritone, printer, auto-build, guard switches, storing. */
import './keyboard.js';   // runs before this file (load order, see index.html)
import { $, $$, api, esc, fmt, renderChecklist, state, toast } from './core.js';
import { baritone } from './places.js';
import { gameChecks, gameStatus, statusText } from './player.js';
import { jsonPost, schem, selPl, spBadge } from './schematics.js';
import { runCheck } from './materials.js';
import { loadContainers, planTour, stillNeeded } from './chests.js';
import { renderQueue } from './farm.js';

// ------------------------------------------------------------------ Building: Baritone, printer, layers

function highlightChests(list) {
  const p = state.player;
  const pts = list.filter((c) => c.dim !== 'ender' && (!p || c.dim === p.dim)).map((c) => [c.x, c.y, c.z]);
  if (!pts.length) { toast('Keine dieser Kisten ist in deiner Dimension'); return; }
  baritone({ kind: 'highlight', pts });
}

var ctl = { state: null, busy: false, pending: 0 };
async function pollControl() {
  if ($('#schem-panel').hidden || document.hidden || !state.server) return;
  const srv = (state.player && state.player.server) || state.server;
  try { ctl.state = await api('/api/control/state?' + new URLSearchParams({ srv })); } catch (_) { ctl.state = null; }
  try { ctl.auto = await api('/api/auto?' + new URLSearchParams({ srv })); } catch (_) { ctl.auto = null; }
  renderBuild();
  renderAuto();
}

/* Start check for auto-build (instead of a confirm dialog) */
let abCheckAt = 0;
function abChecks(pl) {
  const items = gameChecks(true);
  if (!pl) return renderChecklist($('#ab-check'), items);
  items.push(pl.ingame ? { ok: true, text: 'Schematic ist im Spiel (Litematica)' }
    : { ok: false, text: 'Schematic ist noch nicht im Spiel', action: { id: 'ingame', label: 'Ins Spiel' } });
  const c = schem.check && schem.checkPid === pl.id ? schem.check : null;
  if (!c && Date.now() - abCheckAt > 10000 && !$('#schem-panel').hidden) { abCheckAt = Date.now(); runCheck(true); }
  if (c) {
    const st = c.stats, todo = st.missing + st.wrong;
    items.push(st.unknown ? { ok: null, text: `${Math.round(100 * st.unknown / Math.max(1, st.total))} % der Baustelle noch nie aufgezeichnet: Baritone läuft erst hin` }
      : { ok: true, text: 'Baustelle ist aufgezeichnet' });
    if (todo) {
      const tour = planTour(stillNeeded(c.missingItems || []));
      const short = tour.missing.reduce((n, [, k]) => n + k, 0);
      items.push(short ? { ok: null, text: `${fmt(short)} Items in keiner bekannten Kiste (${tour.missing.slice(0, 3).map(([it]) => esc(it.split(':').pop())).join(', ')}${tour.missing.length > 3 ? ' …' : ''})` }
        : { ok: true, text: `Material für ${fmt(todo)} Blöcke liegt in deinen Kisten oder im Inventar` });
    } else items.push({ ok: st.unknown ? null : true, text: st.unknown ? 'Nichts fehlt im aufgezeichneten Teil' : 'Alles gebaut' });
  }
  return renderChecklist($('#ab-check'), items);
}
$('#ab-check').addEventListener('click', (e) => {
  const b = e.target.closest('[data-check-action]');
  if (b && b.dataset.checkAction === 'ingame') $('#sp-ingame').click();
});

/* Auto-build: fetch and build alternately (computed by the viewer, see autobuild.py) */
const AB_PHASE = { fix: 'korrigiert falsche Blöcke', fplan: 'plant', mine: 'baut ab', deposit: 'lagert ein', unpack: 'packt Shulker aus', plan: 'plant', fetch: 'holt Material', approach: 'läuft zur Baustelle', build: 'baut', return: 'bringt Reste zurück', done: 'fertig', error: 'angehalten', off: 'aus' };
function renderAuto() {
  const a = ctl.auto, pl = selPl();
  const running = a && (a.mode || 'build') === 'build' && ['plan', 'fetch', 'approach', 'build', 'unpack', 'return', 'fix'].includes(a.phase);
  const mine = running && pl && a.pid === pl.id;
  renderQueue(a, running);
  $('#ab-toggle').textContent = running ? '■ Autobau stoppen' : '▶ Automatisch holen und bauen';
  spBadge('build', mine ? (AB_PHASE[a.phase] || 'läuft') : 'Autobau', mine ? 'good' : '');
  $('#ab-toggle').classList.toggle('danger', !!running);
  $('#ab-toggle').classList.add('primary');
  const ready = running || abChecks(pl);
  $('#ab-check').hidden = !!running;
  $('#ab-toggle').disabled = !ready;
  $('#ab-toggle').title = ready ? '' : 'Erst die rot markierten Punkte oben erledigen';
  if (!a || a.mode === 'store' || a.mode === 'farm' || a.mode === 'flatten' || a.phase === 'off' && !a.log) { $('#ab-info').textContent = ''; $('#ab-log').innerHTML = ''; return; }
  $('#ab-info').innerHTML = `<b class="${a.phase === 'error' ? 'bad' : a.phase === 'done' ? 'ok' : a.paused ? 'warn' : ''}">${running && a.paused ? 'pausiert' : AB_PHASE[a.phase] || a.phase}</b>` +
    (running && !mine ? ' (andere Schematic)' : '') + (a.info ? ' · ' + esc(a.info) : '') + (a.fetched ? ` · ${a.fetched}× geholt` : '');
  $('#ab-log').innerHTML = (a.log || []).slice(-8).reverse().map(([t, m]) =>
    `<li><span class="mono muted">${new Date(t).toLocaleTimeString(I18N.locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span> ${esc(m)}</li>`).join('');
}
/* Guard switches for auto-build (player pause, food, tools, leftovers) */
const guard = { cfg: null };
function renderGuard() {
  const g = guard.cfg;
  if (!g) return;
  $$('[data-guard]').forEach((c) => { c.checked = !!g[c.dataset.guard]; });
  if (document.activeElement !== $('#gd-dist')) $('#gd-dist').value = g.radarDist;
  $('#gd-engine').value = g.engine || 'auto';
  if (document.activeElement !== $('#gd-friends')) $('#gd-friends').value = (g.friends || []).join(', ');
  const on = [g.radar && 'Spieler-Pause', g.eat && 'Essen', g.tools && 'Werkzeug', g.fix && 'Korrektur', g.leftovers && 'Reste', g.dig && 'Graben erlaubt',
    g.engine === 'native' && 'eigene Engine', g.engine === 'baritone' && 'nur Baritone'].filter(Boolean);
  $('#gd-sum').textContent = on.length ? '· ' + on.join(', ') : '· alles aus';
}
async function saveGuard(patch) {
  try { guard.cfg = await api('/api/auto/settings', { method: 'POST', body: JSON.stringify(patch) }); } catch (e) { toast('Speichern: ' + e.message); }
  renderGuard();
}
$$('[data-guard]').forEach((c) => c.addEventListener('change', () => saveGuard({ [c.dataset.guard]: c.checked })));
$('#gd-dist').addEventListener('change', (e) => saveGuard({ radarDist: Number(e.target.value) || 64 }));
$('#gd-engine').addEventListener('change', (e) => saveGuard({ engine: e.target.value }));
$('#gd-friends').addEventListener('change', (e) => saveGuard({ friends: e.target.value.split(',') }));
api('/api/auto/settings').then((g) => { guard.cfg = g; renderGuard(); }).catch(() => {});

/* Storing (chests tab): runs in the same flow as auto-build */
const storeUi = { timer: null, running: false };
async function pollStore() {
  const srv = (state.player && state.player.server) || state.server;
  let a = null;
  try { a = await api('/api/auto?' + new URLSearchParams({ srv })); } catch (_) { /* ignore */ }
  const running = storeUi.running = !!(a && a.mode === 'store' && a.phase === 'store');
  if (running && !storeUi.timer) storeUi.timer = setInterval(pollStore, 1500);   // e.g. after reloading the page
  $('#st-go').textContent = running ? '■ Einlagern stoppen' : 'Inventar einlagern';
  if (a && a.mode === 'store') {
    $('#st-info').innerHTML = `<b class="${a.phase === 'error' ? 'bad' : a.phase === 'done' ? 'ok' : ''}">${running ? 'lagert ein' : a.phase === 'done' ? 'fertig' : 'angehalten'}</b> · ${esc(a.info || '')}`;
  }
  if (!running && storeUi.timer) { clearInterval(storeUi.timer); storeUi.timer = null; loadContainers(); }
}
$('#st-go').addEventListener('click', async () => {
  const srv = (state.player && state.player.server) || state.server;
  try {
    await pollStore();
    if (storeUi.running) { await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv }) }); }
    else {
      const hotbar = $('#st-hotbar').checked;
      const plan = await api('/api/auto/storeplan?' + new URLSearchParams({ srv, hotbar: hotbar ? '1' : '0' }));
      if (!plan.stops.length) {
        toast(`Einlagern: keine Kiste im Umkreis von ${plan.radius} m hat deine Items. Öffne die Kiste im Spiel einmal oder wähle sie auf der Karte und lagere dort ein.`);
        return;
      }
      const where = plan.stops.slice(0, 8).map((s) => `· ${s.n} Stacks → ${s.name} (${s.dist} m)`).join('\n') + (plan.stops.length > 8 ? `\n· und ${plan.stops.length - 8} weitere Kisten` : '');
      if (!confirm(`Inventar einlagern?\n\nBaritone bringt jeden Stack in eine Kiste im Umkreis von ${plan.radius} m, in der dieses Item schon liegt:\n${where}` + (plan.left.length ? `\n\nBleibt bei dir (keine passende Kiste): ${plan.left.slice(0, 6).join(', ')}` : '') + (hotbar ? '' : '\n\nDie Hotbar bleibt, wie sie ist.'))) return;
      await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv, store: true, hotbar }) });
      if (!storeUi.timer) storeUi.timer = setInterval(pollStore, 1500);
    }
  } catch (e) { toast('Einlagern: ' + e.message); }
  pollStore();
});
setTimeout(pollStore, 1500);   // a run that is still going shows "stop" right away

$('#ab-toggle').addEventListener('click', async () => {
  const a = ctl.auto, pl = selPl();
  const srv = (state.player && state.player.server) || state.server;
  const running = a && (a.mode || 'build') === 'build' && ['plan', 'fetch', 'approach', 'build', 'unpack', 'return', 'fix'].includes(a.phase);
  if (!running && !pl) return;
  if (!running && a && (a.mode === 'farm' || a.mode === 'flatten') && ['fplan', 'mine', 'deposit'].includes(a.phase) && !confirm(a.mode === 'flatten' ? 'Gerade wird geebnet. Ebnen beenden und stattdessen bauen?' : 'Gerade wird gefarmt. Farmen beenden und stattdessen bauen?')) return;
  try {
    ctl.auto = await api('/api/auto', { method: 'POST', body: JSON.stringify(running ? { srv } : { srv, pid: pl.id }) });
  } catch (e) { toast('Autobau: ' + e.message); }
  renderAuto();
});
setInterval(pollControl, 1500);

async function control(body) {
  ctl.pending = Date.now();
  try {
    ctl.state = await jsonPost('/api/control', { srv: (state.player && state.player.server) || state.server, ...body });
    renderBuild();
  } catch (e) { toast('Printer: ' + e.message); }
}

function renderBuild() {
  const c = ctl.state;
  const g = c && c.game;
  const ly = g && g.layer;
  const pl = selPl();
  const gs = gameStatus();
  let txt;
  if (!gs.online) txt = 'Spiel offline (Status unten rechts)';
  else if (!c || !c.online) txt = 'Spiel verbunden, meldet sich noch nicht';
  else {
    const parts = [];
    parts.push(`Printer ${c.web && c.web.on ? '<b class="ok">AN</b>' : 'aus'}`);
    if (ly && ly.available) {
      parts.push(ly.mode === 'ALL' ? 'Litematica: alle Schichten' : ly.mode === 'SINGLE_LAYER' ? `Litematica: Schicht Y ${ly.single}` :
        ly.mode === 'LAYER_RANGE' ? `Litematica: Y ${ly.min} bis ${ly.max}` : `Litematica: ${ly.mode.toLowerCase()}`);
    }
    txt = parts.join(' · ');
  }
  $('#bd-game').innerHTML = txt;
  $('#bd-game').classList.toggle('muted', !(c && c.online));
  statusText(true);
  // Only update the slider when nobody is dragging it right now
  const idle = Date.now() - ctl.pending > 2500;
  renderWebPrinter(c, g, idle);
  const auto = c && pl && c.auto === pl.id;
  $('#ly-auto').checked = !!auto;
  if (idle && document.activeElement !== $('#ly-y')) {
    const y = ly && ly.available && ly.mode !== 'ALL' ? (ly.mode === 'SINGLE_LAYER' ? ly.single : ly.min) : (pl ? pl.y : '');
    if ($('#ly-y').value === '' || (ly && ly.mode !== 'ALL')) $('#ly-y').value = y;
  }
  $('#ly-info').textContent = auto ? (c.autoInfo || 'rechnet …') : c && c.auto ? 'Automatik läuft für eine andere Schematic' : '';
  $('#bd-build').disabled = !(pl && pl.ingame);
  $('#bd-build').title = pl && pl.ingame ? 'Baritone baut die Schematic aus Litematica (#litematica)' : 'Erst „Ins Spiel“ einschalten';
}

/* Webmatica's own printer: the viewer holds the settings, the mod reports what it does */
function renderWebPrinter(c, g, idle) {
  const want = c && c.web, have = g && g.web;
  const on = !!(want && want.on);
  $('#wp-toggle').textContent = !want ? 'Printer: ?' : on ? 'Printer AN, klicken zum Ausschalten' : 'Printer aus, klicken zum Einschalten';
  $('#wp-toggle').classList.toggle('on', on);
  if (want && idle) { $('#wp-range').value = want.range; $('#wp-bps').value = want.bps; }
  const safe = !!(c && c.safe);
  $('#wp-range-v').textContent = Number($('#wp-range').value).toFixed(1);
  $('#wp-bps-v').textContent = safe && Number($('#wp-bps').value) > 5 ? '5 (Server-safe)' : $('#wp-bps').value;
  $('#wp-sum').textContent = on ? (safe ? '· an, server-safe' : '· an') : '';
  const parts = [];
  if (on && !have) parts.push('Spiel meldet sich noch nicht');
  if (on && have) {
    if (!schem.list.some((p) => p.ingame)) parts.push('Keine Schematic ist „Ins Spiel“ geschaltet');
    parts.push(`${fmt(have.placed)} gesetzt`);
    if (have.todo) parts.push(`${fmt(have.todo)} offen in der Nähe`);
    if (have.wrong) parts.push(`${fmt(have.wrong)} falsche Blöcke im Weg (werden nicht abgebaut)`);
    if (have.blocked) parts.push(safe ? `${fmt(have.blocked)} gerade nicht erreichbar oder nur mit anderer Blickrichtung` : `${fmt(have.blocked)} ohne Halt`);
    if (have.noItem) parts.push(`fehlt: ${have.missing.map((m) => esc(m.split(':').pop())).join(', ')}`);
  }
  $('#wp-info').innerHTML = parts.join(' · ');
}
$('#wp-toggle').addEventListener('click', () => {
  const want = ctl.state && ctl.state.web;
  if (want) control({ web: { on: !want.on } });
});
$('#wp-range').addEventListener('input', () => { ctl.pending = Date.now(); renderBuild(); });
$('#wp-bps').addEventListener('input', () => { ctl.pending = Date.now(); renderBuild(); });
$('#wp-range').addEventListener('change', (e) => control({ web: { range: Number(e.target.value) } }));
$('#wp-bps').addEventListener('change', (e) => control({ web: { bps: Number(e.target.value) } }));

$('#ly-all').addEventListener('click', () => control({ layer: { mode: 'all' }, auto: null }));
const setLayer = (y) => { $('#ly-y').value = y; control({ layer: { mode: 'range', min: y, max: y }, auto: null }); };
$('#ly-set').addEventListener('click', () => setLayer(Number($('#ly-y').value)));
$('#ly-up').addEventListener('click', () => setLayer(Number($('#ly-y').value) + 1));
$('#ly-down').addEventListener('click', () => setLayer(Number($('#ly-y').value) - 1));
$('#ly-y').addEventListener('keydown', (e) => { if (e.key === 'Enter') setLayer(Number(e.target.value)); });
$('#ly-auto').addEventListener('change', (e) => {
  const pl = selPl();
  if (e.target.checked && pl && !pl.ingame) toast('Tipp: „Ins Spiel“ einschalten, sonst sieht Litematica die Schematic nicht');
  control({ auto: e.target.checked && pl ? pl.id : null });
});

$('#bd-build').addEventListener('click', () => { const pl = selPl(); if (pl) baritone({ kind: 'litematica', pid: pl.id }); });
$('#bd-pause').addEventListener('click', () => baritone({ kind: 'pause' }));
$('#bd-resume').addEventListener('click', () => baritone({ kind: 'resume' }));
$('#bd-stop').addEventListener('click', () => baritone({ kind: 'stop' }));
$('#bd-clear').addEventListener('click', () => {
  const pl = selPl();
  if (!pl) return;
  const [w, h, l] = pl.size;
  const done = schem.check ? schem.check.stats.ok : 0;
  if (!confirm(`Baritone baut ALLES im Rahmen ${pl.x} ${pl.y} ${pl.z} bis ${pl.x + w - 1} ${pl.y + h - 1} ${pl.z + l - 1} ab (${fmt(w * h * l)} Blöcke).` +
    (done ? `\n\nAchtung: ${fmt(done)} Blöcke der Schematic stehen schon und würden mit abgebaut.` : '') + '\n\nWirklich starten?')) return;
  baritone({ kind: 'clear', x0: pl.x, y0: pl.y, z0: pl.z, x1: pl.x + w - 1, y1: pl.y + h - 1, z1: pl.z + l - 1 });
});

export { AB_PHASE, ctl, highlightChests, renderAuto };
