/* Webmatica map UI: Server-safe switch (options), per server. */
import './xray.js';   // runs before this file (load order, see index.html)
import { $, api, state, toast } from './core.js';
import { jsonPost } from './schematics.js';
import { xray, xrayToggle } from './xray.js';

// ------------------------------------------------------------------ Server-safe

const srvNow = () => (state.player && state.player.server) || state.server;

function renderSafe(on) {
  state.safe = !!on;
  $('#opt-safe').checked = state.safe;
  $('#safe-sum').textContent = state.safe ? '· an' : '';
  $('#btn-xray').title = state.safe ? 'X-Ray ist aus (Server-safe)' : 'X-Ray: nur gesuchte Blöcke zeigen (X)';
  if (state.safe && xray.on) xrayToggle(false);
}

async function pollSafe() {
  const srv = srvNow();
  if (!srv || document.hidden) return;
  try { renderSafe((await api('/api/control/state?' + new URLSearchParams({ srv }))).safe); } catch (_) { /* viewer busy */ }
}

$('#opt-safe').addEventListener('change', async (e) => {
  const srv = srvNow();
  if (!srv) { e.target.checked = false; toast('Erst eine Welt wählen'); return; }
  try {
    renderSafe((await jsonPost('/api/control', { srv, safe: e.target.checked })).safe);
    toast(state.safe ? 'Server-safe an: Bot-Funktionen und X-Ray sind aus' : 'Server-safe aus');
  } catch (err) { toast('Server-safe: ' + err.message); }
});
setInterval(pollSafe, 3000);
setTimeout(pollSafe, 500);

export { pollSafe };
