/* Webmatica map UI: Building automation: Baritone, printer, auto-build, guard switches, storing. */
'use strict';

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
  $('#ab-toggle').disabled = !running && !(pl && pl.ingame);
  $('#ab-toggle').title = running || (pl && pl.ingame) ? '' : 'Erst „Ins Spiel“ einschalten, damit Baritone die Schematic aus Litematica bauen kann';
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
  if (document.activeElement !== $('#gd-friends')) $('#gd-friends').value = (g.friends || []).join(', ');
  const on = [g.radar && 'Spieler-Pause', g.eat && 'Essen', g.tools && 'Werkzeug', g.fix && 'Korrektur', g.leftovers && 'Reste'].filter(Boolean);
  $('#gd-sum').textContent = on.length ? '· ' + on.join(', ') : '· alles aus';
}
async function saveGuard(patch) {
  try { guard.cfg = await api('/api/auto/settings', { method: 'POST', body: JSON.stringify(patch) }); } catch (e) { toast('Speichern: ' + e.message); }
  renderGuard();
}
$$('[data-guard]').forEach((c) => c.addEventListener('change', () => saveGuard({ [c.dataset.guard]: c.checked })));
$('#gd-dist').addEventListener('change', (e) => saveGuard({ radarDist: Number(e.target.value) || 64 }));
$('#gd-friends').addEventListener('change', (e) => saveGuard({ friends: e.target.value.split(',') }));
api('/api/auto/settings').then((g) => { guard.cfg = g; renderGuard(); }).catch(() => {});

/* Storing (chests tab): runs in the same flow as auto-build */
const storeUi = { timer: null };
async function pollStore() {
  const srv = (state.player && state.player.server) || state.server;
  let a = null;
  try { a = await api('/api/auto?' + new URLSearchParams({ srv })); } catch (_) { /* ignore */ }
  const running = a && a.mode === 'store' && a.phase === 'store';
  $('#st-go').textContent = running ? '■ Einlagern stoppen' : 'Inventar einlagern';
  if (a && a.mode === 'store') {
    $('#st-info').innerHTML = `<b class="${a.phase === 'error' ? 'bad' : a.phase === 'done' ? 'ok' : ''}">${running ? 'lagert ein' : a.phase === 'done' ? 'fertig' : 'angehalten'}</b> · ${esc(a.info || '')}`;
  }
  if (!running && storeUi.timer) { clearInterval(storeUi.timer); storeUi.timer = null; loadContainers(); }
}
$('#st-go').addEventListener('click', async () => {
  const srv = (state.player && state.player.server) || state.server;
  try {
    if (storeUi.timer) { await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv }) }); }
    else {
      if (!confirm('Inventar einlagern?\n\nBaritone läuft zu deinen Kisten und legt jeden Stack dorthin, wo dieses Item schon liegt. Was in keine Kiste passt, bleibt bei dir.' + ($('#st-hotbar').checked ? '' : '\n\nDie Hotbar bleibt, wie sie ist.'))) return;
      await api('/api/auto', { method: 'POST', body: JSON.stringify({ srv, store: true, hotbar: $('#st-hotbar').checked }) });
      storeUi.timer = setInterval(pollStore, 1500);
    }
  } catch (e) { toast('Einlagern: ' + e.message); }
  pollStore();
});

$('#ab-toggle').addEventListener('click', async () => {
  const a = ctl.auto, pl = selPl();
  const srv = (state.player && state.player.server) || state.server;
  const running = a && (a.mode || 'build') === 'build' && ['plan', 'fetch', 'approach', 'build', 'unpack', 'return', 'fix'].includes(a.phase);
  if (!running && !pl) return;
  if (!running && a && (a.mode === 'farm' || a.mode === 'flatten') && ['fplan', 'mine', 'deposit'].includes(a.phase) && !confirm(a.mode === 'flatten' ? 'Gerade wird geebnet. Ebnen beenden und stattdessen bauen?' : 'Gerade wird gefarmt. Farmen beenden und stattdessen bauen?')) return;
  if (!running && !confirm('Autobau starten?\n\nBaritone läuft zu deinen Kisten, nimmt das fehlende Material und baut die Schematic. Unterwegs baut es nichts ab, beim Bauen selbst schon (falsche Blöcke im Rahmen).\n\nShulker im Inventar packt er neben sich aus und baut sie wieder ab.\\n\\nAbbrechen jederzeit mit „■ Stopp“.')) return;
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
  const pr = g && g.printer, ly = g && g.layer;
  const pl = selPl();
  let txt;
  if (!c || !c.online) txt = 'Spiel nicht verbunden (Minecraft mit Mod 0.3.2 starten)';
  else {
    const parts = [];
    parts.push(pr && pr.available ? `Printer ${pr.on ? '<b class="ok">AN</b>' : 'aus'}` : 'kein Printer gefunden');
    if (ly && ly.available) {
      parts.push(ly.mode === 'ALL' ? 'Litematica: alle Schichten' : ly.mode === 'SINGLE_LAYER' ? `Litematica: Schicht Y ${ly.single}` :
        ly.mode === 'LAYER_RANGE' ? `Litematica: Y ${ly.min} bis ${ly.max}` : `Litematica: ${ly.mode.toLowerCase()}`);
    }
    txt = parts.join(' · ');
  }
  $('#bd-game').innerHTML = txt;
  $('#bd-game').classList.toggle('muted', !(c && c.online));
  // Only update the slider when nobody is dragging it right now
  const idle = Date.now() - ctl.pending > 2500;
  const ok = !!(pr && pr.available);
  $('#pr-toggle').textContent = ok ? (pr.on ? 'Printer AN, klicken zum Ausschalten' : 'Printer aus, klicken zum Einschalten') : 'Printer: nicht verbunden';
  $('#pr-toggle').classList.toggle('on', ok && pr.on);
  for (const el of $$('#pr-range, #pr-interval, [data-pr]')) el.disabled = !ok;
  if (ok && idle) {
    $('#pr-range').value = pr.range; $('#pr-interval').value = pr.interval;
    $$('[data-pr]').forEach((cb) => { cb.checked = !!pr[cb.dataset.pr]; });
  }
  $('#pr-range-v').textContent = Number($('#pr-range').value).toFixed(1);
  $('#pr-interval-v').textContent = $('#pr-interval').value + ' Ticks';
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

$('#pr-toggle').addEventListener('click', () => {
  const pr = ctl.state && ctl.state.game && ctl.state.game.printer;
  if (pr && pr.available) control({ printer: { on: !pr.on } });
});
$('#pr-range').addEventListener('input', () => { ctl.pending = Date.now(); renderBuild(); });
$('#pr-interval').addEventListener('input', () => { ctl.pending = Date.now(); renderBuild(); });
$('#pr-range').addEventListener('change', (e) => control({ printer: { range: Number(e.target.value) } }));
$('#pr-interval').addEventListener('change', (e) => control({ printer: { interval: Number(e.target.value) } }));
$$('[data-pr]').forEach((cb) => cb.addEventListener('change', () => control({ printer: { [cb.dataset.pr]: cb.checked } })));
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
